# LedgerFlow

A payment orchestration service with a double-entry ledger.

A merchant creates a payment, the service authorizes and captures it through a
payment provider, and every movement of money is recorded as balanced
double-entry bookkeeping. Retries are safe, illegal state changes are
impossible, provider calls that time out are resolved rather than guessed at,
and the books are guaranteed to balance by the database itself — not only by
application code.

Every state change a merchant can observe is published: written to an outbox in
the same transaction as the payment, relayed to Kafka, and delivered as an
HMAC-signed webhook with retries and a dead-letter state. Every night the
provider's settlement statement is compared against the ledger, and the money
the provider actually paid is posted as a settlement batch.

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
| 5 | Settlement and reconciliation | Done |
| 6 | Observability and documentation | Done, deliberately narrowed |

Phase 6 was scoped down to the parts that change how the system is built:
business metrics, trace context that survives the asynchronous hops, and health
that separates "the books are wrong" from "the process is unhealthy". Three
things that were on the original list were skipped on purpose, because they
demonstrate tooling rather than design: a Grafana dashboard, a k6 load test, and
Kubernetes manifests. The metrics a dashboard would draw are all exported; no
dashboard JSON is committed.

## Stack

- Java 25, Spring Boot 4.1
- PostgreSQL 17, Flyway for schema migrations
- Apache Kafka 3.8
- Spring Modulith for module boundaries
- Resilience4j for the provider circuit breaker
- Micrometer with a Prometheus registry, and OpenTelemetry tracing
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
├── webhook     Kafka consumer, delivery rows, signed HTTP delivery with retries
└── recon       settlement statements, mismatches, settlement batches
```

### Dependency rules

Taken from the `@ApplicationModule` declarations, exactly as written:

| Module | May depend on |
|--------|---------------|
| `payment` | `shared`, `ledger`, `psp`, `outbox` |
| `recon` | `shared`, `ledger`, `payment` |
| `psp` | `shared` |
| `outbox` | `shared` |
| `webhook` | `shared` |
| `ledger` | `shared` |
| `shared` | *none* |

The intended rule is one-way: `payment` may use `ledger`, and `ledger` may only
use `shared`. The ledger does not know that payments exist — it takes a source
type, a source id and an operation, and has no idea what any of them mean.

That rule is enforced end to end: `payment` names `ledger` in its allow-list,
so the direction `payment → ledger` is legal and `ledger → payment` is not.
`psp`, `outbox` and `webhook` may touch only `shared`, so none of them can
reach back into `payment`. `ledger` names only `shared` in its allow-list, and
`shared` declares no allowed dependencies at all. Breaking any of these fails
`ModularityTests`.

Two more things the module graph does not tell you:

- `webhook` depends only on `shared`. It consumes payment events without
  importing anything from `payment`: it reads the JSON envelope generically and
  takes the event type from a Kafka header, exactly as an external consumer
  would. The wire format is the contract, not a shared Java type.
- `recon` declares a dependency on `payment` but imports no Java type from it.
  What it actually does is read `payment` and `psp_attempt` **as tables**, in
  raw SQL. Modulith checks Java references; it cannot see a `select`. The
  coupling is real and is listed under limitations.

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

Nightly:  provider statement ──► Reconciliation ──► mismatches for a human
                                        └────────► settlement batch ──► Ledger
```

## The payment lifecycle

`PaymentStatus` holds the whole transition table. `PaymentEntity` has no
`setStatus` — the only way to change state is `transitionTo`, which consults the
table and throws `IllegalStateTransitionException` on an illegal move. The
database repeats the list as a `check` constraint on `payment.status`.

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

### Why two pending statuses and not one

`AUTHORIZATION_PENDING` and `CAPTURE_PENDING` are separate statuses rather than
one `PENDING` plus a column saying which operation is outstanding.

The reason is that the transition table is the entire safety argument, and a
flag sitting next to it is not covered by that argument. "A capture that may be
in flight cannot be canceled" is one missing entry in the table —
`CAPTURE_PENDING` permits only `CAPTURED` and `FAILED`. Expressed as a boolean,
it would be an `if` that every future code path has to remember.

