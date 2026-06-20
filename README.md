# Wallet Transfer Service

A reliable, transactional wallet-to-wallet transfer service that guarantees **exactly-once request handling**, **double-entry ledger consistency**, **correct balances under concurrency**, and **safe state transitions** — the core design challenges of distributed financial systems.

> Design rationale lives in two companion docs: [`TechnicalDesignDocument.md`](./TechnicalDesignDocument.md) (the documentation-first design note) and [`implementation_details.md`](./implementation_details.md) (architecture, design decisions, trade-offs, and performance considerations).

---

## Tech Stack

| Concern | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.3.4 (Web, Validation) |
| Persistence | Spring Data JPA / Hibernate ORM 6.5 |
| Database | PostgreSQL 15 |
| Migrations | Flyway |
| Boilerplate | Lombok |
| Testing | JUnit 5, Testcontainers (real PostgreSQL), AssertJ |
| Build | Gradle (wrapper included) |
| Formatting | Spotless + Google Java Format (enforced by `check`) |

---

## Key Features

- **`POST /transfers`** — move funds between two wallets with exactly-once semantics.
- **Idempotency** — a dedicated, durable registry returns the original result for any retry and prevents duplicate side effects.
- **Double-entry ledger** — every transfer writes exactly one DEBIT and one CREDIT; the ledger always balances.
- **Concurrency safety** — pessimistic row locks with deterministic ordering prevent double-spend and deadlocks.
- **Guarded state machine** — transfers move `PENDING → PROCESSED | FAILED`, never backwards.
- **Defense in depth** — invariants enforced in the domain *and* at the database (`CHECK` / `UNIQUE` / `FK`).
- **Uniform error contract** — a single `ErrorResponse` shape across all non-2xx responses.

---

## Architecture Overview

A clean, layered architecture with strict separation of concerns:

```
HTTP ─▶ Controller ─▶ Service (orchestration + idempotency) ─▶ Processor (1 ACID tx) ─▶ Repositories ─▶ PostgreSQL
                                                                      │
                                                              Domain entities
                                                       (state transitions + invariants)
```

| Layer | Responsibility | Key types |
|---|---|---|
| **Controller** | Transport only: validate, map outcome → HTTP status, delegate | `TransferController` |
| **Service** | Orchestration + idempotency; **not** transactional | `TransferServiceImpl` |
| **Processor** | The atomic unit of work — one `@Transactional` boundary | `TransferProcessor` |
| **Repository** | Persistence only (incl. the pessimistic-lock query) | `WalletRepository`, … |
| **Domain** | Entities owning their state transitions and invariants | `Wallet`, `Transfer`, … |

The **service/processor split is deliberate**: the orchestrator stays outside any transaction so it can catch a concurrent duplicate's failure and replay the committed winner, while the processor owns a single real transaction boundary. See [Implementation Highlights](#implementation-highlights).

---

## Project Structure

