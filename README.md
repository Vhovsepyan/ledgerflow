# LedgerFlow

A payment orchestration service with a double-entry ledger.

A merchant creates a payment, the service authorizes and captures it through a
payment provider, and every movement of money is recorded as balanced
double-entry bookkeeping. Retries are safe, illegal state changes are
impossible, provider calls that time out are resolved rather than guessed at,
and the books are guaranteed to balance by the database itself — not only by
application code.

Every state change that a merchant can observe is published: written to an
outbox in the same transaction as the payment, relayed to Kafka, and delivered
to the merchant as an HMAC-signed webhook with retries and a dead-letter state.

Built as a learning and portfolio project. The gaps are deliberate and listed at
the bottom.

## Status

| Phase | Scope | State |
|-------|-------|-------|
| 0 | Foundations: build, Postgres, Flyway, Testcontainers, Modulith | Done |
| 1 | Ledger: money model, accounts, entries, invariants | Done |
| 2 | Payment API: state machine, idempotency keys, REST | Done |
| 3 | Provider adapter: timeouts, retries, circuit breaker, unknown outcomes | Done |
| 4 | Events: outbox, Kafka, merchant webhooks | Done |
| 5 | Settlement and reconciliation | Not started |
| 6 | Observability, load testing, polish | Not started |

Phases 5 and 6 have no code. There is no `recon` module, no payout batching, no
reconciliation against a provider statement, and no load test.

## Stack

- Java 25, Spring Boot 4.1
- PostgreSQL 17, Flyway for schema migrations
- Apache Kafka 3.8
- Spring Modulith for module boundaries
- Resilience4j for the provider circuit breaker
- Testcontainers for integration tests (Postgres and Kafka)
- Gradle

## Architecture

A modular monolith — one deployable application, with modules that behave like
separate services. Boundaries are declared in each module's `package-info.java`
and verified by `ModularityTests`, so breaking one fails the build.

```
am.ankap.ledgerflow
├── shared      Money and its exceptions — the only shared vocabulary
├── ledger      accounts, entries, balances — the source of truth for money
├── payment     payment lifecycle, idempotency, REST API, verification job
├── psp         provider adapter: retries, circuit breaker, unknown outcomes
├── outbox      transactional outbox and the relay that publishes to Kafka
└── webhook     Kafka consumer, delivery rows, signed HTTP delivery with retries
```

### Dependency rules

Taken from the `@ApplicationModule` declarations, exactly as written:

| Module | May depend on | Declared in |
|--------|---------------|-------------|
| `payment` | `shared`, `ledger`, `psp`, `outbox` | `payment/package-info.java` |
| `psp` | `shared` | `psp/package-info.java` |
| `outbox` | `shared` | `outbox/package-info.java` |
| `webhook` | `shared` | `webhook/package-info.java` |
| `ledger` | not restricted — no `@ApplicationModule` declaration | — |
| `shared` | not restricted — no `@ApplicationModule` declaration | — |

Two honest notes about that table:

- `ledger` and `shared` carry no `@ApplicationModule` annotation, so Modulith
  applies no allow-list to them. In practice `ledger` imports only `shared`, and
  `shared` imports nothing — but that is discipline, not enforcement. The
  enforced direction is the one that matters most: nothing may depend on
  `payment`, and `psp`, `outbox` and `webhook` cannot reach back into it.
- `ledger/internal/package-info.java` declares `@NamedInterface("internal")`,
  which *exposes* that package instead of hiding it. Modulith would otherwise
  treat a nested package as internal by default.

`payment` is the only module that composes the others. `webhook` is the
interesting one: it consumes payment events without depending on the `payment`
module at all. It reads the JSON envelope generically (`merchantId` out of the
payload, the rest out of Kafka headers) exactly as an external consumer would,
which means the wire format is the contract — not a shared Java type.