It also keeps a payment row honest on its own. Someone reading a single row
during an incident — in `psql`, at two in the morning — sees
`CAPTURE_PENDING` and knows immediately that money may have moved and that the
row is not final. A row that said `AUTHORIZED` with a separate flag two columns
over would read as settled, and the flag is exactly the thing a tired person
misses. The pending state is also committed *before* the provider is called, so
a crash mid-call leaves a row that says "a call may be in flight" rather than
one that looks untouched.

### Why authorization writes no ledger entries and capture does

Authorization is a promise, not a movement. The provider agrees to hold the
customer's funds; nothing has left anyone's account and no money is owed to the
merchant yet. Posting an entry would assert a movement that has not happened —
and if the payment is then canceled or expires, the ledger would have to be
unwound, which in an append-only ledger means a reversing transaction to undo
something that never occurred.

Capture is the movement, so capture posts. This is also why `AUTHORIZED` is not
a terminal state and carries no ledger consequence at all.

## The ledger

### Money is a value type

```java
record Money(long minorUnits, Currency currency)
```

Amounts are stored as integer minor units (cents for USD, whole yen for JPY).
Adding two different currencies throws rather than compiles. `BigDecimal`
appears in exactly one place — the fee calculation — where a percentage is
rounded once, explicitly, and immediately converted back to a `long`. Fraction
digits come from `java.util.Currency`, so currencies with zero or three decimal
places work without special cases.

### Entries carry a signed amount

Positive is a debit, negative is a credit. Assets grow with debits; liabilities
and revenue grow with credits. The invariant is then a single `sum(amount) = 0`
rather than a comparison of two aggregates.

Callers never handle signs: the builder exposes `debit(...)` and `credit(...)`,
both taking positive amounts, and owns the negation.

A capture of 50.00 USD with a 1.75 fee posts three entries:

| Account | Type | Amount |
|---------|------|--------|
| `PSP_CLEARING:USD` | ASSET | +5000 |
| `MERCHANT_PAYABLE:<merchant>:USD` | LIABILITY | −4825 |
| `FEE_REVENUE:USD` | REVENUE | −175 |

Sum: `5000 − 4825 − 175 = 0`. Read as a sentence: the provider owes us 50.00, of
which we owe the merchant 48.25 and have earned 1.75.

When the provider later pays out, settlement posts two more entries against the
same clearing account, which is what drains it back to zero:

| Account | Type | Amount |
|---------|------|--------|
| `BANK:USD` | ASSET | +5000 |
| `PSP_CLEARING:USD` | ASSET | −5000 |

### The balance invariant is enforced twice

In the type system: a `LedgerTransactionRequest` cannot be constructed
unbalanced, because `build()` refuses. The service method that posts needs no
runtime balance check, since an unbalanced request cannot reach it.

In the database: a **deferred constraint trigger** re-checks `sum(amount) = 0`
per currency at `COMMIT`. The application check protects the paths that were
remembered; the database protects the ones that were not — migrations, `psql`
sessions, and future code. In a ledger, those are exactly the situations where
the books go wrong silently.

Two more invariants live in Postgres:

- **Entries are immutable.** A trigger raises on `UPDATE` and `DELETE`.
  Corrections are posted as reversing transactions, as in real accounting.
- **No cross-currency entry.** `ledger_entry` has a composite foreign key on
  `(account_id, currency)` referencing `ledger_account (id, currency)`, so an
  entry in the wrong currency cannot be written — no trigger required, just a
  key.

Balances are never stored as a mutable column; they are always computed from
entries.

### Transactions record their source in columns, not in a parsed string

`ledger_transaction` carries `source_type`, `source_id` and `source_operation`.
The human-readable `reference` is derived from them
(`payment:<uuid>:capture`) and still carries the unique constraint that makes
posting idempotent, but nothing reads meaning back out of it by splitting on
colons. Reconciliation asks for captures with
`source_type = 'payment' and source_operation = 'capture'` — an indexed query
against typed columns.

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

**The rule that separates the last two is whether the request reached the
provider.** A refused connection is `Failed` — nothing was sent, so nothing
happened, and the payment can be failed immediately with a clear conscience. A
call the circuit breaker refused is also `Failed`, by definition. A read timeout
is `Unknown`: the bytes were on the wire and the provider may well have applied
the charge; we simply never heard the answer. Any response the provider actually
gave that could not be interpreted is `Unknown` too.