```
src/
├── main/
│   ├── java/com/rajat/wallet/
│   │   ├── WalletTransferApplication.java        # Spring Boot entry point (@EnableJpaAuditing)
│   │   ├── controller/
│   │   │   └── TransferController.java            # POST /transfers — thin transport layer
│   │   ├── service/
│   │   │   ├── TransferService.java              # service interface
│   │   │   ├── TransferServiceImpl.java          # idempotency-aware orchestrator (non-transactional)
│   │   │   └── TransferProcessor.java            # the single @Transactional unit of work
│   │   ├── repository/
│   │   │   ├── WalletRepository.java             # findAllForUpdate (SELECT … FOR UPDATE)
│   │   │   ├── TransferRepository.java
│   │   │   ├── LedgerEntryRepository.java
│   │   │   └── IdempotencyRecordRepository.java  # findByIdempotencyKey
│   │   ├── domain/
│   │   │   ├── entities/
│   │   │   │   ├── Wallet.java                   # balance + debit/credit invariants
│   │   │   │   ├── Transfer.java                 # guarded state machine
│   │   │   │   ├── LedgerEntry.java              # immutable, append-only
│   │   │   │   ├── IdempotencyRecord.java        # exactly-once registry record
│   │   │   │   └── common/
│   │   │   │       ├── BaseEntity.java           # UUID v7 primary key
│   │   │   │       └── AuditableEntity.java      # created_at / updated_at auditing
│   │   │   └── enums/
│   │   │       ├── TransferStatus.java           # PENDING | PROCESSED | FAILED
│   │   │       ├── EntryType.java                # DEBIT | CREDIT
│   │   │       └── IdempotencyStatus.java        # IN_PROGRESS | COMPLETED
│   │   ├── dto/
│   │   │   ├── CreateTransferRequest.java        # inbound contract + bean validation
│   │   │   ├── TransferResponse.java             # outbound contract (builder)
│   │   │   └── ErrorResponse.java                # uniform error body
│   │   └── exception/
│   │       ├── WalletNotFoundException.java      # → 404
│   │       ├── IdempotencyConflictException.java # → 409
│   │       └── handler/
│   │           └── GlobalExceptionHandler.java   # @RestControllerAdvice
│   └── resources/
│       ├── application.yml
│       └── db/migration/
│           ├── V1__init.sql                      # wallets, transfers, ledger_entries
│           ├── V2__create_idempotency_records.sql
│           └── V3__amount_precision_two_decimals.sql
└── test/java/com/rajat/wallet/
    ├── domain/entities/
    │   ├── WalletTest.java                        # balance invariants (unit)
    │   └── TransferTest.java                      # state machine (unit)
    ├── support/
    │   └── AbstractIntegrationTest.java           # Testcontainers base
    ├── TransferApiIT.java                          # execution, ledger, validation
    ├── IdempotencyIT.java                          # replay + conflict
    └── ConcurrencyIT.java                          # no double-spend, duplicate key

scripts/seed-wallets.sql                            # 10 demo wallets (manual, not a migration)
docker-compose.yml                                  # local PostgreSQL
```

---

## How to Run

### Prerequisites

- JDK 21 (the Gradle toolchain will resolve it)
- Docker (for PostgreSQL and for the Testcontainers-based tests)

### 1. Start PostgreSQL

```bash
docker compose up -d
```

This starts `postgres:15.4` as container `wallet-postgres` (db/user/password all `wallet`) on port `5432`.

### 2. Run the application

```bash
./gradlew bootRun
```

Flyway applies `V1`–`V3` on startup, then Hibernate validates the schema. The API is available at `http://localhost:8080`.

Connection settings are overridable via env vars (defaults shown):

```
DB_URL=jdbc:postgresql://localhost:5432/wallet
DB_USERNAME=wallet
DB_PASSWORD=wallet
SERVER_PORT=8080
```

### 3. (Optional) Seed demo wallets

```bash
docker exec -i wallet-postgres psql -U wallet -d wallet < scripts/seed-wallets.sql
```

Seeds 10 wallets with fixed, human-readable UUIDs (`00000000-0000-0000-0000-000000000001` … `…010`), including a zero-balance wallet and two `USD` wallets so insufficient-funds and currency-mismatch paths are easy to demo.

### 4. Run the tests

```bash
./gradlew test
```

Domain unit tests run instantly; integration tests spin up a real PostgreSQL via Testcontainers (Docker required).

---

## API Endpoints

### `POST /transfers`

Creates a transfer. Idempotent on `idempotencyKey`.

**Request**

```json
{
  "idempotencyKey": "abc123",
  "fromWalletId": "00000000-0000-0000-0000-000000000001",
  "toWalletId":   "00000000-0000-0000-0000-000000000002",
  "amount": 250.50
}
```

| Field | Type | Rules |
|---|---|---|
| `idempotencyKey` | string | required, non-blank; unique per logical request |
| `fromWalletId` | UUID | required, must exist |
| `toWalletId` | UUID | required, must exist, `!= fromWalletId` |
| `amount` | decimal | required, `> 0`, scale ≤ 2 |

**Success — `201 Created`**

```json
{
  "transferId": "018f...",
  "fromWalletId": "00000000-0000-0000-0000-000000000001",
  "toWalletId":   "00000000-0000-0000-0000-000000000002",
  "amount": 250.50,
  "status": "PROCESSED",
  "failureReason": null,
  "createdAt": "2026-06-20T10:00:00Z"
}
```

