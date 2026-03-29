# Simplified Data Flow Guide

A beginner-friendly walkthrough of what actually happens when you call each API endpoint.
No jargon without explanation. Real data examples throughout.

---

## The Big Idea (read this first)

A normal app stores **current state**:
```
accounts table:
┌────────┬───────┬─────────┐
│ id     │ owner │ balance │
│ abc123 │ Alice │  850.00 │   ← just the current value, history is gone
└────────┴───────┴─────────┘
```

This app stores **what happened** (events), never the current state:
```
account_events table:
┌────────┬─────────┬──────────────────────────────────────────────────┐
│ agg_id │ version │ event_data                                       │
├────────┼─────────┼──────────────────────────────────────────────────┤
│ abc123 │    1    │ {"type":"AccountOpened",  "balance": 1000.00}    │
│ abc123 │    2    │ {"type":"MoneyWithdrawn", "amount":  100.00}     │
│ abc123 │    3    │ {"type":"MoneyDeposited", "amount":   50.00}     │
└────────┴─────────┴──────────────────────────────────────────────────┘
           ↑ always goes up, never updated, never deleted
```

To get the balance you **replay** events: 1000 - 100 + 50 = **950.00**

**Why?** Full audit history, time travel queries, debugging, and you can always rebuild any view of data.

---

## Flow 1 — Open an Account

**You call:**
```http
POST /api/v1/accounts
{ "owner": "Alice", "initialBalance": 1000.00 }
```

**What happens inside, step by step:**

```
  REST Controller
       │
       ▼
  AccountCommandService.openAccount()
       │
       ├─ 1. Creates an AccountAggregate object (just in memory, nothing saved yet)
       │      aggregate.id      = "abc-123" (random UUID)
       │      aggregate.balance = 1000.00
       │      aggregate.version = 1
       │      aggregate.pendingEvents = [AccountOpened{...}]
       │                                 ↑ event is queued, not saved yet
       │
       ├─ 2. Calls eventStore.append()  →  INSERT into PostgreSQL:
       │
       │      account_events:
       │      ┌──────────┬─────────┬─────────────────────────────────────────────┐
       │      │ agg_id   │ version │ event_data                                  │
       │      │ abc-123  │    1    │ {"type":"AccountOpened",                    │
       │      │          │         │  "aggregateId":"abc-123",                   │
       │      │          │         │  "owner":"Alice",                           │
       │      │          │         │  "initialBalance":1000.00,                  │
       │      │          │         │  "occurredAt":"2024-01-15T10:30:00Z"}       │
       │      └──────────┴─────────┴─────────────────────────────────────────────┘
       │
       └─ 3. Publishes event internally → AccountProjectionUpdater receives it
                  │
                  └─ INSERT into MongoDB:
                         accounts collection:
                         {
                           "_id": "abc-123",
                           "owner": "Alice",
                           "balance": 1000.00,
                           "eventCount": 1,
                           "lastUpdated": "2024-01-15T10:30:00Z"
                         }
                         ↑ this is the "read model" — a pre-built snapshot for fast queries
```

**You get back:**
```json
{ "accountId": "abc-123" }
```

> **Why two databases?**
> PostgreSQL = source of truth (events). MongoDB = fast read cache (current state snapshot).
> This separation is called **CQRS** (Command Query Responsibility Segregation).

---

## Flow 2 — Deposit Money

**You call:**
```http
POST /api/v1/accounts/abc-123/deposit
{ "amount": 200.00, "description": "Salary" }
```

**What happens:**