```
Merchant
   │
   ▼
Payment API ──── idempotency keys (claim before work)
   │
   ▼
Payment orchestration ─── explicit state machine
   │
   ├──────────► Ledger        double-entry, always balanced, same transaction
   ├──────────► PSP adapter   timeouts, retries, circuit breaker
   └──────────► Outbox        same transaction as the state change
                   │
                   ▼
              Kafka ──► Webhook consumer ──► signed HTTP POST to the merchant
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

Only capture posts to the ledger. Authorization is a promise from the provider,
not a movement of money.

## Payment state machine

`PaymentStatus` holds the whole transition table. `PaymentEntity` has no
`setStatus` — the only way to change state is `transitionTo`, which consults the
table and throws `IllegalStateTransitionException` on an illegal move. The
database repeats the list as a `check` constraint on `payment.status`.

Eight statuses:

| Status | Meaning |
|--------|---------|
| `CREATED` | Recorded, nothing sent to the provider yet |
| `AUTHORIZATION_PENDING` | Authorization is in flight, or its outcome is unknown |
| `AUTHORIZED` | The provider holds the funds; no ledger entries yet |
| `CAPTURE_PENDING` | Capture is in flight, or its outcome is unknown |
| `CAPTURED` | Money captured and posted to the ledger |
| `FAILED` | Declined, or definitively failed. Terminal |
| `CANCELED` | Abandoned before capture. Terminal |
| `REFUNDED` | Captured then returned. Terminal |

Legal transitions:

| From | To |
|------|----|
| `CREATED` | `AUTHORIZATION_PENDING`, `AUTHORIZED`, `FAILED`, `CANCELED` |
| `AUTHORIZATION_PENDING` | `AUTHORIZED`, `FAILED`, `CANCELED` |
| `AUTHORIZED` | `CAPTURE_PENDING`, `CAPTURED`, `FAILED`, `CANCELED` |
| `CAPTURE_PENDING` | `CAPTURED`, `FAILED` |
| `CAPTURED` | `REFUNDED` |
| `FAILED` | — terminal |
| `CANCELED` | — terminal |
| `REFUNDED` | — terminal |

Note what is missing: `CAPTURE_PENDING` cannot be canceled. Once a capture may
have reached the provider, the only honest answers are "it worked" or "it did
not" — never "never mind".

`CANCELED` and `REFUNDED` are in the machine and in the schema, but no endpoint
or job ever moves a payment into them yet. See known limitations.

Payments carry `@Version`, so two concurrent captures cannot both succeed. The
ledger needs no locking because it is append-only.

## Provider calls and unknown outcomes

The `psp` module wraps one external provider over HTTP: a 1s connect timeout, a
2s read timeout, up to 3 attempts with exponential backoff and jitter, all
behind a Resilience4j circuit breaker (20-call window, opens at a 50% failure
rate, 10s open, 3 half-open probes). Every call carries an `Idempotency-Key`
the provider honours — that is the only reason retrying is safe.

The adapter returns a sealed `PspResult`:

| Result | Meaning |
|--------|---------|
| `Authorized(pspReference)` | The provider is holding the funds |
| `Captured(pspReference, capturedMinor)` | The provider captured |
| `Declined(pspReference, reason)` | The provider said no |
| `Failed(reason)` | The request never reached the provider. Nothing happened |
| `Unknown(reason)` | It may have reached them. Nobody knows |

`Failed` versus `Unknown` is the distinction the whole module exists for. A
connection refused, or a call the breaker never let out, is `Failed` — the
payment can be failed immediately. A read timeout means the request was on the
wire and may have been applied, so it becomes `Unknown` and the payment stays
in its `*_PENDING` state.

`PaymentVerificationJob` then owns the resolution. It runs every 10s, picks up
payments whose `next_verification_at` is due, asks the provider what really
happened (`GET /psp/authorizations?reference=payment-<id>`), and applies the
answer. Backoff doubles from 15s up to 30 minutes; after 8 attempts it logs an
error for a human but keeps trying. A pending payment is never auto-failed —
money may have moved.

Every attempt is written to `psp_attempt` (operation, request count, outcome,
provider reference, detail, latency), so a payment's provider history is
inspectable after the fact.

## Event flow

One path from a state change to a merchant's server:

```
capture()                       one transaction:
  ├─ payment.status = CAPTURED    payment row
  ├─ ledger entries               ledger_transaction + ledger_entry
  └─ outbox_event row             outbox_event (published_at null)
                     │
                     │  OutboxRelay, every 1s
                     │  select … where published_at is null
                     │  order by sequence_no limit 50 for update skip locked
                     ▼
              Kafka topic `payment-events`
              key   = payment id      (ordering per payment)
              value = the event JSON
              headers: event-id, event-type, aggregate-type,
                       aggregate-id, occurred-at
                     │
                     │  PaymentEventConsumer, group `webhook-dispatcher`
                     │  one row per active endpoint of that merchant
                     │  unique (endpoint_id, event_id) absorbs redeliveries
                     ▼
              webhook_delivery (PENDING)
                     │
                     │  WebhookDispatcher, every 2s
                     │  select … where status='PENDING' and next_retry_at <= now
                     │  order by next_retry_at limit 20 for update skip locked
                     ▼
              POST <merchant url>
                X-Ledgerflow-Event-Id, -Event-Type, -Timestamp
                X-Ledgerflow-Signature: v1=<HMAC-SHA256 of "timestamp.payload">