```bash
curl -s -X POST localhost:8080/transfers -H 'Content-Type: application/json' -d '{
  "idempotencyKey": "demo-1",
  "fromWalletId": "00000000-0000-0000-0000-000000000001",
  "toWalletId":   "00000000-0000-0000-0000-000000000002",
  "amount": 250.50
}'
```

**Business failure — `422 Unprocessable Entity`** (recorded, not an error)

A business failure is a first-class outcome: the transfer is persisted as `FAILED` with a reason, and the `422` response is cached so a retry replays it identically.

```json
{
  "transferId": "018f...",
  "fromWalletId": "00000000-0000-0000-0000-000000000006",
  "toWalletId":   "00000000-0000-0000-0000-000000000002",
  "amount": 100.00,
  "status": "FAILED",
  "failureReason": "Insufficient funds in wallet 00000000-0000-0000-0000-000000000006",
  "createdAt": "2026-06-20T10:00:00Z"
}
```

### Possible error scenarios

| Scenario | Code | Body |
|---|---|---|
| Transfer executed | `201 Created` | `TransferResponse` (`status: PROCESSED`) |
| Insufficient funds / currency mismatch | `422 Unprocessable Entity` | `TransferResponse` (`status: FAILED` + `failureReason`) |
| Validation error (missing field, `amount <= 0`, scale > 2, self-transfer, malformed JSON) | `400 Bad Request` | `ErrorResponse` (with `fieldErrors`) |
| Wallet does not exist | `404 Not Found` | `ErrorResponse` |
| Idempotency key reused with a **different** payload, or original still `IN_PROGRESS` | `409 Conflict` | `ErrorResponse` |
| Duplicate of a **completed** request (same key + same payload) | replay | original status + body, verbatim |
| Unexpected server error | `500 Internal Server Error` | `ErrorResponse` |

---

## Error Handling

All non-2xx responses share one shape, produced by `GlobalExceptionHandler` (`@RestControllerAdvice`):

```json
{
  "timestamp": "2026-06-20T10:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "fieldErrors": { "amount": "must be greater than 0" }
}
```

`fieldErrors` is omitted (`@JsonInclude(NON_NULL)`) for non-validation errors.

| Exception | HTTP |
|---|---|
| `MethodArgumentNotValidException` (bean validation) | `400` |
| `HttpMessageNotReadableException` (malformed body) | `400` |
| `IllegalArgumentException` | `400` |
| `WalletNotFoundException` | `404` |
| `IdempotencyConflictException` | `409` |
| `DataIntegrityViolationException` (race fallback) | `409` |
| any other `Exception` | `500` (logged) |

The controller maps a recorded business `FAILED` outcome to `422`, and `PROCESSED` to `201` — so a replayed result returns the same status code as the original.

---

## Implementation Highlights

### Idempotency

A **dedicated, operation-agnostic `idempotency_records` table** (rather than a unique constraint on `transfers`) stores each key with a `request_hash`, a `status` (`IN_PROGRESS → COMPLETED`), and the **cached response** (`response_status` + `response_body`).

- **First request** inserts an `IN_PROGRESS` record (`saveAndFlush`), executes the transfer, then flips it to `COMPLETED` with the cached response — all in one transaction.
- **Retry of a completed request** with the same payload **replays** the cached response verbatim (no re-execution).
- **Same key, different payload** → `409` (the `request_hash` guards against accidental key reuse).
- Durable, so it survives process restarts.

### Concurrency

- Both wallets are loaded with `SELECT … FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`), **ordered by id**, so two opposing transfers between the same pair always acquire locks in the same sequence — **no deadlock**.
- Funds are checked and the balance written **under the same lock**, closing the read-then-write window — **no double-spend**. The DB `CHECK (balance >= 0)` is a backstop.
- **Concurrent duplicate keys**: the `saveAndFlush` on the unique `idempotency_key` makes the second writer **block** on the index until the winner commits, then fail with a unique violation. The non-transactional orchestrator catches it and replays the committed winner — so the side effect happens **exactly once**.

