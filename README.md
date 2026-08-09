# LedgerFlow

A payment orchestration service with a double-entry ledger.

A merchant creates a payment, the service authorizes and captures it, and every
movement of money is recorded as balanced double-entry bookkeeping. Retries are
safe, illegal state changes are impossible, and the books are guaranteed to
balance by the database itself — not only by application code.

Built as a learning and portfolio project.

## Status

| Phase | Scope | State |
|-------|-------|-------|
| 0 | Foundations: build, Postgres, Flyway, Testcontainers, Modulith | Done |
| 1 | Ledger: money model, accounts, entries, invariants | Done |
| 2 | Payment API: state machine, idempotency keys, REST | Done |
| 3 | Provider adapter: timeouts, retries, circuit breaker, unknown outcomes | Planned |
| 4 | Events: outbox, Kafka, merchant webhooks | Planned |
| 5 | Settlement and reconciliation | Planned |
| 6 | Observability, load testing, polish | Planned |

## Stack

- Java 25, Spring Boot 4.1
- PostgreSQL 17, Flyway for schema migrations
- Spring Modulith for module boundaries
- Testcontainers for integration tests
- Gradle

## Architecture

A modular monolith — one deployable application, with modules that behave like
separate services. Boundaries are enforced by Spring Modulith and verified by a
test, so breaking one fails the build.

```
am.ankap.ledgerflow
├── shared      value types (Money) used by every module
├── ledger      accounts, entries, balances — the source of truth for money
├── payment     payment lifecycle, idempotency, REST API
├── psp         provider adapter and resilience          (phase 3)
└── recon       settlement and reconciliation            (phase 5)
```

The dependency direction is one-way and enforced: `payment` may use `ledger`,
`ledger` may only use `shared`. The ledger does not know that payments exist.

```
Merchant
   │
   ▼
Payment API ──── idempotency keys (claim before work)
   │
   ▼
Orchestrator ─── payment state machine
   │
   ├──────────► Ledger        double-entry, always balanced
   ├──────────► PSP adapter   retries, circuit breaker      (phase 3)
   └──────────► Outbox        publishes to Kafka            (phase 4)
```

### Money

Money is a value type, not a number:

```java
record Money(long minorUnits, Currency currency)
```

Amounts are stored as integer minor units (cents for USD, whole yen for JPY).
`BigDecimal` appears in exactly one place — the fee calculation — where a
percentage is rounded once, explicitly, and immediately converted back to a
`long`. Fraction digits come from `java.util.Currency`, so currencies with
zero or three decimal places work without special cases.

Adding two different currencies throws rather than compiles.

### Ledger

Every transaction is a set of entries whose amounts sum to zero. Positive is a
debit, negative is a credit. Assets grow with debits; liabilities and revenue
grow with credits.

A capture of 50.00 USD with a 1.75 fee:

| Account | Type | Amount |
|---------|------|--------|
| `PSP_CLEARING:USD` | ASSET | +5000 |
| `MERCHANT_PAYABLE:<merchant>:USD` | LIABILITY | −4825 |
| `FEE_REVENUE:USD` | REVENUE | −175 |

Balances are never stored as a mutable column — they are always computed from
entries. Entries are immutable; a mistake is corrected by posting a reversing
transaction, never by editing history.

The balance invariant is enforced twice: in the application (a transaction
cannot even be constructed unbalanced) and in Postgres (a deferred constraint
trigger that rejects unbalanced entries at commit, even when the application is
bypassed entirely).

## Running it

Requirements: Java 25 and Docker.

```bash
./gradlew bootRun
```

Spring Boot's Docker Compose support starts Postgres automatically and wires the
datasource. Flyway applies the migrations on startup.

Create a merchant to work with:

```bash
docker compose exec postgres psql -U ledgerflow -d ledgerflow \
  -c "insert into merchant (id, name) values ('11111111-1111-1111-1111-111111111111', 'Test Merchant');"
```

### API

```bash
# Create a payment
curl -X POST http://localhost:8080/v1/payments \
  -H 'Content-Type: application/json' \
  -H 'X-Merchant-Id: 11111111-1111-1111-1111-111111111111' \
  -H 'Idempotency-Key: key-001' \
  -d '{"amountMinor": 5000, "currency": "USD", "merchantRef": "order-1"}'

# Authorize, then capture
curl -X POST http://localhost:8080/v1/payments/{id}/authorize
curl -X POST http://localhost:8080/v1/payments/{id}/capture

# Read it back
curl http://localhost:8080/v1/payments/{id}
```

Sending the create request twice with the same `Idempotency-Key` returns the
same payment and creates nothing new. Sending the same key with a different body
returns `409` with a distinct error type.

Errors use RFC 9457 `application/problem+json`:

```json
{
  "type": "https://ledgerflow.dev/errors/idempotency-key-conflict",
  "title": "Idempotency key reused",
  "status": 409,
  "detail": "Idempotency key 'key-001' was used with a different request",
  "retryable": false
}
```

Amounts cross the API as minor units plus a currency code, never as decimals —
a JSON number like `12.30` can lose precision in a client's parser before it
reaches the server.

### Tests

```bash
./gradlew test
```

Integration tests run against a real PostgreSQL container. The tests worth
looking at:

- `LedgerConcurrencyTest` — 100 simultaneous posts produce an exact balance
- `LedgerDatabaseConstraintsTest` — raw SQL bypasses the service to prove the
  database constraints actually fire
- `PaymentIdempotencyConcurrencyTest` — 20 identical requests fired at once
  create exactly one payment
- `ModularityTests` — module boundaries are verified, and architecture diagrams
  are generated into `build/spring-modulith-docs/`

## Design decisions

The reasoning behind the significant choices is in
[docs/decisions.md](docs/decisions.md), including the ones deliberately *not*
taken — microservices, Kubernetes, an internal outbox, and an account hierarchy.

## Known limitations

Deliberate, and listed here rather than hidden:

- **No authentication.** The merchant comes from an `X-Merchant-Id` header. In a
  real system this would come from an authenticated API key. Out of scope.
- **Ledger check-then-insert race.** Two threads posting the same reference at
  the same instant: the unique constraint keeps the books correct, but the loser
  gets a `DataIntegrityViolationException` instead of the existing transaction
  id. Fixing it properly means moving the retry outside the transaction
  boundary, into a second bean — a self-call would bypass Spring's proxy and the
  `@Transactional` annotation would do nothing.
- **Idempotency records are never cleaned up.** They should expire after roughly
  24 hours; there is no job for that yet.
- **No merchant onboarding.** Merchant rows are inserted by hand.
- **Fee policy is hard-coded** at 2.9% + 30 minor units for every merchant.