```

Each hop and what it buys:

1. **Payment → outbox.** `OutboxService.append` is `Propagation.MANDATORY` and
   additionally asserts an active transaction. There is no way to record an
   event outside the transaction that caused it, so "state changed but nobody
   was told" cannot happen.
2. **Outbox → Kafka.** `OutboxRelay` polls every second, takes a bounded batch
   with `FOR UPDATE SKIP LOCKED`, and publishes with a 5s send timeout. A
   failure throws, the row stays unpublished, `publish_attempts` and
   `last_error` are recorded, and the rest of the batch is abandoned so that a
   later event can never overtake an earlier one.
3. **Kafka → delivery rows.** The consumer does one thing: turn one event into
   one `webhook_delivery` row per active endpoint. It never calls out over the
   network, so a slow merchant cannot stall the consumer group or push the
   partition into rebalancing.
4. **Delivery rows → merchant.** `WebhookDispatcher` is a separate scheduled
   job owning all retry state in the database.

Delivery outcome rules:

| Response | Action |
|----------|--------|
| `2xx` | `DELIVERED` |
| `4xx` except `429` | `DEAD` immediately — the endpoint understood and refused |
| `429`, `5xx`, timeout, connection error | Retry |

Retries back off 5s, 10s, 20s, 40s, 80s, 160s, 320s, capped at 10 minutes, and
give up at 8 attempts with status `DEAD`.

### Published event schema

From `PaymentEvents`. Three event types, each a record serialized to JSON:

`payment.authorized`

```json
{
  "version": 1,
  "paymentId": "…", "merchantId": "…", "merchantRef": "order-1",
  "amountMinor": 5000, "currency": "USD",
  "pspReference": "auth_…", "occurredAt": "2026-01-01T00:00:00Z"
}
```

`payment.captured` — the same fields plus `feeMinor` and `merchantNetMinor`.

`payment.failed` — `paymentId`, `merchantId`, `merchantRef`, `amountMinor`,
`currency`, `reason`, `occurredAt`.

**Compatibility rule**, stated in the source and honoured here:

- Additive changes only. A field is never renamed and never removed.
- `version` is bumped when the *meaning* of an existing field changes. It is
  `1` today, set in one place (`PaymentEventFactory.CURRENT_VERSION`).
- No internal or operational detail is published — no retry counts, no provider
  error strings, no verification attempts. Those live in `psp_attempt` and the
  logs, where changing them breaks nobody.

The events are deliberately fat: everything a consumer needs to act is in the
payload, so no consumer has to call back to read the payment.

## Running it

Requirements: Java 25 and Docker.

```bash
./gradlew bootRun
```

Spring Boot's Docker Compose support reads `compose.yml`, starts Postgres and
Kafka, and wires both. Flyway applies the migrations on startup. Kafka topic
`payment-events` (3 partitions) is created by the application.

Create a merchant to work with:

```bash
docker compose exec postgres psql -U ledgerflow -d ledgerflow \
  -c "insert into merchant (id, name) values ('11111111-1111-1111-1111-111111111111', 'Test Merchant');"
```

### The mock provider

There are two, for two different jobs.

**1. `mock-psp` — the standalone Spring Boot project** (a sibling directory,
its own Gradle build). Use this one for demos: it survives restarts of the main
app, has a `GET /admin/chaos` you can read back, and includes a webhook receiver
that verifies signatures.

```bash
cd ../mock-psp
./gradlew bootRun          # listens on :9090
# or: docker compose up    # same thing, containerised
```

Then point LedgerFlow at it — it is the default, so nothing to do unless you
moved it:

```bash
PSP_BASE_URL=http://localhost:9090 ./gradlew bootRun
```

Note that `mock-psp` starts **misbehaving by default**: 10% declines, 10% HTTP
500s, 10% swallowed responses, per `application.yml`. Turn it off for a clean
run (see below).

**2. `MockPsp` — an in-repo test double** (`src/test/.../psp/MockPsp.java`), a
single class on the JDK's `HttpServer`. It is what `PspServiceManualTest`
starts on a random port, and it can be run by hand:

```bash
./gradlew mockPsp -Pport=9090
```

It speaks the same wire protocol and has the same three chaos rates, but only
`POST /admin/chaos`, no webhook receiver, and no configurable latency. It starts
with chaos **off**.

### Demonstrating failure with `/admin/chaos`

`mock-psp` exposes the current settings on `GET /admin/chaos` and accepts a
partial update on `POST`. Fields: `declineRate`, `errorRate`, `timeoutRate`
(0.0–1.0), `baseLatencyMs`, `timeoutDelayMs`.

```bash
# What is it doing right now?
curl http://localhost:9090/admin/chaos

