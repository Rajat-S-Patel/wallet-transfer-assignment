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

---

## AI Usage

*(Disclosure per [`ASSIGNMENT.md`](./ASSIGNMENT.md) § AI usage.)*

**Tool used:** Antigravity.

**How I used it.** I worked design-first — I owned the architecture and used the AI as a pair-programmer to turn my decisions into code and tests faster:

- **Made the key design decisions myself**: the layering (controller / orchestrating service / transactional processor / repositories / domain), the dedicated idempotency-registry table, pessimistic locking with deterministic lock ordering, the materialized-balance strategy, UUID v7 keys, and treating a business failure as a recorded `FAILED` outcome rather than an exception.
- **Started from a written design note** ([`TechnicalDesignDocument.md`](./TechnicalDesignDocument.md)) and drove implementation from that spec, rather than asking the AI to invent the approach.
- **Used it to accelerate the mechanical work**: scaffolding boilerplate, drafting entities/migrations from my schema, and generating the Testcontainers test cases for scenarios I specified.
- **Reviewed and corrected every suggestion**, often sending it back for revision — e.g. rejected an early `@Version` optimistic-lock approach, redirected the concurrent-duplicate handling toward the non-transactional-orchestrator + transactional-processor split, and had it remove leftover debug code and fix the idempotency reservation semantics.
- **Pressure-tested the design** by having it explain trade-offs and edge cases (concurrency races, retry safety), then validated the conclusions against the code and tests.

**Representative prompts.** These are the kinds of prompts I used through the session, written the way I actually asked them — each one lays out what I wanted and how it should behave, so the AI was filling in a design I'd already thought through:

1. *"Let's set up a Spring Boot 3.3 project on Java 21 with Gradle for a wallet-transfer service. I want PostgreSQL with Flyway handling the schema, plus Spring Data JPA, bean validation, and Lombok. Add a docker-compose so I can run Postgres locally, and set Hibernate to validate only — Flyway should own the schema, not Hibernate."*

2. *"I want four tables. Wallets keeps a running balance and a currency. Transfers holds the from/to wallet, amount, status, and a failure reason. Ledger entries is an append-only double-entry log — wallet, transfer, type, amount, and the balance right after. And an idempotency records table for dedup. Use UUID v7 for the ids and assign them in the app. Pull the id and the created/updated timestamps into a BaseEntity and AuditableEntity that every entity extends. Also put the real guardrails in the database itself — balance can't go negative, amount has to be positive, you can't transfer to the same wallet, and only valid status/type values are allowed."*

3. *"Why did you put the idempotency key on the transfers table? I'd rather have a separate idempotency table — I need to send back the cached response when a duplicate comes in, and I want to reuse the same idempotency mechanism on other endpoints later. Store the key as unique, a hash of the request so I can detect a reused key with a different body, a status that goes from in-progress to completed, the id of whatever it created, and the cached response."*

4. *"Now implement the actual transfer. It should all happen in one transaction, in this order: reserve the idempotency key first and flush it so a duplicate hits the unique index early; lock both wallets with SELECT … FOR UPDATE, always in id order so we don't deadlock; create the transfer as PENDING; then check currency and funds while the rows are locked. If it fails, mark the transfer FAILED with a reason and stop. If it's fine, debit the sender, credit the receiver, write the two ledger entries with the balance-after, and mark it PROCESSED. Then save the response onto the idempotency record. Treat insufficient funds as a normal FAILED outcome that returns 422 — not an exception."*

5. *"What do you mean the loser of a concurrent duplicate gets a 409 instead of a replay? I don't want that — if two identical requests race, the second one should still get back the original result. Walk me through whether committing the reservation early actually helps here, then set it up so a non-transactional service calls a transactional processor: the second request blocks on the unique key until the first commits, fails, and then we just replay the winner's cached response."*

6. *"Keep the controller thin — just validate, hand off to the service, and turn the result into a status code (201 for processed, 422 for a recorded failure). Add a global exception handler that returns one consistent error body: 400 for validation, 404 for an unknown wallet, 409 for an idempotency conflict or race, and 500 for anything unexpected."*

7. *"Add validation on the request — the idempotency key can't be blank, both wallet ids are required, the amount has to be positive and at most two decimal places, and reject a transfer where the source and destination are the same wallet."*

8. *"Write tests, and keep them behavioral. Plain unit tests for the wallet's overdraft guard and the transfer state transitions. Then integration tests on a real Postgres with Testcontainers — the happy path with the two ledger entries and correct balances, insufficient funds and currency mismatch coming back as 422 with nothing moved, unknown wallet as 404, and the validation cases as 400. Add idempotency tests for replaying the same key and for rejecting a reused key with a different body. And concurrency tests that prove parallel debits can't overdraw and that firing the same key many times at once still only does the transfer once."*

9. *"Help me write up the docs — a design document with the contract, failure modes, idempotency and retry behavior, consistency guarantees and the testing strategy; a README with the stack, how to run it, the architecture, the API, the schema and the tests; and a separate implementation-details note going deeper on the design decisions, trade-offs and performance."*