Getting this backwards is expensive in both directions. Failing an `Unknown`
authorization tells a merchant their customer was not charged while the provider
holds the customer's money. Treating a `Failed` as `Unknown` leaves payments
pending that could have been resolved instantly.

`PaymentVerificationJob` owns the resolution. It runs every 10s, picks up
payments whose `next_verification_at` is due, asks the provider what really
happened (`GET /psp/authorizations?reference=payment-<id>`), and applies the
answer. Backoff doubles from 15s to a 30-minute cap; after 8 attempts it logs an
error for a human but **keeps trying and never auto-fails the payment** — an
unresolved payment is a question, and inventing an answer to it is how money
goes missing.

Every attempt is written to `psp_attempt` (operation, request count, outcome,
provider reference, detail, latency), which is both the audit trail and the raw
material reconciliation uses to explain a mismatch later.

## The event flow

One path from a state change to a merchant's server:

```
capture()                       one transaction:
  ├─ payment.status = CAPTURED    payment row
  ├─ ledger entries               ledger_transaction + ledger_entry
  └─ outbox_event row             outbox_event (published_at null, trace_id)
                     │
                     │  OutboxRelay, every 1s
                     │  select … where published_at is null
                     │  order by sequence_no limit 50 for update skip locked
                     ▼
              Kafka topic `payment-events`
              key   = payment id      (ordering per payment)
              value = the event JSON
              headers: event-id, event-type, aggregate-type,
                       aggregate-id, occurred-at, traceparent
                     │
                     │  PaymentEventConsumer, group `webhook-dispatcher`
                     │  one row per active endpoint of that merchant
                     │  insert … on conflict (endpoint_id, event_id) do nothing
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

What each hop buys:

1. **Payment → outbox, atomically.** `OutboxService.append` is
   `Propagation.MANDATORY` and additionally asserts an active transaction. The
   payment row, the ledger entries and the event are one commit. "State changed
   but nobody was told" is not a reachable state.
2. **Outbox → Kafka.** The relay takes a bounded batch with
   `FOR UPDATE SKIP LOCKED` and publishes with a 5s send timeout. A failure
   throws, the row stays unpublished, `publish_attempts` and `last_error` are
   recorded, and the rest of the batch is abandoned so a later event can never
   overtake an earlier one.
3. **Kafka → delivery rows.** The consumer only inserts rows. It never makes an
   HTTP call, so a slow merchant cannot stall the consumer group or trigger a
   rebalance. Kafka is at-least-once, so the same event will arrive twice; the
   insert uses `on conflict (endpoint_id, event_id) do nothing`, which absorbs
   the duplicate without marking the transaction rollback-only.
4. **Delivery rows → merchant.** A separate scheduled job owns every attempt,
   the backoff schedule and the dead-lettering, all in the database.

Delivery outcomes:

| Response | Action |
|----------|--------|
| `2xx` | `DELIVERED` |
| `4xx` except `429` | `DEAD` immediately — the endpoint understood and refused |
| `429`, `5xx`, timeout, connection error | Retry |

Retries back off 5s, 10s, 20s, 40s, 80s, 160s, 320s, capped at 10 minutes, and
give up at 8 attempts with status `DEAD`.

### The published event schema

From `PaymentEvents`. Three event types, each a record serialized to JSON:

`payment.authorized`

```json
{
  "version": 1,
  "paymentId": "…", "merchantId": "…", "merchantRef": "order-1",
  "amountMinor": 5000, "currency": "USD",
  "pspReference": "auth_…", "occurredAt": "2026-08-14T10:00:00Z"
}
```

`payment.captured` — the same fields plus `feeMinor` and `merchantNetMinor`.

`payment.failed` — `version`, `paymentId`, `merchantId`, `merchantRef`,
`amountMinor`, `currency`, `reason`, `occurredAt`.

**The compatibility rule**, stated in the source and honoured here:

- **Additive changes only.** A field is never renamed and never removed.
- **`version` is bumped when the meaning of an existing field changes** — not
  when a field is added, because adding one breaks nobody. It is `1` today, set
  in one place, `PaymentEventFactory.CURRENT_VERSION`.
- **No internal or operational detail is published.** No retry counts, no
  provider error strings, no verification attempts. Those live in `psp_attempt`
  and the logs, where changing them breaks nobody.

The events are deliberately fat: everything a consumer needs to act is in the
payload, so no consumer has to call back to read the payment.

## Reconciliation and settlement

Every night at 02:00 (`ledgerflow.recon.cron`), reconciliation fetches the
provider's statement for the previous day and compares it, line by line, against
what the ledger says was captured. A run is recorded in `recon_run` whether or
not anything is wrong, because "we checked and it was fine" is information too,
and an absent run is the failure mode that looks like silence.

### Three ways the two sides can disagree

| Type | Meaning |
|------|---------|
| `MISSING_IN_LEDGER` | The provider settled something with no matching capture on our side |
| `MISSING_IN_PROVIDER` | We captured it; the statement does not mention it |
| `AMOUNT_MISMATCH` | Both have it, with different amounts |

### The timing rule

`MISSING_IN_PROVIDER` is the type that would otherwise cry wolf every night,
because a capture made an hour before the statement was cut is *supposed* to be
absent from it. So a capture younger than **24 hours** is counted as
`pendingTiming` rather than filed as a mismatch, judged by the payment's
`updated_at`.

This is the one piece of automatic judgement in the whole module, and it is a
guess about the provider's schedule rather than a fact. The cost is stated in
the source: if the real lag is longer than 24 hours, genuine problems get filed
as "pending" and stay invisible. It auto-resolves the *timing* difference only —
never an amount, never a missing payment.

### Evidence, not corrections

Each mismatch is stored with two human-facing fields, built by
`EvidenceCollector` from the provider attempt log:

- **`evidence`** — the literal history of provider calls for that payment: every
  attempt with its outcome, retry count, latency and detail, in order.
- **`suggestion`** — a reading of that history. If any attempt came back
  `UNKNOWN`, the note says so and warns that the provider's figure is probably
  the correct one. If every call returned a definite answer, it says the
  difference looks like a real discrepancy rather than a retry artefact.

**Nothing is ever corrected automatically.** No mismatch adjusts the ledger, and
resolving one through the API records a decision — status, who decided, and a
note — without posting a single entry. A correcting entry, if one is needed, is
posted deliberately and separately.

That restraint is the point of the module. An automatic correction is a guess
about which of two systems is right, applied to money, at 2am, with nobody
watching. The provider is usually right about what they *paid*; we are usually
right about what we *charged*; when those differ, the interesting cases are
exactly the ones no rule anticipates. A mismatch resolves once and cannot be
reopened — `resolve()` refuses to move a row out of `RESOLVED` or `IGNORED`, and
refuses to reopen anything.

Re-running the same day does not pile up duplicates: a mismatch that is still
`OPEN` for the same reference and type is left alone.

### Settlement

Matched lines settle. `SettlementPoster` groups them by currency and posts **one
ledger transaction per currency per day**, debiting `BANK:<ccy>` and crediting
`PSP_CLEARING:<ccy>` for the total — which is what actually happens, since a
provider pays out one lump sum, not one wire per payment. `settlement_batch`
records the date, currency, total, payment count and the ledger transaction id,
with a unique constraint on `(settlement_date, currency)` so a re-run cannot
double-post.

**Disputed amounts do not settle.** Only matched lines are included in the
total, so a payment with an `AMOUNT_MISMATCH` moves no money until a human has
decided what happened. Which payments were in a batch is answered by `recon_run`
and `recon_mismatch`, not by the ledger.

### The admin API

```bash
# Run reconciliation for a date (defaults to today)
curl -X POST 'http://localhost:8080/admin/reconciliation/runs?date=2026-08-13'