# Behave.
curl -X POST http://localhost:9090/admin/chaos \
  -H 'Content-Type: application/json' \
  -d '{"declineRate":0, "errorRate":0, "timeoutRate":0}'
```

**Every authorization is declined** — the payment fails immediately and
`payment.failed` is published:

```bash
curl -X POST http://localhost:9090/admin/chaos \
  -H 'Content-Type: application/json' -d '{"declineRate":1.0}'
```

**Every call returns 500** — three attempts, then the circuit breaker opens
after enough failures and later calls return `Failed` without a request being
sent. Watch for `PSP circuit breaker: CLOSED_TO_OPEN` in the log:

```bash
curl -X POST http://localhost:9090/admin/chaos \
  -H 'Content-Type: application/json' -d '{"errorRate":1.0, "declineRate":0}'
```

**Every response is swallowed** — this is the interesting one. The provider
records the authorization *first*, then sleeps 8s, well past the 2s read
timeout. The caller gets nothing:

```bash
curl -X POST http://localhost:9090/admin/chaos \
  -H 'Content-Type: application/json' -d '{"timeoutRate":1.0, "errorRate":0, "declineRate":0}'
```

Now authorize a payment. The API returns `202 Accepted` with status
`AUTHORIZATION_PENDING` and a `Retry-After` header, because the outcome is
`Unknown` and guessing would be wrong in one direction or the other. Turn chaos
back off, wait for the verification job, and the payment resolves to
`AUTHORIZED` with the provider's real reference — the authorization had existed
the whole time.

To watch the webhook side, point an endpoint at the receiver built into
`mock-psp` (secret `test-webhook-secret`, hard-coded there):

```sql
insert into webhook_endpoint (id, merchant_id, url, secret, active)
values (gen_random_uuid(),
        '11111111-1111-1111-1111-111111111111',
        'http://localhost:9090/merchant/webhooks',
        'test-webhook-secret',
        true);
```

```bash
curl http://localhost:9090/merchant/webhooks                    # what arrived
curl -X POST http://localhost:9090/merchant/webhooks/failure-rate \
  -H 'Content-Type: application/json' -d '{"failureRate":1.0}'  # reject with 503
curl -X DELETE http://localhost:9090/merchant/webhooks          # clear
```

With `failureRate` at 1.0 the receiver returns 503 and you can watch
`webhook_delivery.attempts` and `next_retry_at` climb, then drop to `DEAD` after
eight attempts. Set it back to 0 partway through and the pending delivery
succeeds on its next pass. The receiver also verifies the HMAC and answers 400
on a mismatch, which is the dead-letter-immediately path.

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

`authorize` and `capture` return `200 OK` when the payment reached a settled
state, and `202 Accepted` with `Retry-After` when it is left in
`AUTHORIZATION_PENDING` or `CAPTURE_PENDING` — the request was accepted, the
answer is not in yet, poll the payment.

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
./gradlew test          # everything except the tests tagged "manual"
./gradlew manualTest    # slow narrative tests against the in-repo mock provider
```

Integration tests run against real PostgreSQL and Kafka containers. The ones
worth looking at:

- `LedgerConcurrencyTest` — 100 simultaneous posts produce an exact balance
- `LedgerDatabaseConstraintsTest` — raw SQL bypasses the service to prove the
  database constraints actually fire
- `PaymentIdempotencyConcurrencyTest` — 20 identical requests fired at once
  create exactly one payment
- `PaymentVerificationTest` — a timed-out authorization the provider *did*
  apply gets resolved by the verification job; a capture that stays unknown
  never auto-fails
- `OutboxTest` — payment, ledger entries and outbox row commit together, and
  the relay drains them
- `KafkaPublishingTest` — events arrive on one partition, in order, keyed by
  payment id
- `WebhookDeliveryTest` — a 5xx is retried until it succeeds, a 4xx is
  dead-lettered after exactly one attempt, and a redelivered event does not
  create a second delivery row
- `ModularityTests` — module boundaries are verified, and architecture diagrams
  are generated into `build/spring-modulith-docs/`

## Design decisions

The reasoning behind the significant choices is in
[docs/decisions.md](docs/decisions.md), including the ones deliberately *not*
taken — microservices, Kubernetes, Debezium, Spring Modulith's event registry,
a topic per event type, and an account hierarchy.

## Known limitations

Deliberate, and listed here rather than hidden.