```
  AccountCommandService.deposit()
       │
       ├─ 1. Load account from PostgreSQL:
       │      SELECT * FROM account_events WHERE aggregate_id = 'abc-123' ORDER BY version
       │      → returns [AccountOpened{balance:1000}]
       │
       ├─ 2. Replay events to rebuild state in memory:
       │      start: balance = 0
       │      apply AccountOpened  → balance = 1000.00   ← current state rebuilt
       │      current version = 1
       │
       ├─ 3. Run business logic: balance + 200 = 1200.00
       │      Queue new event: MoneyDeposited{amount:200}
       │
       ├─ 4. Save to PostgreSQL (version MUST be 2, not 1):
       │      ┌──────────┬─────────┬──────────────────────────────────────────┐
       │      │ abc-123  │    1    │ {"type":"AccountOpened",  "balance":1000} │  ← existing
       │      │ abc-123  │    2    │ {"type":"MoneyDeposited", "amount":200}   │  ← NEW
       │      └──────────┴─────────┴──────────────────────────────────────────┘
       │
       └─ 5. Update MongoDB snapshot:
              { "_id":"abc-123", "balance": 1200.00, "eventCount": 2 }
```

> **What is Optimistic Locking?**
> The `version` column has a UNIQUE constraint: `UNIQUE(aggregate_id, version)`.
> If two requests try to save version=2 at the same time, the second one gets a DB error.
> This prevents double-spending without locking rows — the failed request just retries.

---

## Flow 3 — Get Balance (Event Replay)

**You call:**
```http
GET /api/v1/accounts/abc-123/balance
```

**What happens:**

```
  AccountQueryService.getBalance()
       │
       ├─ 1. Load ALL events from PostgreSQL:
       │      [AccountOpened{1000}, MoneyDeposited{200}, MoneyWithdrawn{100}]
       │
       └─ 2. Replay from scratch in memory:
              balance = 0
              + AccountOpened{1000}    → balance = 1000.00
              + MoneyDeposited{200}    → balance = 1200.00
              + MoneyWithdrawn{100}    → balance = 1100.00
                                              ↑ this is what you get back
```

> **Time Travel Query:** You can ask "what was the balance on January 10th?"
> ```http
> GET /api/v1/accounts/abc-123/balance?asOf=2024-01-10T12:00:00Z
> ```
> It replays only events that occurred BEFORE that timestamp. You can query any point in history.
> This is impossible in a normal app that only stores current state.

---

## Flow 4 — Transfer Money (the complex one)

A transfer involves **two accounts** — if we update them in a single DB transaction, it works fine locally, but falls apart across microservices. Kafka + the **Saga pattern** solves this.

**You call:**
```http
POST /api/v1/transfers
{ "fromAccountId": "abc-123", "toAccountId": "xyz-789", "amount": 300.00 }
```

**Full flow (6 steps):**

```
STEP 1 — Write to PostgreSQL + Outbox atomically
─────────────────────────────────────────────────
  AccountCommandService
       │
       ├─ Debit abc-123: append TransferRequested event (version 3)
       │
       └─ In SAME DB transaction, write to outbox table:
              transfer_outbox:
              ┌─────────────┬──────────────────────────────────────────────┐
              │ transfer_id │ payload                                      │
              │ transfer-99 │ {"from":"abc-123","to":"xyz-789","amt":300}  │
              │ published   │ false                                        │
              └─────────────┴──────────────────────────────────────────────┘
              ↑ if the app crashes here, the outbox row survives in DB

STEP 2 — Background publisher picks it up
──────────────────────────────────────────
  OutboxPublisher (runs every 200ms)
       │
       ├─ SELECT * FROM transfer_outbox WHERE published = false
       │
       ├─ Sends message to Kafka topic: "transfer.requested"
       │
       └─ UPDATE transfer_outbox SET published = true

STEP 3 — Saga coordinator credits the target account
─────────────────────────────────────────────────────
  TransferSagaCoordinator (Kafka consumer on "transfer.requested")
       │
       ├─ Load xyz-789 from PostgreSQL, replay events
       │
       ├─ Append MoneyDeposited{300, "Transfer from abc-123"} to xyz-789
       │
       └─ Send result to Kafka topic: "transfer.result"
              { "transferId":"transfer-99", "success": true }

STEP 4 — Result consumer finalizes the source account
──────────────────────────────────────────────────────
  TransferResultConsumer (Kafka consumer on "transfer.result")
       │
       └─ Append TransferCompleted event to abc-123 (marks transfer as done)


FINAL STATE in PostgreSQL:
┌──────────┬─────────┬──────────────────────────────────────────────────┐
│ abc-123  │    1    │ AccountOpened{balance:1000}                      │
│ abc-123  │    2    │ MoneyDeposited{200}                              │
│ abc-123  │    3    │ TransferRequested{to:xyz-789, amount:300}        │
│ abc-123  │    4    │ TransferCompleted{transferId:transfer-99}        │
├──────────┼─────────┼──────────────────────────────────────────────────┤
│ xyz-789  │    1    │ AccountOpened{balance:500}                       │
│ xyz-789  │    2    │ MoneyDeposited{300, "Transfer from abc-123"}     │
└──────────┴─────────┴──────────────────────────────────────────────────┘
```