# Recent runs, and everything still open
curl http://localhost:8080/admin/reconciliation/runs
curl http://localhost:8080/admin/reconciliation/mismatches

# Record a human decision — this does not touch the ledger
curl -X POST http://localhost:8080/admin/reconciliation/mismatches/{id}/resolve \
  -H 'Content-Type: application/json' \
  -d '{"status": "RESOLVED", "resolvedBy": "vahe", "note": "Provider applied a partial capture"}'
```

## Observability

### Metrics

Micrometer, exported on `/actuator/prometheus`. Names are dotted in code and
rendered with underscores by the Prometheus registry, so
`ledgerflow.outbox.unpublished` is scraped as `ledgerflow_outbox_unpublished`.
Gauges are refreshed every 15s by scheduled readers, because a gauge reads a
number — it does not observe events, so something has to look.

| Metric | Type | Question it answers |
|--------|------|---------------------|
| `ledgerflow.payments.outcome` | counter, tagged `status` | What is happening to payments? Success rate is a query over the tag, not a separate metric |
| `ledgerflow.payments.pending` | gauge | How many payments are stuck in an unresolved provider call right now? |
| `ledgerflow.psp.call` | timer, tagged `operation`, `outcome` | How slow is the provider, split by what we asked and what we got? |
| `ledgerflow.psp.attempts` | counter, tagged `operation`, `outcome` | How much retrying is happening — is the provider degrading before it fails? |
| `ledgerflow.outbox.unpublished` | gauge | Are events being written but not published? |
| `ledgerflow.outbox.oldest_unpublished_seconds` | gauge | How far behind is the relay? A count can look fine while one row is hours old |
| `ledgerflow.webhooks.pending` | gauge | Is delivery keeping up? |
| `ledgerflow.webhooks.dead` | gauge | How many merchants have stopped receiving events? |
| `ledgerflow.recon.open_mismatches` | gauge | How much unexplained money is sitting on the books? |

The pair of outbox gauges is deliberate. A backlog count of 40 is meaningless on
its own — it could be a busy second or a stuck relay. The age of the oldest
unpublished row is the one that distinguishes them.

### Tracing

One trace id spans the API call, the provider request, the outbox publish, the
Kafka consume and the webhook delivery — including deliveries that happen
minutes later on the eighth retry.

That does not happen by itself. Trace context lives in a thread-local, and every
one of those hops crosses either a thread boundary or a process boundary, where
a thread-local is simply gone. So the context is **written down as data** at
each handover:

- `outbox_event` has `trace_id` and `span_id` columns, filled from the MDC at
  append time — inside the request that caused the event.
- The relay restores them into the MDC before publishing, so the publish is
  logged under the original request's trace rather than the scheduler's.
- The Kafka message carries a W3C `traceparent` header
  (`00-<traceId>-<spanId>-01`), so any tracing system can read it, not just this
  one.
- The consumer parses that header back into the MDC, and stores `trace_id` on
  the `webhook_delivery` row.
- The dispatcher restores it again for each attempt, then clears it — the
  consumer and scheduler threads are pooled and reused, so a value left behind
  would contaminate the next message.

The log pattern prints `[ledgerflow,traceId,spanId]` on every line, so grepping
one id across the API log, the relay log and a retry three minutes later returns
the whole story.

### Health

`/actuator/health` has two groups with different jobs:

- **`readiness`** includes `db` and `ping` only. It answers "should this
  instance receive traffic?"
- **`business`** includes the `recon` indicator, with details always shown. It
  answers "are the books in order?"

The separation is the point. `ReconHealthIndicator` reports `WARN` — not `DOWN`
— when ten or more mismatches are open, or when no reconciliation has completed
in 36 hours. Both are real problems, and neither is a reason to take an instance
out of the load balancer or let Kubernetes restart it. A stale reconciliation
means somebody needs to look at the books; restarting the process would not fix
it and would drop live traffic. Because `readiness` names its indicators
explicitly, adding `recon` to `business` cannot leak into it by accident.

Of the two conditions, the stale run is the worse one, which is why it is
checked first: open mismatches are visibly wrong, while a reconciliation that
never ran looks exactly like a night on which nothing was wrong.

## Running it

Requirements: Java 25 and Docker.

```bash
./gradlew bootRun
```

Spring Boot's Docker Compose support reads `compose.yml`, starts Postgres and
Kafka, and wires both. Flyway applies the migrations on startup, and the
`payment-events` topic (3 partitions) is created by the application.

Create a merchant to work with:

```bash
docker compose exec postgres psql -U ledgerflow -d ledgerflow \
  -c "insert into merchant (id, name) values ('11111111-1111-1111-1111-111111111111', 'Test Merchant');"