### Double-Entry Ledger

Every processed transfer writes **exactly two immutable `ledger_entries`**: a DEBIT on the source and a CREDIT on the destination, each snapshotting `balance_after`. `transfer_id` is a non-null FK, so a ledger row can never be orphaned. The invariant `balance == SUM(credits) − SUM(debits)` holds for every committed transaction.

### Clean Separation

Thin controller (transport only) → orchestrating service (idempotency, no DB transaction) → transactional processor (the atomic unit) → persistence-only repositories → rich domain entities that own their own state transitions (`Transfer.markProcessed/markFailed`) and invariants (`Wallet.debit` refuses to go negative).

---

## Database Schema

| Table | Purpose | Key columns & constraints |
|---|---|---|
| `wallets` | materialized balance | `balance NUMERIC(19,2)`, `currency`, `CHECK (balance >= 0)` |
| `transfers` | request + lifecycle | `from_wallet_id`, `to_wallet_id` (FK→wallets), `amount`, `status`, `failure_reason`; `CHECK (amount > 0)`, `CHECK (from <> to)`, `CHECK (status IN …)` |
| `ledger_entries` | append-only double-entry | `wallet_id`, `transfer_id` (FKs), `type`, `amount`, `balance_after`; `CHECK (amount > 0)`, `CHECK (type IN …)` |
| `idempotency_records` | exactly-once registry | `idempotency_key UNIQUE`, `request_hash`, `status`, `target_id`, `response_status`, `response_body`; `CHECK (status IN …)` |

- **Primary keys**: UUID **v7** (time-ordered, assigned by Hibernate on persist — append-friendly index inserts).
- **Indexes**: FK columns on `transfers` and `ledger_entries`; the `UNIQUE (idempotency_key)` doubles as the duplicate-lookup index.
- **Migrations** (Flyway, applied on startup): `V1` (core tables), `V2` (idempotency registry), `V3` (narrow money columns to `NUMERIC(19,2)`).

---

## Testing

Behavioral tests (TDD: Red → Blue → Green). Integration tests run against a **real PostgreSQL via Testcontainers**, so locking and constraints are exercised for real — no in-memory substitute.

**Domain unit tests** (no Spring, no DB)
- `WalletTest` — debit reduces balance, credit increases, exact-balance debit allowed, overdraft throws and leaves the balance untouched, `hasSufficientFunds` boundary.
- `TransferTest` — `PENDING → PROCESSED`, `PENDING → FAILED` (with reason), and the guards that make retries safe (can't re-process, can't fail-after-process, can't process-after-fail).

**Integration tests** (Testcontainers)
- `TransferApiIT` — happy path (`201`, balances moved, **exactly two ledger entries** with correct `balance_after`, ledger balances); insufficient funds → `422 FAILED`, no money moved, no ledger rows; currency mismatch → `422 FAILED`; unknown wallet → `404`, nothing persisted; validation cases → `400` (blank key, non-positive amount, scale > 2, self-transfer, malformed JSON).
- `IdempotencyIT` — same key + same payload replays the original result and applies the transfer **once**; same key + different payload → `409`.
- `ConcurrencyIT` — 10 simultaneous debits of a 100-balance wallet: **exactly 5 succeed, 5 fail, balance lands at 0.00, never negative**, ledger stays consistent; 6 concurrent requests with the **same** key apply the transfer **exactly once** (one shared transfer id; every caller gets `201` or `409`).

```bash
./gradlew test     # all suites
```

---

## Documentation

- [`TechnicalDesignDocument.md`](./TechnicalDesignDocument.md) — documentation-first design note: problem statement, API contract, failure modes, idempotency/retry behavior, consistency expectations, observability, testing strategy, assumptions.
- [`implementation_details.md`](./implementation_details.md) — detailed architecture, design decisions, trade-offs, and performance considerations.

---

## Assumptions

- Transfers are **single-currency** (no FX); a currency mismatch is a recorded `FAILED` outcome.
- `idempotencyKey` is carried in the **request body** (per the assignment example); an `Idempotency-Key` header is an equivalent alternative.
- No authentication/authorization layer (out of scope).
