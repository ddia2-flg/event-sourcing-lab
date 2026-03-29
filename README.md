# Event Sourcing & CQRS — Bank Account Demo

A Spring Boot application demonstrating Event Sourcing, CQRS, Kafka Saga, and the Transactional Outbox pattern using a bank account domain.

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3 |
| Event Store | PostgreSQL (append-only `account_events` table) |
| Read Model | MongoDB (CQRS projections) |
| Messaging | Apache Kafka (transfer saga) |
| Migrations | Flyway |
| API Docs | Swagger UI |

## Key Concepts Implemented

- **Event Sourcing** — state is derived by replaying events, never stored directly
- **CQRS** — separate write model (PostgreSQL) and read model (MongoDB)
- **Time Travel** — query account balance at any past timestamp
- **Kafka Saga** — distributed transfer across two aggregates with compensation on failure
- **Transactional Outbox** — guaranteed Kafka delivery without two-phase commit
- **Optimistic Locking** — `UNIQUE(aggregate_id, version)` prevents concurrent write conflicts

## Running Locally

```bash
docker compose up --build
```

Wait ~30 seconds, then open Swagger UI: **http://localhost:8080/swagger-ui.html**

### Database connections (optional)

**PostgreSQL** — `localhost:5433`, database `eventstore`, user `es_user`, password `es_pass`

**MongoDB** — `mongodb://localhost:27017/accountview`

## API Overview

| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/v1/accounts` | Open account |
| POST | `/api/v1/accounts/{id}/deposits` | Deposit money |
| POST | `/api/v1/accounts/{id}/withdrawals` | Withdraw money |
| GET | `/api/v1/accounts/{id}/balance` | Current balance (event replay) |
| GET | `/api/v1/accounts/{id}/balance?asOf=` | Balance at past timestamp |
| GET | `/api/v1/accounts/{id}/events` | Full immutable event history |
| GET | `/api/v1/accounts` | List all accounts (MongoDB read model) |
| GET | `/api/v1/accounts/{id}/summary` | Fast summary (MongoDB, no replay) |
| POST | `/api/v1/transfers` | Async transfer between accounts |

## Documentation

- [ARCHITECTURE.md](ARCHITECTURE.md) — deep-dive into every class, design decisions, and data flows
- [DEMO_GUIDE.md](DEMO_GUIDE.md) — step-by-step walkthrough of all concepts with SQL queries and curl examples

## Cleanup

```bash
docker compose down        # stop containers
docker compose down -v     # stop and remove data volumes
```