```

### The mock provider

`mock-psp/` is a separate Spring Boot project at the repository root, with its
own Gradle build (it is not a subproject of `ledgerflow` — the root
`settings.gradle` does not include it). It stands in for the external company:
it holds authorizations, honours idempotency keys, publishes a daily
settlement file, and misbehaves on demand.

```bash
cd mock-psp
./gradlew bootRun          # listens on :9090
# or: docker compose up
```

LedgerFlow points at `http://localhost:9090` by default; override with
`PSP_BASE_URL`.

Two things to know before demoing:

- **It starts misbehaving on purpose**: 10% declines, 10% HTTP 500s and 10%
  swallowed responses, per its `application.yml`. Turn chaos off for a clean run.
- **Its settlement file lies on purpose** too. With error injection on (the
  default) it drops the second captured payment, understates the third by 10
  minor units, and appends a phantom line for a payment that never existed —
  one of each mismatch type, so reconciliation has something to find.

There is also a smaller in-repo mock, `src/test/.../psp/MockPsp.java`. It is
not a substitute for `mock-psp/` — the two exist for different jobs:

- **Use `mock-psp/`** for anything this README's demos describe: the timeout
  scenario, the settlement file, reconciliation finding its injected
  mismatches. Only `mock-psp/` publishes a settlement file, so it's the only
  one that can drive that half of the system.