**What if the transfer fails?** (e.g. target account doesn't exist)

```
TransferSagaCoordinator
     │
     └─ Sends { "success": false, "reason": "Account not found" }

TransferResultConsumer
     │
     └─ Appends TransferFailed event to abc-123
          + Appends MoneyDeposited (REFUND) back to abc-123
            ↑ this is a "compensating event" — we NEVER delete events,
              we add a new one that undoes the effect
```

> **Why Kafka?** It guarantees the message is delivered even if the app restarts mid-transfer.
> The Outbox pattern ensures we never send to Kafka without also saving to the DB first (atomicity).

---

## Flow 5 — List Accounts (CQRS Read)

**You call:**
```http
GET /api/v1/accounts
```

**What happens:**

```
  AccountController
       │
       └─ accountProjectionRepository.findAll()
              │
              └─ Queries MongoDB directly (no event replay!)
                     Returns pre-built snapshots:
                     [
                       { "id":"abc-123", "owner":"Alice", "balance":1100.00 },
                       { "id":"xyz-789", "owner":"Bob",   "balance":800.00  }
                     ]
```

> **Why not just replay events every time?**
> For 1 account: fast enough. For 10,000 accounts with 500 events each: 5 million rows read per request.
> MongoDB holds a pre-computed snapshot that's updated after every event — reads are instant.

---

## Summary: Where Does Each Piece of Data Live?

```
┌─────────────────────────────────────────────────────────────────┐
│  PostgreSQL (port 5433 externally, 5432 internally)             │
│                                                                 │
│  account_events  ← the ONLY source of truth                     │
│    every event ever, append-only, never updated/deleted         │
│                                                                 │
│  transfer_outbox ← temporary staging for Kafka messages         │
│    rows set to published=true once sent                         │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  MongoDB (port 27017)                                           │
│                                                                 │
│  accounts collection ← read-optimized snapshots                 │
│    rebuilt from events, always up to date                       │
│    used for fast list/search queries                            │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│  Kafka (port 29092 externally)                                  │
│                                                                 │
│  transfer.requested ← saga trigger messages                     │
│  transfer.result    ← saga outcome messages                     │
└─────────────────────────────────────────────────────────────────┘
```

---

## Key Terms Cheat Sheet

| Term | Plain English |
|------|---------------|
| **Event** | A record of something that happened ("Money was deposited") |
| **Event Store** | The PostgreSQL table that holds all events |
| **Aggregate** | An in-memory object rebuilt by replaying events (AccountAggregate) |
| **Event Replay** | Reading all past events and applying them one by one to get current state |
| **CQRS** | Using separate storage for writes (PostgreSQL) and reads (MongoDB) |
| **Projection** | The MongoDB snapshot — a "view" derived from events |
| **Saga** | A multi-step distributed transaction coordinated via Kafka messages |
| **Outbox Pattern** | Writing to a DB table first, then publishing to Kafka, to avoid data loss |
| **Compensating Event** | A new event that undoes a previous one (refund instead of delete) |
| **Optimistic Locking** | Preventing conflicts by rejecting duplicate version numbers |
| **Temporal Query** | Querying state at a specific point in time by replaying events up to that moment |
