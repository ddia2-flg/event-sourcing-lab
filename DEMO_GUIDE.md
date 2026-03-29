# Event Sourcing & CQRS — Step-by-Step Demo Guide

A hands-on walkthrough of every event-sourcing and CQRS concept in this project.
Follow the steps in order — each demo builds on the previous one.

---

## Prerequisites

- Docker & Docker Compose installed
- Terminal open in the project root
- (Optional) A PostgreSQL client (DBeaver, TablePlus, or `psql`)
- (Optional) MongoDB Compass for viewing CQRS projections

---

## 1. Launch the Project

```bash
docker compose up --build
```

Wait ~30 seconds until you see `Started EventSourcingApplication` in the logs.

Open Swagger UI: **http://localhost:8080/swagger-ui.html**

### Connect to databases (optional, for showing internals)

**PostgreSQL:**
```
Host: localhost:5433
Database: eventstore
User: es_user
Password: es_pass
```

**MongoDB:**
```
URI: mongodb://localhost:27017/accountview
```

---

## 2. Core Concept: State is Derived from Events

> Traditional apps store current state (UPDATE balance SET 1300).
> Event sourcing stores **what happened** — the balance is computed by replaying events.

### 2.1 — Open an account

In Swagger, expand **POST /api/v1/accounts** and execute:

```json
{
  "owner": "Alice",
  "initialBalance": 1000.00
}
```

Copy the `accountId` from the response. You'll use it in all following steps.

```bash
# Or via curl:
curl -s -X POST http://localhost:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"owner":"Alice","initialBalance":1000}' | jq
```

### 2.2 — Look at the event store (the "aha" moment)

```sql
SELECT id, aggregate_id, version, event_type, payload, occurred_at
FROM account_events
ORDER BY id;
```

You will see **one row**:

| version | event_type    | payload                                            |
|---------|---------------|----------------------------------------------------|
| 1       | AccountOpened | `{"owner":"Alice","initialBalance":1000.00, ...}`  |

There is **no `balance` column** anywhere in the database.
The balance *is* the sum of all events.

### 2.3 — Deposit money

```json
POST /api/v1/accounts/{accountId}/deposits
{
  "amount": 500.00,
  "description": "March salary"
}
```

### 2.4 — Withdraw money

```json
POST /api/v1/accounts/{accountId}/withdrawals
{
  "amount": 200.00,
  "description": "Rent payment"
}
```

### 2.5 — Check the event store again

```sql
SELECT version, event_type, payload FROM account_events ORDER BY version;
```

| version | event_type     | payload (key fields)              |
|---------|----------------|-----------------------------------|
| 1       | AccountOpened  | initialBalance: 1000.00           |
| 2       | MoneyDeposited | amount: 500.00, desc: "March salary" |
| 3       | MoneyWithdrawn | amount: 200.00, desc: "Rent payment" |

**The balance is 1000 + 500 - 200 = 1300.** The server computes this by replaying all 3 events.

### 2.6 — Get the current balance (replay in action)

```
GET /api/v1/accounts/{accountId}/balance
```

Response:
```json
{
  "accountId": "...",
  "owner": "Alice",
  "balance": 1300.00,
  "version": 3
}
```

The server loaded all events from PostgreSQL, fed them through `AccountAggregate.reconstitute()`,
and returned the computed state. No stored balance was read.

---

## 3. Full Audit Log — Immutable History

> Every mutation is permanently recorded. You can never accidentally overwrite history.

```
GET /api/v1/accounts/{accountId}/events
```

Response — a complete chronological record:
```json
[
  { "version": 1, "eventType": "AccountOpened",  "occurredAt": "...", "payload": { ... } },
  { "version": 2, "eventType": "MoneyDeposited", "occurredAt": "...", "payload": { ... } },
  { "version": 3, "eventType": "MoneyWithdrawn", "occurredAt": "...", "payload": { ... } }
]
```

In a traditional CRUD app, if the balance is wrong, you have no idea why.
Here, you look at the event log and find the exact moment something went wrong.

---

## 4. Time Travel — Temporal Queries

> Since every event has a timestamp, you can reconstruct the state at any point in the past — for free.

### 4.1 — Note the timestamps

From the event history above, copy the `occurredAt` timestamp of the **MoneyDeposited** event.
The balance at that moment was 1000 + 500 = **1500**.

### 4.2 — Query balance at that past moment

```
GET /api/v1/accounts/{accountId}/balance?asOf=2026-03-28T12:05:00Z
```

(Replace the timestamp with a time **after** the deposit but **before** the withdrawal.)

Response:
```json
{
  "balance": 1500.00,
  "asOf": "2026-03-28T12:05:00Z"
}
```

### 4.3 — Query even further back

Use a timestamp **before** the deposit:

```
GET /api/v1/accounts/{accountId}/balance?asOf=2026-03-28T12:00:00Z
```

Balance: **1000.00** — the original opening balance.

**Key point:** In a traditional CRUD app, `UPDATE balance` destroys the old value forever.
Here, time travel costs nothing — it's just replaying fewer events.

---

## 5. CQRS — Two Models, Two Jobs