- **Use in-repo `MockPsp`** when a test needs a real HTTP server without
  managing an external process. `PspServiceManualTest` starts one in-JVM on a
  random port per test class via `MockPsp.startOnRandomPort()`, with chaos off
  by default and no settlement endpoint at all. It can also be run standalone
  with `./gradlew mockPsp -Pport=9090` if you just want something on :9090 that
  speaks the authorization/capture protocol without the settlement machinery.

### Demo: a timeout, and the system healing itself

This is the scenario the `psp` module exists for. Make every response arrive too
late to be heard:

```bash
curl -X POST http://localhost:9090/admin/chaos \
  -H 'Content-Type: application/json' \
  -d '{"declineRate":0, "errorRate":0, "timeoutRate":1.0}'
```

The provider records the authorization *first*, then sleeps 8 seconds — well
past the adapter's 2s read timeout. It really did authorize; the caller just
never found out.

Create a payment and authorize it:

```bash
PAYMENT=$(curl -s -X POST http://localhost:8080/v1/payments \
  -H 'Content-Type: application/json' \
  -H 'X-Merchant-Id: 11111111-1111-1111-1111-111111111111' \
  -H 'Idempotency-Key: demo-001' \
  -d '{"amountMinor": 5000, "currency": "USD", "merchantRef": "demo"}' \
  | sed -n 's/.*"id":"\([^"]*\)".*/\1/p')

curl -i -X POST http://localhost:8080/v1/payments/$PAYMENT/authorize
```

The response is **`202 Accepted`** with `"status": "AUTHORIZATION_PENDING"` and
a `Retry-After` header — three attempts, all timed out, outcome `Unknown`. The
payment is not failed, because failing it would be a guess.

Now stop the chaos and wait for the verification job (it runs every 10s):

```bash
curl -X POST http://localhost:9090/admin/chaos \
  -H 'Content-Type: application/json' -d '{"timeoutRate":0}'

sleep 20 && curl -s http://localhost:8080/v1/payments/$PAYMENT
```

The payment is now `AUTHORIZED`, carrying the provider's real reference — the
one that existed the whole time. The attempt log tells the story in two rows:

```sql
select operation, attempts, outcome, latency_ms, detail
  from psp_attempt where payment_id = '<id>' order by created_at;

 operation | attempts |  outcome   | latency_ms |                detail
-----------+----------+------------+------------+--------------------------------------
 AUTHORIZE |        3 | UNKNOWN    |       6573 | Error while extracting response for…
 LOOKUP    |        1 | AUTHORIZED |         11 |
```

Three attempts that reached the provider and told us nothing, then one lookup
that asked what really happened. Two rows, and no money invented.

