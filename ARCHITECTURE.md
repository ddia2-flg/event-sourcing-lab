# Architecture Guide — How Everything Works

A technical deep-dive into every class, how they connect, and why each design decision was made.

---

## Table of Contents

1. [High-Level Architecture](#1-high-level-architecture)
2. [Layer-by-Layer Breakdown](#2-layer-by-layer-breakdown)
3. [Data Flow: Opening an Account](#3-data-flow-opening-an-account)
4. [Data Flow: Deposit / Withdrawal](#4-data-flow-deposit--withdrawal)
5. [Data Flow: Temporal Query (Time Travel)](#5-data-flow-temporal-query-time-travel)
6. [Data Flow: CQRS Read vs Write](#6-data-flow-cqrs-read-vs-write)
7. [Data Flow: Kafka Transfer Saga](#7-data-flow-kafka-transfer-saga)
8. [Data Flow: Saga Failure & Compensation](#8-data-flow-saga-failure--compensation)
9. [Optimistic Locking](#9-optimistic-locking)
10. [Event Serialization](#10-event-serialization)
11. [Dependency Map](#11-dependency-map)
12. [Class Reference](#12-class-reference)

---

## 1. High-Level Architecture

```
┌──────────────────────────────────────────────────────────────────────┐
│                           Spring Boot App                            │
│                                                                      │
│  ┌──────────────┐    ┌──────────────────────┐    ┌────────────────┐  │
│  │   REST API   │───▶│  Application Services │───▶│   Domain Layer │  │
│  │  Controllers │    │  Command / Query      │    │   Aggregate    │  │
│  └──────┬───────┘    └──────────┬───────────┘    │   Events       │  │
│         │                       │                 └────────────────┘  │
│         │                       │                                     │
│         │           ┌───────────┴───────────┐                        │
│         │           │                       │                        │
│  ┌──────┴───────┐   │   ┌──────────────┐    │   ┌────────────────┐  │
│  │  MongoDB     │   │   │  EventStore  │    │   │  Kafka         │  │
│  │  Projection  │◀──┘   │  (Postgres)  │    └──▶│  Saga          │  │
│  │  Updater     │       └──────────────┘        │  Components    │  │
│  └──────────────┘                               └────────────────┘  │
└──────────────────────────────────────────────────────────────────────┘
         │                       │                        │
         ▼                       ▼                        ▼
    ┌─────────┐          ┌────────────┐            ┌───────────┐
    │ MongoDB │          │ PostgreSQL │            │   Kafka   │
    │  (read) │          │  (events)  │            │  (saga)   │
    └─────────┘          └────────────┘            └───────────┘
```

**Three databases, three jobs:**

| Database   | Role             | What it stores                                  |
|------------|------------------|-------------------------------------------------|
| PostgreSQL | Source of truth  | Append-only event log (`account_events` table)  |
| Kafka      | Message bus      | Transfer saga messages between aggregates        |
| MongoDB    | CQRS read model  | Pre-computed account summaries (disposable cache)|

---

## 2. Layer-by-Layer Breakdown

### 2.1 Domain Layer — Pure Business Logic, Zero I/O

```
domain/
├── event/
│   ├── DomainEvent.java          ← sealed interface (the contract)
│   ├── AccountOpened.java        ← Java record
│   ├── MoneyDeposited.java       ← Java record
│   ├── MoneyWithdrawn.java       ← Java record
│   ├── TransferRequested.java    ← Java record
│   ├── TransferCompleted.java    ← Java record
│   └── TransferFailed.java       ← Java record
├── aggregate/
│   └── AccountAggregate.java     ← state machine
└── exception/
    ├── AccountNotFoundException.java
    ├── InsufficientFundsException.java
    └── OptimisticLockingException.java
```

**Motivation:** The domain layer has ZERO dependencies on Spring, databases, or Kafka.
It's a pure Java model — you can test it with plain JUnit, no Spring context needed.
This is the heart of the event sourcing pattern: the aggregate is a state machine that
only understands events.

#### DomainEvent (sealed interface)

```java
public sealed interface DomainEvent
    permits AccountOpened, MoneyDeposited, MoneyWithdrawn,
            TransferRequested, TransferCompleted, TransferFailed {
    String aggregateId();
    Instant occurredAt();
}
```

**Why sealed?** Java 21 sealed interfaces let the compiler enforce that the `switch` inside
the aggregate covers ALL event types. If you add a new event and forget to handle it,
the code won't compile. This is a safety net.

**Why records?** Records give you immutability, `equals()`/`hashCode()`, and
Jackson serialization for free. Events should be immutable — once something happened, it happened.

#### AccountAggregate — The State Machine

The aggregate has **two roles**:

**Role 1 — Reconstitute from history** (used when loading):
```
List<DomainEvent> events = eventStore.load(accountId);
AccountAggregate account = AccountAggregate.reconstitute(events);
// account.getBalance() now reflects all past events
```

`reconstitute()` replays each event through `applyEvent()` — a pure switch:

```java
private void applyEvent(DomainEvent event) {
    switch (event) {
        case AccountOpened e    → { id = e.aggregateId(); balance = e.initialBalance(); open = true; }
        case MoneyDeposited e   → balance = balance.add(e.amount());
        case MoneyWithdrawn e   → balance = balance.subtract(e.amount());
        case TransferRequested e → balance = balance.subtract(e.amount());
        case TransferCompleted _ → {}   // no state change
        case TransferFailed _    → {}   // compensation handled via separate MoneyDeposited
    }
}
```

**Role 2 — Handle commands** (used when mutating):
```java
public void deposit(BigDecimal amount, String description) {
    // 1. Validate
    if (amount.compareTo(BigDecimal.ZERO) <= 0)
        throw new IllegalArgumentException("Deposit must be positive");

    // 2. Create event
    var event = new MoneyDeposited(id, amount, description, Instant.now());

    // 3. Apply to self + record as pending
    applyAndRecord(event);
}
```

The command method never touches the database. It validates, creates an event,
applies it to update in-memory state, and adds it to `pendingEvents`.
The caller (`AccountCommandService`) is responsible for persisting those pending events.

**Why separate `applyEvent()` from command methods?**
- During **reconstitution**, we skip validation (the event already happened, it's valid by definition)
- During **command handling**, we validate first, then create the event
- Both paths call the same `applyEvent()` so state transitions are identical

---

### 2.2 Event Store Layer — The Append-Only Log

```
eventstore/
├── EventStore.java               ← interface (port)
├── PostgresEventStore.java       ← JDBC implementation (adapter)
├── EventRecord.java              ← database row model
├── EventSerializer.java          ← serialization interface
└── JacksonEventSerializer.java   ← JSON serializer
```

**Motivation:** This layer is the bridge between domain events (Java objects) and
the database (JSON rows). It's deliberately kept behind an interface so the domain
layer doesn't know about PostgreSQL.

#### EventStore interface

```java
void append(String aggregateId, long expectedVersion, List<DomainEvent> events);
List<DomainEvent> load(String aggregateId);
List<DomainEvent> loadUpTo(String aggregateId, Instant asOf);
List<DomainEvent> loadFrom(String aggregateId, long fromVersion);
```

Four operations — that's it. The event store only does:
- **Append** events (never update, never delete)
- **Load** events (full history, up to a timestamp, or from a version)

#### PostgresEventStore

The `append()` method inserts events one by one, incrementing the version:

```java
public void append(String aggregateId, long expectedVersion, List<DomainEvent> events) {
    long nextVersion = expectedVersion + 1;
    for (DomainEvent event : events) {
        try {
            jdbc.update("INSERT INTO account_events (aggregate_id, version, event_type, payload) ...",
                    aggregateId, nextVersion++, eventTypeFor(event), serializer.serialize(event));
        } catch (DuplicateKeyException e) {
            throw new OptimisticLockingException(aggregateId);
        }
    }
}
```

**Why `DuplicateKeyException`?** The `UNIQUE (aggregate_id, version)` constraint in PostgreSQL
is the optimistic lock. If two threads try to insert version 4 simultaneously, one succeeds and
the other gets a duplicate key error → `OptimisticLockingException` → HTTP 409 to the client.

#### JacksonEventSerializer — Type Registry

```java
private static final Map<String, Class<? extends DomainEvent>> REGISTRY = Map.of(
    "AccountOpened",     AccountOpened.class,
    "MoneyDeposited",    MoneyDeposited.class,
    ...
);
```

Events are stored as JSON with an `event_type` discriminator column.
When loading, the serializer looks up the class by name and deserializes.

**Why a registry, not `@JsonTypeInfo`?** Explicitness. You see exactly which types exist.
If you rename a class, old events in the database still deserialize because the registry
maps the *stored string* to the *current class*.

---

### 2.3 Application Services — The Orchestrators

```
application/
├── AccountCommandService.java    ← writes (commands)
├── AccountQueryService.java      ← reads (queries)
└── dto/
    ├── command/                   ← request DTOs
    └── query/                     ← response DTOs
```

**Motivation:** The aggregate doesn't know about persistence.
The event store doesn't know about aggregates.
Application services wire them together: load → mutate → save.

#### AccountCommandService — The Write Path

Every command follows the same pattern:

```
1. Load events from store       → eventStore.load(accountId)
2. Reconstitute aggregate       → AccountAggregate.reconstitute(events)
3. Execute command on aggregate  → account.deposit(amount, desc)
4. Save pending events           → eventStore.append(id, version, pendingEvents)
5. Publish events                → eventPublisher.publishEvent(event)
6. Clear pending                 → account.clearPendingEvents()
```

Steps 4–6 happen in `saveAndPublish()`:

```java
private void saveAndPublish(AccountAggregate account, long expectedVersion) {
    List<DomainEvent> pending = account.getPendingEvents();
    eventStore.append(account.getId(), expectedVersion, pending);  // 4. persist
    pending.forEach(eventPublisher::publishEvent);                 // 5. notify listeners
    account.clearPendingEvents();                                  // 6. reset
}
```

**Why `ApplicationEventPublisher`?** This is Spring's built-in event bus.
After persisting events to PostgreSQL, we publish them in-process so the
MongoDB projection updater can react. No extra infrastructure needed for
the CQRS sync.

#### AccountQueryService — The Read Path (Event Replay)

```java
public AccountSummaryDto getBalance(String accountId) {
    var events = eventStore.load(accountId);         // load ALL events
    var account = AccountAggregate.reconstitute(events);  // replay
    return new AccountSummaryDto(accountId, account.getOwner(),
            account.getBalance(), account.getVersion(), Instant.now());
}
```

For temporal queries:

```java
public AccountSummaryDto getBalanceAt(String accountId, Instant asOf) {
    var events = eventStore.loadUpTo(accountId, asOf);  // only events before asOf
    var account = AccountAggregate.reconstitute(events);
    return new AccountSummaryDto(..., account.getBalance(), ..., asOf);
}
```

The query service never writes. It loads events and replays them.
This is the pure event sourcing read path.

---

### 2.4 REST API Layer

```
api/
├── AccountController.java        ← account endpoints
├── TransferController.java       ← transfer endpoint
└── GlobalExceptionHandler.java   ← error mapping
```

**Motivation:** Thin layer. Controllers do no logic — they delegate to services
and map exceptions to HTTP status codes.

#### AccountController — Two Read Sources

```java
// Read from PostgreSQL (event replay):
@GetMapping("/{accountId}/balance")
public AccountSummaryDto getBalance(@PathVariable String accountId, ...) {
    return queryService.getBalance(accountId);   // ← replays events
}

// Read from MongoDB (CQRS projection):
@GetMapping("/{accountId}/summary")
public ResponseEntity<AccountProjection> getSummary(@PathVariable String accountId) {
    return projectionRepository.findById(accountId);  // ← instant, no replay
}
```

Both return the same balance, but through different paths.
This is CQRS in action — two read models optimised for different things.

#### GlobalExceptionHandler — RFC 7807 Problem Details

```java
@ExceptionHandler(InsufficientFundsException.class)
public ResponseEntity<ProblemDetail> handleInsufficientFunds(InsufficientFundsException ex) {
    var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, ex.getMessage());
    problem.setTitle("Insufficient Funds");
    return ResponseEntity.status(422).body(problem);
}
```

Domain exceptions map to HTTP status codes:

| Exception                   | HTTP Status |
|-----------------------------|-------------|
| `AccountNotFoundException`  | 404         |
| `InsufficientFundsException`| 422         |
| `OptimisticLockingException`| 409         |
| `IllegalArgumentException`  | 400         |
| Validation errors           | 400         |

---

### 2.5 Projection Layer — MongoDB CQRS Read Model

```
projection/
├── AccountProjection.java             ← MongoDB document
├── AccountProjectionRepository.java   ← Spring Data Mongo interface
└── AccountProjectionUpdater.java      ← event listener (keeps Mongo in sync)
```

**Motivation:** Pure event sourcing has limitations:
- **Listing all accounts** requires knowing all aggregate IDs upfront
- **Getting a balance** requires replaying potentially thousands of events

The MongoDB projection solves both: it's a pre-computed, denormalized view
that's updated on every event. It's fast, but it's also **disposable** —
if MongoDB dies, rebuild it by replaying all events from PostgreSQL.

#### AccountProjectionUpdater — The Sync Mechanism

Uses `@EventListener` to react to Spring application events published by
`AccountCommandService.saveAndPublish()`:

```java
@EventListener
public void on(AccountOpened event) {
    var projection = new AccountProjection(event.aggregateId(), event.owner(), event.initialBalance());
    repository.save(projection);
}

@EventListener
public void on(MoneyDeposited event) {
    repository.findById(event.aggregateId()).ifPresent(p -> {
        p.setBalance(p.getBalance().add(event.amount()));
        repository.save(p);
    });
}
```

**Why `@EventListener` and not Kafka?** For the CQRS projection, we want
**synchronous, in-process** updates. When the REST endpoint returns `200 OK`,
the MongoDB projection is already updated. No eventual consistency delay
for basic operations. Kafka is only used for the distributed transfer saga.

---

### 2.6 Kafka Saga Layer — Distributed Transfer

```
infrastructure/kafka/
├── OutboxPublisher.java            ← polls outbox, sends to Kafka
├── TransferSagaCoordinator.java    ← credits target account
├── TransferResultConsumer.java     ← finalises source account
└── dto/
    ├── TransferRequestedMessage.java
    └── TransferResultMessage.java
```

**Motivation:** Transferring money between two accounts touches two aggregates.
In event sourcing, each aggregate is a consistency boundary — you can't modify
two aggregates in one transaction. The Saga pattern coordinates this:

```
Account A (source)                 Kafka                    Account B (target)
     │                               │                           │
     │  1. TransferRequested         │                           │
     │     (debit A, -300)           │                           │
     │──────────────────────────────▶│                           │
     │                               │  2. transfer.requested   │
     │                               │─────────────────────────▶│
     │                               │                           │
     │                               │                    3. MoneyDeposited
     │                               │                       (credit B, +300)
     │                               │                           │
     │                               │  4. transfer.result      │
     │  5. TransferCompleted         │◀─────────────────────────│
     │◀──────────────────────────────│                           │
     │                               │                           │
```

#### OutboxPublisher — Transactional Outbox Pattern

**Problem:** When `AccountCommandService.requestTransfer()` debits Alice and wants to
notify Kafka, what if Kafka is down? The debit is committed but the message is lost.

**Solution:** Write the Kafka message to a PostgreSQL `transfer_outbox` table in the
**same database transaction** as the event. A separate `@Scheduled` job polls for
unpublished rows and sends them to Kafka:

```java
@Scheduled(fixedDelay = 200)   // every 200ms
@Transactional
public void publishPending() {
    // 1. SELECT unpublished rows (LIMIT 100)
    // 2. For each: send to Kafka
    // 3. UPDATE published = true
}
```

**Why not just send to Kafka directly?** If the app crashes between DB commit and
Kafka send, the message is lost. The outbox guarantees: if the event was written,
the Kafka message **will eventually** be sent. Worst case: duplicate delivery
(at-least-once), which the consumer handles via idempotency.

#### TransferSagaCoordinator — The "Middleman"

Listens to `transfer.requested` topic. Loads the target account, credits it,
and sends the result:

```java
@KafkaListener(topics = "transfer.requested", groupId = "transfer-saga-coordinator")
@Transactional
public void onTransferRequested(TransferRequestedMessage msg) {
    try {
        // Load target account
        var events = eventStore.load(msg.targetAccountId());
        var target = AccountAggregate.reconstitute(events);

        // Credit target
        target.deposit(msg.amount(), "Transfer from " + msg.sourceAccountId());
        eventStore.append(target.getId(), versionBefore, target.getPendingEvents());

        // Report success
        kafkaTemplate.send("transfer.result", msg.transferId(), successMessage);

    } catch (AccountNotFoundException | ...) {
        // Report failure
        kafkaTemplate.send("transfer.result", msg.transferId(), failureMessage);
    }
}
```

#### TransferResultConsumer — Closing the Loop

Listens to `transfer.result` topic. Updates the source account:

```java
@KafkaListener(topics = "transfer.result", groupId = "transfer-result-consumer")
public void onTransferResult(TransferResultMessage msg) {
    if (msg.success()) {
        commandService.completeTransfer(msg.sourceAccountId(), msg.transferId());
        // → appends TransferCompleted event to source
    } else {
        commandService.failTransfer(msg.sourceAccountId(), msg.transferId(),
                msg.failureReason(), msg.amount());
        // → appends TransferFailed + compensating MoneyDeposited to source
    }
}
```

---

## 3. Data Flow: Opening an Account

```
HTTP POST /api/v1/accounts { "owner": "Alice", "initialBalance": 1000 }
  │
  ▼
AccountController.openAccount()
  │
  ▼
AccountCommandService.openAccount()
  │
  ├── 1. Generate UUID
  ├── 2. AccountAggregate.create(id)
  ├── 3. account.open("Alice", 1000)
  │       └── validates → creates AccountOpened event → applyEvent() → adds to pendingEvents
  │
  ├── 4. saveAndPublish(account, expectedVersion=0)
  │       ├── eventStore.append("abc-123", 0, [AccountOpened])
  │       │     └── INSERT INTO account_events (aggregate_id, version=1, event_type, payload)
  │       │
  │       ├── eventPublisher.publishEvent(AccountOpened)
  │       │     └── AccountProjectionUpdater.on(AccountOpened)
  │       │           └── MongoDB: INSERT account_projections { accountId, owner, balance: 1000 }
  │       │
  │       └── account.clearPendingEvents()
  │
  └── 5. return accountId
  │
  ▼
HTTP 201 Created { "accountId": "abc-123" }
```

**What happened in the database:**

PostgreSQL `account_events`:
```
| id | aggregate_id | version | event_type    | payload                                    |
|----|--------------|---------|---------------|--------------------------------------------|
| 1  | abc-123      | 1       | AccountOpened | {"owner":"Alice","initialBalance":1000.00} |
```

MongoDB `account_projections`:
```json
{ "_id": "abc-123", "owner": "Alice", "balance": 1000.00, "version": 1 }
```

---

## 4. Data Flow: Deposit / Withdrawal

```
HTTP POST /api/v1/accounts/abc-123/deposits { "amount": 500, "description": "Salary" }
  │
  ▼
AccountController.deposit()
  │
  ▼
AccountCommandService.deposit()
  │
  ├── 1. eventStore.load("abc-123")
  │       └── SELECT * FROM account_events WHERE aggregate_id = 'abc-123' ORDER BY version
  │           → returns [AccountOpened(1000)]
  │
  ├── 2. AccountAggregate.reconstitute([AccountOpened])
  │       └── replays: balance = 1000, version = 1
  │
  ├── 3. account.deposit(500, "Salary")
  │       └── validates amount > 0 → creates MoneyDeposited → applyEvent() → balance = 1500
  │
  └── 4. saveAndPublish(account, expectedVersion=1)
          ├── eventStore.append("abc-123", 1, [MoneyDeposited])
          │     └── INSERT (aggregate_id='abc-123', version=2, event_type='MoneyDeposited', ...)
          │
          └── eventPublisher → AccountProjectionUpdater.on(MoneyDeposited)
                └── MongoDB: balance += 500 → 1500
```

**Key insight:** The aggregate was loaded by replaying 1 event. After the deposit,
there are 2 events. The balance 1500 was never "stored" — it was computed.

---

## 5. Data Flow: Temporal Query (Time Travel)

```
HTTP GET /api/v1/accounts/abc-123/balance?asOf=2026-03-28T12:05:00Z
  │
  ▼
AccountController.getBalance(accountId, asOf=12:05)
  │
  ▼
AccountQueryService.getBalanceAt("abc-123", 12:05)
  │
  ├── 1. eventStore.loadUpTo("abc-123", 12:05)
  │       └── SELECT * FROM account_events
  │           WHERE aggregate_id = 'abc-123' AND occurred_at <= '12:05'
  │           ORDER BY version
  │       → returns [AccountOpened(1000), MoneyDeposited(500)]
  │       (the withdrawal at 12:10 is excluded!)
  │
  ├── 2. AccountAggregate.reconstitute([AccountOpened, MoneyDeposited])
  │       └── replays: 1000 + 500 = 1500
  │
  └── 3. return AccountSummaryDto(balance=1500, asOf=12:05)
```

**Why this works for free:** Every event has `occurred_at`. The SQL `WHERE occurred_at <= ?`
filters out future events. No extra tables, no snapshots, no versioning logic.

---

## 6. Data Flow: CQRS Read vs Write

Two endpoints return the same balance through completely different paths:

```
┌─────────────────────────────────┐    ┌─────────────────────────────────┐
│ GET /accounts/{id}/balance      │    │ GET /accounts/{id}/summary      │
│                                 │    │                                 │
│ 1. Load events from PostgreSQL  │    │ 1. Read document from MongoDB   │
│ 2. Replay through aggregate     │    │    (single findById)            │
│ 3. Compute balance              │    │                                 │
│                                 │    │                                 │
│ Source: PostgreSQL (truth)       │    │ Source: MongoDB (cache)         │
│ Speed: O(n) where n = events    │    │ Speed: O(1) constant time      │
│ Always correct                  │    │ Always correct (sync'd)         │
│ Cannot list all accounts        │    │ CAN list all accounts           │
└─────────────────────────────────┘    └─────────────────────────────────┘
```

**Why both?**
- `/balance` proves event sourcing works — state is computed
- `/summary` proves CQRS works — a separate read model is faster and more flexible
- `/accounts` (list all) is **impossible** with pure event sourcing without a projection

**How they stay in sync:**

```
AccountCommandService.saveAndPublish()
  │
  ├── eventStore.append(...)          → PostgreSQL (write model updated)
  │
  └── eventPublisher.publishEvent()   → Spring ApplicationEvent
        │
        └── AccountProjectionUpdater  → MongoDB (read model updated)
```

The sync is synchronous — both databases are updated before the HTTP response is sent.

---

## 7. Data Flow: Kafka Transfer Saga

```
HTTP POST /api/v1/transfers { sourceAccountId: "alice", targetAccountId: "bob", amount: 300 }
  │
  ▼
TransferController.initiateTransfer()
  │
  ▼
AccountCommandService.requestTransfer()  ─ @Transactional (single DB transaction) ─┐
  │                                                                                  │
  ├── 1. Load Alice's events, reconstitute aggregate                                 │
  ├── 2. account.requestTransfer("transfer-1", "bob", 300)                           │
  │       └── validates balance >= 300 → creates TransferRequested → balance -= 300  │
  ├── 3. saveAndPublish() → INSERT account_events (TransferRequested)                │
  ├── 4. writeOutbox() → INSERT transfer_outbox (published=false)                    │
  └── return "transfer-1"                                                            │
                                                                                     │
  ─── transaction commit ────────────────────────────────────────────────────────────┘
  │
  ▼
HTTP 202 Accepted { "transferId": "transfer-1", "status": "PENDING" }

          ┄┄┄ asynchronous from here ┄┄┄

OutboxPublisher (@Scheduled every 200ms)
  │
  ├── SELECT FROM transfer_outbox WHERE published = false
  ├── kafkaTemplate.send("transfer.requested", "transfer-1", message)
  └── UPDATE transfer_outbox SET published = true
  │
  ▼
  ════════════════════ Kafka topic: transfer.requested ═══════════════════
  │
  ▼
TransferSagaCoordinator (@KafkaListener)
  │
  ├── Load Bob's events, reconstitute aggregate
  ├── bob.deposit(300, "Transfer from alice")
  ├── eventStore.append(bob, version, [MoneyDeposited])
  ├── eventPublisher.publishEvent(MoneyDeposited) → MongoDB updated for Bob
  │
  └── kafkaTemplate.send("transfer.result", "transfer-1", success=true)
  │
  ▼
  ════════════════════ Kafka topic: transfer.result ══════════════════════
  │
  ▼
TransferResultConsumer (@KafkaListener)
  │
  └── commandService.completeTransfer("alice", "transfer-1")
        ├── Load Alice's events, reconstitute
        ├── alice.completeTransfer("transfer-1") → TransferCompleted event
        └── saveAndPublish() → PostgreSQL + MongoDB updated
```

**Alice's events after transfer:**
```
v1: AccountOpened(1000)
v2: MoneyDeposited(500)           ← salary
v3: MoneyWithdrawn(200)           ← rent
v4: TransferRequested(300)        ← balance debited immediately
v5: TransferCompleted(transfer-1) ← saga confirmed
```

**Bob's events:**
```
v1: AccountOpened(200)
v2: MoneyDeposited(300)           ← credited by saga coordinator
```

---

## 8. Data Flow: Saga Failure & Compensation

```
POST /api/v1/transfers { source: "alice", target: "non-existent-id", amount: 100 }
```

```
AccountCommandService.requestTransfer()
  ├── Alice debited: TransferRequested(-100)
  └── Outbox row written
  │
  ▼
OutboxPublisher → Kafka: transfer.requested
  │
  ▼
TransferSagaCoordinator
  ├── eventStore.load("non-existent-id") → empty list
  ├── throws AccountNotFoundException!
  │
  └── kafkaTemplate.send("transfer.result", success=false, reason="Account not found")
  │
  ▼
TransferResultConsumer
  │
  └── commandService.failTransfer("alice", "transfer-1", "Account not found", 100)
        ├── Load Alice, reconstitute
        ├── alice.failTransfer("transfer-1", reason)  → TransferFailed event
        ├── alice.deposit(100, "Transfer rollback")   → MoneyDeposited event (COMPENSATION)
        └── saveAndPublish() → both events saved atomically
```

**Alice's events after failed transfer:**
```
v6: TransferRequested(100)                     ← balance debited
v7: TransferFailed(reason: "Account not found") ← saga failed
v8: MoneyDeposited(100, "Transfer rollback")    ← COMPENSATING EVENT (balance restored)
```

**Key insight:** We never delete or modify v6 (TransferRequested). The debit happened,
it was recorded, and the history shows it was later compensated. Full audit trail.

---

## 9. Optimistic Locking

The event store table has a composite unique constraint:

```sql
CONSTRAINT uq_aggregate_version UNIQUE (aggregate_id, version)
```

**How it prevents corruption:**

```
Thread A: loads Alice (version=3)     Thread B: loads Alice (version=3)
Thread A: deposit(100)                Thread B: withdraw(50)
Thread A: append(version=3, ...)      Thread B: append(version=3, ...)
         ↓                                     ↓
  INSERT version=4 → SUCCESS           INSERT version=4 → DUPLICATE KEY!
                                        → OptimisticLockingException
                                        → HTTP 409 Conflict
```

Both threads saw version 3. Both tried to write version 4.
PostgreSQL's unique constraint ensures only one wins. The loser gets
a clear error and can retry.

**Why not pessimistic locking (SELECT FOR UPDATE)?**
- Pessimistic locking blocks threads, reducing throughput
- Event sourcing with optimistic locking is naturally conflict-free for most operations
- Retries are simple: reload, recompute, resave

---

## 10. Event Serialization

```
Domain Event (Java object)
        │
        │  JacksonEventSerializer.serialize()
        ▼
JSON string → stored in account_events.payload (JSONB column)

account_events.payload (JSONB)
        │
        │  JacksonEventSerializer.deserialize(eventType, payload)
        │     ← looks up class in REGISTRY map by event_type column
        ▼
Domain Event (Java object)
```

The registry maps stored event type names to Java classes:

```java
Map.of(
    "AccountOpened",     AccountOpened.class,
    "MoneyDeposited",    MoneyDeposited.class,
    "MoneyWithdrawn",    MoneyWithdrawn.class,
    "TransferRequested", TransferRequested.class,
    "TransferCompleted", TransferCompleted.class,
    "TransferFailed",    TransferFailed.class
);
```

**Why JSONB?** PostgreSQL JSONB is binary JSON — it supports indexing and querying
inside the payload. For example, you could later query
`WHERE payload->>'transferId' = 'abc'` without changing the schema.

---

## 11. Dependency Map

Who injects what — every arrow is a constructor injection:

```
AccountController
  ├── AccountCommandService
  ├── AccountQueryService
  └── AccountProjectionRepository

TransferController
  └── AccountCommandService

AccountCommandService
  ├── EventStore (→ PostgresEventStore)
  ├── JdbcTemplate
  ├── ApplicationEventPublisher
  └── ObjectMapper

AccountQueryService
  ├── EventStore (→ PostgresEventStore)
  └── ObjectMapper

PostgresEventStore
  ├── JdbcTemplate
  └── JacksonEventSerializer

JacksonEventSerializer
  └── ObjectMapper

OutboxPublisher
  ├── JdbcTemplate
  ├── KafkaTemplate<String, Object>
  └── ObjectMapper

TransferSagaCoordinator
  ├── EventStore (→ PostgresEventStore)
  ├── KafkaTemplate<String, Object>
  └── ApplicationEventPublisher

TransferResultConsumer
  └── AccountCommandService

AccountProjectionUpdater
  └── AccountProjectionRepository
```

---

## 12. Class Reference

### All 33 Classes at a Glance

| Package | Class | Type | Purpose |
|---------|-------|------|---------|
| (root) | `EventSourcingApplication` | Main | Entry point, `@EnableScheduling` |
| `domain.event` | `DomainEvent` | Sealed interface | Contract for all events |
| `domain.event` | `AccountOpened` | Record | Account creation event |
| `domain.event` | `MoneyDeposited` | Record | Deposit event |
| `domain.event` | `MoneyWithdrawn` | Record | Withdrawal event |
| `domain.event` | `TransferRequested` | Record | Transfer initiation event |
| `domain.event` | `TransferCompleted` | Record | Transfer success event |
| `domain.event` | `TransferFailed` | Record | Transfer failure event |
| `domain.aggregate` | `AccountAggregate` | Class | State machine, replays events |
| `domain.exception` | `AccountNotFoundException` | Exception | 404 |
| `domain.exception` | `InsufficientFundsException` | Exception | 422 |
| `domain.exception` | `OptimisticLockingException` | Exception | 409 |
| `eventstore` | `EventStore` | Interface | Port for event persistence |
| `eventstore` | `PostgresEventStore` | Repository | JDBC adapter |
| `eventstore` | `EventRecord` | Record | DB row model |
| `eventstore` | `EventSerializer` | Interface | Serialization port |
| `eventstore` | `JacksonEventSerializer` | Component | JSON serializer with registry |
| `application` | `AccountCommandService` | Service | Write operations orchestrator |
| `application` | `AccountQueryService` | Service | Read operations (replay) |
| `application.dto.command` | `OpenAccountCommand` | Record | Request DTO |
| `application.dto.command` | `DepositMoneyCommand` | Record | Request DTO |
| `application.dto.command` | `WithdrawMoneyCommand` | Record | Request DTO |
| `application.dto.command` | `RequestTransferCommand` | Record | Request DTO |
| `application.dto.query` | `AccountSummaryDto` | Record | Response DTO |
| `application.dto.query` | `EventHistoryDto` | Record | Response DTO |
| `api` | `AccountController` | Controller | REST endpoints for accounts |
| `api` | `TransferController` | Controller | REST endpoint for transfers |
| `api` | `GlobalExceptionHandler` | Advice | Maps exceptions → HTTP status |
| `infrastructure.config` | `JacksonConfig` | Config | ObjectMapper with JavaTime |
| `infrastructure.config` | `KafkaConfig` | Config | Topic definitions |
| `infrastructure.config` | `OpenApiConfig` | Config | Swagger metadata |
| `infrastructure.kafka` | `OutboxPublisher` | Scheduled | Polls outbox → Kafka |
| `infrastructure.kafka` | `TransferSagaCoordinator` | Listener | Credits target account |
| `infrastructure.kafka` | `TransferResultConsumer` | Listener | Finalises source account |
| `infrastructure.kafka.dto` | `TransferRequestedMessage` | Record | Kafka DTO |
| `infrastructure.kafka.dto` | `TransferResultMessage` | Record | Kafka DTO |
| `projection` | `AccountProjection` | Document | MongoDB model |
| `projection` | `AccountProjectionRepository` | Interface | Spring Data Mongo |
| `projection` | `AccountProjectionUpdater` | Listener | Syncs events → MongoDB |