> **Write model** (PostgreSQL): optimised for correctness, ordering, and consistency.
> **Read model** (MongoDB): optimised for fast, flexible queries — a denormalized cache.

### 5.1 — List all accounts (impossible with pure event sourcing)

```
GET /api/v1/accounts
```

This returns data from **MongoDB**, not PostgreSQL. With pure event sourcing you'd need to know
all aggregate IDs upfront. The MongoDB projection makes this trivial.

### 5.2 — Fast summary (no event replay)

```
GET /api/v1/accounts/{accountId}/summary
```

This reads directly from MongoDB — instant response, no matter how many events exist.
Compare the fields: it includes `recentEvents` (last 20), `balance`, `owner`, `lastUpdated`.

### 5.3 — Show MongoDB in Compass

Open MongoDB Compass, connect to `mongodb://localhost:27017`, and browse:

```
Database: accountview
Collection: account_projections
```

You'll see a document with pre-computed `balance`, `owner`, and `recentEvents`.

### 5.4 — Do another deposit, watch both sides update

```json
POST /api/v1/accounts/{accountId}/deposits
{
  "amount": 100.00,
  "description": "Bonus"
}
```

Now compare:
- `GET /{accountId}/balance` — reads from **PostgreSQL** via event replay → **1400.00**
- `GET /{accountId}/summary` — reads from **MongoDB** projection → **1400.00**

Both show the same result. The write to PostgreSQL fired a Spring `ApplicationEvent`,
which `AccountProjectionUpdater` caught and updated MongoDB — synchronously, same request.

### 5.5 — Key insight

> If MongoDB goes down, **no data is lost**. PostgreSQL events are the single source of truth.
> MongoDB can be rebuilt at any time by replaying all events from the event store.
> MongoDB is disposable. PostgreSQL is permanent.

---

## 6. Kafka Transfer Saga — Distributed Transactions

> Transfers touch two aggregates (two accounts). A single DB transaction can't span both.
> The **Saga pattern** solves this with a sequence of events and compensating actions.

### 6.1 — Create a second account

```json
POST /api/v1/accounts
{
  "owner": "Bob",
  "initialBalance": 200.00
}
```

Save Bob's `accountId`.

### 6.2 — Initiate a transfer

```json
POST /api/v1/transfers
{
  "sourceAccountId": "<ALICE_ID>",
  "targetAccountId": "<BOB_ID>",
  "amount": 300.00
}
```

Response: `202 Accepted` — the transfer is **not yet complete**, only requested.

```json
{
  "transferId": "...",
  "status": "PENDING"
}
```

### 6.3 — What just happened? (trace the saga)

**Alice's events:**
```
GET /api/v1/accounts/<ALICE_ID>/events
```

| # | event_type        | what happened                      |
|---|-------------------|------------------------------------|
| 1 | AccountOpened     | Balance: 1000                      |
| 2 | MoneyDeposited    | +500 (salary)                      |
| 3 | MoneyWithdrawn    | -200 (rent)                        |
| 4 | MoneyDeposited    | +100 (bonus)                       |
| 5 | TransferRequested | -300 (debited immediately)         |
| 6 | TransferCompleted | saga finished, debit is confirmed  |

**Bob's events:**
```
GET /api/v1/accounts/<BOB_ID>/events
```

| # | event_type     | what happened                             |
|---|----------------|-------------------------------------------|
| 1 | AccountOpened  | Balance: 200                              |
| 2 | MoneyDeposited | +300 (credited by saga coordinator)       |

**Final balances:**
- Alice: 1000 + 500 - 200 + 100 - 300 = **1100**
- Bob: 200 + 300 = **500**

### 6.4 — Show the transactional outbox

```sql
SELECT id, transfer_id, event_type, published, created_at
FROM transfer_outbox;
```

The row has `published = true`. The **Transactional Outbox Pattern** works like this:

1. The app writes the event + outbox row in the **same DB transaction**
2. A `@Scheduled` background job reads unpublished rows and sends them to Kafka
3. After Kafka confirms, the row is marked `published = true`

This guarantees the Kafka message is eventually sent, even if the app crashes right after writing the event.

### 6.5 — The saga flow (draw on whiteboard)

```
POST /transfers
  |
  v
[AccountCommandService]
  - Debit Alice (append TransferRequested)
  - Write outbox row (same transaction)
  |
  v
[OutboxPublisher @Scheduled every 200ms]
  - Read unpublished outbox rows
  - Send to Kafka topic: transfer.requested
  - Mark as published
  |
  v
[TransferSagaCoordinator @KafkaListener]
  - Load Bob's aggregate
  - Credit Bob (append MoneyDeposited)
  - Send to Kafka topic: transfer.result (success=true)
  |
  v
[TransferResultConsumer @KafkaListener]
  - Append TransferCompleted to Alice's stream
  - Saga complete!
```

---

## 7. Saga Failure & Compensation

> What if the target account doesn't exist? The debit already happened.
> In event sourcing, you don't rollback — you emit a **compensating event**.

### 7.1 — Transfer to a non-existent account