To see reconciliation find its three mismatches, capture a handful of payments,
then run it for today:

```bash
curl -X POST http://localhost:8080/admin/reconciliation/runs
curl http://localhost:8080/admin/reconciliation/mismatches
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

`authorize` and `capture` return `200 OK` when the payment reached a settled
state and `202 Accepted` with `Retry-After` when it is left pending — the
request was accepted, the answer is not in yet, poll the payment.

Sending the create request twice with the same `Idempotency-Key` returns the
same payment and creates nothing new. The same key with a different body returns
`409` with a distinct error type.

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
worth reading, because they document behaviour the production code does not
make obvious:

- `LedgerConcurrencyTest` — 100 simultaneous posts produce an exact balance
- `LedgerDatabaseConstraintsTest` — raw SQL bypasses the service to prove the
  database constraints fire on their own
- `PaymentIdempotencyConcurrencyTest` — 20 identical requests at once create
  exactly one payment
- `PaymentVerificationTest` — a timed-out authorization the provider *did* apply
  gets healed by the job; a capture that stays unknown never auto-fails
- `OutboxTest` — payment, ledger entries and outbox row commit together
- `KafkaPublishingTest` — events arrive on one partition, in order, keyed by
  payment id
- `WebhookDeliveryTest` — a 5xx is retried until it succeeds, a 4xx is
  dead-lettered after exactly one attempt, a redelivered event creates no second
  row
- `ReconciliationTest` — each mismatch type is produced deliberately; a recent
  capture missing from the statement is timing, an old one is a mismatch;
  matched lines settle, disputed ones do not; re-running a day does not
  double-post; a resolved mismatch cannot be resolved twice
- `SettlementParsingTest` — a malformed statement row is refused rather than
  guessed at
- `ModularityTests` — module boundaries are verified, and diagrams are generated
  into `build/spring-modulith-docs/`

## Design decisions

The reasoning behind the significant choices is in
[docs/decisions.md](docs/decisions.md), including the ones deliberately *not*
taken — microservices, Kubernetes, Debezium, Spring Modulith's event registry, a
topic per event type, automatic correction of mismatches, and an account
hierarchy.

## Known limitations

Deliberate, and listed here rather than hidden.

**Security and access**

- **No authentication anywhere.** The merchant comes from an `X-Merchant-Id`
  header; in a real system it would come from an authenticated API key. There is
  no Spring Security on the classpath.
- **`/admin/**` is wide open too**, which is worse: anyone who can reach the port
  can run reconciliation or resolve a mismatch. `resolvedBy` is a free-text
  field the caller fills in, so the audit trail records a claim, not an identity.
- **Endpoint secrets are stored in plaintext** in `webhook_endpoint.secret`, and
  cannot be rotated.

**Not built**

- **No refunds or cancellations.** `REFUNDED` and `CANCELED` are in the state
  machine and in the schema, but no endpoint or job transitions into them. The
  state machine is ahead of the API on purpose.
- **No merchant onboarding and no endpoint registration API.** Merchant rows and
  `webhook_endpoint` rows are inserted by hand.
- **Dead webhook deliveries have no requeue endpoint.** A `DEAD` row is the end
  of the line: no replay, no alert, no automatic disabling of an endpoint that
  fails permanently. The same is true of an `outbox_event` that keeps failing —
  `publish_attempts` and `last_error` are recorded, but nothing gives up or
  raises anything.
- **No Grafana dashboard, load test or Kubernetes manifests**, as noted in the
  status table.

**Simplified on purpose**

- **One hard-coded fee policy**: 2.9% + 30 minor units for every merchant,
  capped so the fee can never exceed the amount. No per-merchant pricing, no
  interchange, no currency-specific rates.
- **No provider fee is deducted at settlement.** The settlement batch debits
  `BANK` for the full captured total, as though the provider paid out gross. Real
  providers net their fees out of the payout, so a real integration would need a
  provider-fee expense account and a statement that reports it.
- **The 24-hour settlement lag is a hard-coded guess** about the provider's
  schedule, not a configured property, and not derived from the statement.
- **The `PSP_CLEARING` account key is defined twice** — in
  `payment/internal/LedgerAccounts` and again in `recon/internal/SettlementAccounts`
  — because neither module may depend on the other's internals. They are kept in
  step by a comment. If they ever drift, captures and settlements silently
  accumulate in two different accounts and the clearing account never drains.
  A shared account-key vocabulary in `ledger` would be the honest fix.
- **Idempotency records never expire.** They should be cleaned up after roughly
  24 hours; there is no job. Published `outbox_event` rows and `DELIVERED`
  `webhook_delivery` rows are likewise never archived — three tables that grow
  forever.
- **The settlement statement parser is naive**: `split(",")` with no quoting or
  escaping support. It refuses a short row rather than guessing, which is the
  important half, but a quoted comma would break it.
- **Reconciliation loads every captured payment into memory** on each run —
  `capturedAmounts()` is unbounded and returns the whole history, not one day of
  it. Fine at demo scale, wrong at any real scale, and the fix (filtering by
  date) needs capture timestamps in the ledger query.
- **The whole reconciliation run is one transaction**, and the HTTP call that
  fetches the statement happens inside it. That is exactly the long-held
  transaction that decision 15 argues against elsewhere in this codebase.

**Correctness gaps**

- **Ledger check-then-insert race.** Two threads posting the same reference at
  the same instant: the unique constraint keeps the books correct, but the loser
  gets a `DataIntegrityViolationException` instead of the existing transaction
  id. Fixing it properly means moving the retry outside the transaction
  boundary, into a second bean — a self-call would bypass Spring's proxy.
- **A lost optimistic-lock race returns 500.** Two concurrent captures cannot
  both succeed, which is correct, but the loser's
  `OptimisticLockingFailureException` has no handler and surfaces as a 500
  rather than a 409.
- **Mismatch deduplication is check-then-insert.** `recordMismatch` queries for
  an existing `OPEN` row before inserting, so two concurrent runs can both find
  nothing and both try to insert. The backstop is a partial unique index on
  `(reference, mismatch_type) where status = 'OPEN'` (V016), so the database
  refuses the duplicate — but the loser gets a raw
  `DataIntegrityViolationException` rather than a clean skip, which would abort
  the run. Nothing exercises this today, because no test runs two
  reconciliations at once.
- **`recon` reaches into other modules' tables.** It declares a dependency on
  `payment` but imports no Java type from it — instead it reads the `payment` and
  `psp_attempt` tables in raw SQL. Modulith verifies Java references and cannot
  see a `select`, so this boundary is real but unenforced. A query method on the
  payment module's public API would be the honest fix.

**Multi-instance caveats**

- **Event ordering holds for a single application instance.** `SKIP LOCKED` lets
  two relays run without colliding, but nothing stops instance A taking a
  payment's first event while instance B takes its second, and two producers have
  no shared ordering guarantee.
- **`PaymentVerificationJob` and `ReconciliationJob` take no lock.** Unlike the
  relay and the dispatcher, they select work without `FOR UPDATE SKIP LOCKED`, so
  two instances would both do it. For verification that is wasteful but harmless;
  for reconciliation, two simultaneous runs are protected only by the settlement
  batch's unique constraint.

## What I would add next

In the order I would actually do it:

1. **Refunds.** The state machine already permits `CAPTURED → REFUNDED`, and it
   is the first case that posts a *reversing* ledger transaction — the first real
   exercise of the append-only correction model, and the first event type added
   under the additive-only schema rule.
2. **3-D Secure before authorization.** A customer-authentication step ahead of
   the provider call, which introduces a genuinely new shape: a payment that is
   waiting on a *human* in a browser rather than on a network call, with its own
   pending status, its own expiry, and a redirect the merchant has to handle.
3. **File and SFTP settlement ingestion.** Real providers drop a file on SFTP
   overnight; they do not expose a CSV endpoint. `SettlementSource` is already
   the seam for it, so this is a second implementation plus the operational parts
   that come with files — partial downloads, re-sent files, a file that arrives
   twice with different contents.
4. **A Debezium-based outbox.** Replacing the polling relay with change data
   capture removes the poll interval and the relay itself. Doing it *after*
   hand-building the outbox is the point: the trade — a Kafka Connect deployment
   and a schema derived from table columns — is only visible once you have felt
   what the hand-built version costs.