**Not built at all**

- **No settlement or reconciliation** (phase 5). Captured money sits in
  `MERCHANT_PAYABLE` forever; nothing pays it out and nothing compares the
  ledger against a provider statement.
- **No refunds or cancellations.** `REFUNDED` and `CANCELED` exist in the state
  machine and in the schema check constraint, but no endpoint or job ever
  transitions into them. The state machine is ahead of the API on purpose;
  claiming otherwise would be the dishonest part.
- **No authentication.** The merchant comes from an `X-Merchant-Id` header. In a
  real system this would come from an authenticated API key. There is no Spring
  Security on the classpath.
- **No merchant onboarding and no endpoint registration API.** Merchant rows and
  `webhook_endpoint` rows are inserted by hand. Endpoint secrets are stored in
  plaintext and cannot be rotated.
- **No metrics beyond the defaults** (phase 6). Actuator and
  `spring-modulith-observability` are on, nothing custom is instrumented, and
  there is no load test.

**Simplified on purpose**

- **Fee policy is hard-coded** at 2.9% + 30 minor units for every merchant,
  capped so the fee can never exceed the amount.
- **Idempotency records are never cleaned up.** They should expire after roughly
  24 hours; there is no job for that yet. Published `outbox_event` rows and
  `DELIVERED` `webhook_delivery` rows are likewise never archived, so all three
  tables grow forever.
- **Dead deliveries have no operator story.** A `DEAD` row is the end of the
  line: no replay endpoint, no alert, no automatic disabling of an endpoint that
  fails permanently. Same for an `outbox_event` that keeps failing —
  `publish_attempts` and `last_error` are recorded, but nothing ever gives up or
  raises anything.
- **The outbox relay stops the whole batch on the first failure.** That protects
  ordering, but a single poisonous event blocks every other aggregate's events
  until it succeeds or is fixed by hand. Per-aggregate skipping would be the
  real fix.
- **Ledger check-then-insert race.** Two threads posting the same reference at
  the same instant: the unique constraint keeps the books correct, but the loser
  gets a `DataIntegrityViolationException` instead of the existing transaction
  id. Fixing it properly means moving the retry outside the transaction
  boundary, into a second bean — a self-call would bypass Spring's proxy and the
  `@Transactional` annotation would do nothing.
- **A lost optimistic-lock race returns 500.** Two concurrent captures cannot
  both succeed — that part works — but the loser's
  `OptimisticLockingFailureException` has no handler in
  `PaymentExceptionHandler`, so it surfaces as a 500 instead of a 409. The
  sequential case (capturing an already-captured payment) is correctly a 409.

**True only on a single instance**

- **Event ordering holds for one application instance.** `SKIP LOCKED` lets two
  relays run without stepping on each other, but nothing stops instance A from
  taking a payment's first event while instance B takes its second, and two
  producers have no shared ordering guarantee. Correct multi-instance ordering
  would need partitioning by aggregate, or one relay elected as leader.
- **`PaymentVerificationJob` takes no lock.** Unlike the relay and the
  dispatcher, it selects due payments without `FOR UPDATE SKIP LOCKED`, so two
  instances would both look up the same payment at the provider. Harmless —
  lookups are read-only and applying the same result twice is a no-op — but
  wasteful, and it is inconsistent with the other two jobs.

## What I would add next

In the order I would actually do it:

1. **Cancel and refund endpoints.** The state machine already permits them and
   the refund is the more interesting one, because it is the first reversing
   ledger transaction and the first case where a payment leaves a terminal-ish
   state legitimately.
2. **Retention.** One scheduled job that expires idempotency records after 24h
   and archives published outbox rows and delivered webhook rows. Three tables
   that grow forever is the most boring way this project would fall over.
3. **An operator surface for dead letters.** List `DEAD` deliveries and stuck
   `outbox_event` rows, replay one, disable an endpoint that has failed
   permanently. Right now the answer to "a merchant missed a webhook" is `psql`.
4. **Merchant and endpoint management,** with API-key authentication replacing
   the `X-Merchant-Id` header and hashed endpoint secrets that can be rotated
   with an overlap window.
5. **Settlement (phase 5):** a scheduled job that nets `MERCHANT_PAYABLE` into a
   payout, posts it to the ledger, and reconciles against a provider statement
   the mock can be taught to produce. This is the phase that would exercise the
   ledger properly — everything so far only ever posts one shape of entry.
6. **Multi-instance correctness:** partition the relay by aggregate id, put
   `SKIP LOCKED` on the verification job, and prove it with a test that runs two
   application contexts against one database.