```json
POST /api/v1/transfers
{
  "sourceAccountId": "<ALICE_ID>",
  "targetAccountId": "00000000-0000-0000-0000-000000000000",
  "amount": 100.00
}
```

### 7.2 — Check Alice's events (wait 1-2 seconds for Kafka)

```
GET /api/v1/accounts/<ALICE_ID>/events
```

You'll see three new events at the end:

| # | event_type        | what happened                                |
|---|-------------------|----------------------------------------------|
| 7 | TransferRequested | -100 (debited immediately)                   |
| 8 | TransferFailed    | reason: "Account not found: 00000000-..."    |
| 9 | MoneyDeposited    | +100 (compensating credit — balance restored)|

**The TransferRequested event is never deleted or modified.**
The history faithfully records: a transfer was attempted, it failed, and it was compensated.

### 7.3 — Verify Alice's balance is restored

```
GET /api/v1/accounts/<ALICE_ID>/balance
```

Balance: **1100.00** — same as before the failed transfer.

---

## 8. Optimistic Locking — Concurrency Control

> The event store prevents two concurrent writers from corrupting the same aggregate.

### 8.1 — How it works

The `account_events` table has:

```sql
CONSTRAINT uq_aggregate_version UNIQUE (aggregate_id, version)
```

When two requests simultaneously try to append version 5 for the same account:
- One succeeds
- The other gets a **409 Conflict**: `"Concurrent modification detected"`

No lost updates. No phantom writes. Show the constraint:

```sql
\d account_events
-- or
SELECT conname, contype FROM pg_constraint WHERE conrelid = 'account_events'::regclass;
```

### 8.2 — Trigger it (optional)

Open two terminals and send simultaneous deposits:

```bash
# Terminal 1
curl -X POST http://localhost:8080/api/v1/accounts/<ALICE_ID>/deposits \
  -H "Content-Type: application/json" \
  -d '{"amount":1,"description":"race-1"}'

# Terminal 2 (run at the exact same time)
curl -X POST http://localhost:8080/api/v1/accounts/<ALICE_ID>/deposits \
  -H "Content-Type: application/json" \
  -d '{"amount":1,"description":"race-2"}'
```

One returns `200 OK`, the other returns `409 Conflict`.

---

## 9. Insufficient Funds — Domain Validation

> The aggregate validates business rules before emitting events.

### 9.1 — Try to withdraw more than the balance

```json
POST /api/v1/accounts/<ALICE_ID>/withdrawals
{
  "amount": 999999.00,
  "description": "Trying to overdraw"
}
```

Response: `422 Unprocessable Entity`

```json
{
  "title": "Insufficient Funds",
  "status": 422,
  "detail": "Account <id> has balance 1100.00, requested 999999.00"
}
```

No event was written. The aggregate rejected the command before any event was created.
This is the **Command → Validate → Event** pattern at work.

---

## 10. Cleanup

```bash
# Stop all containers
docker compose down

# Stop and remove data volumes (full reset)
docker compose down -v
```

---

## Quick Reference — All Endpoints

| Method | Endpoint                              | Source    | Description                    |
|--------|---------------------------------------|-----------|--------------------------------|
| POST   | `/api/v1/accounts`                    | PG write  | Open account                   |
| POST   | `/api/v1/accounts/{id}/deposits`      | PG write  | Deposit money                  |
| POST   | `/api/v1/accounts/{id}/withdrawals`   | PG write  | Withdraw money                 |
| GET    | `/api/v1/accounts/{id}/balance`       | PG read   | Current balance (event replay) |
| GET    | `/api/v1/accounts/{id}/balance?asOf=` | PG read   | Balance at past timestamp      |
| GET    | `/api/v1/accounts/{id}/events`        | PG read   | Full event history             |
| GET    | `/api/v1/accounts`                    | Mongo read| List all accounts (CQRS)       |
| GET    | `/api/v1/accounts/{id}/summary`       | Mongo read| Fast summary (CQRS, no replay) |
| POST   | `/api/v1/transfers`                   | Kafka saga| Async transfer between accounts|

## Concepts Cheat Sheet

| Concept              | Where                            | How to demo                            |
|----------------------|----------------------------------|----------------------------------------|
| Append-only store    | `account_events` table           | Query the table after each operation   |
| State via replay     | `AccountAggregate.reconstitute()`| `GET /balance` after multiple ops      |
| Immutable audit log  | Same table                       | `GET /{id}/events`                     |
| Time travel          | `loadUpTo(asOf)`                 | `GET /balance?asOf=<timestamp>`        |
| CQRS write model     | PostgreSQL                       | Events, versions, optimistic lock      |
| CQRS read model      | MongoDB `account_projections`    | `GET /accounts`, `GET /{id}/summary`   |
| Kafka saga           | `transfer.requested/result`      | `POST /transfers`, check both accounts |
| Transactional outbox | `transfer_outbox` table          | Query the table during a transfer      |
| Compensation         | MoneyDeposited after failure     | Transfer to non-existent account       |
| Optimistic locking   | `UNIQUE(aggregate_id, version)`  | Concurrent deposits → 409             |
