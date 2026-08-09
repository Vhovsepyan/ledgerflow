# Design decisions

Short records of the significant choices, including the alternatives that were
rejected and why. Written so that a reader — including future me — can tell the
difference between a decision and an accident.

---

## 1. Modular monolith, not microservices

**Decision:** one deployable application with modules enforced by Spring
Modulith. The only separate process is the mock payment provider, because it
stands in for an external company.

**Why:** the reason to split services is to let separate teams deploy
independently. This project has no teams. Splitting would add distributed
transactions, network failures between my own components, and harder local
development, in exchange for nothing.

**Rejected:** microservices. The seams are visible in the module boundaries;
if the ledger ever needed to be extracted, its public API is already the
interface it would expose.

**Cost:** the boundaries are enforced by a build-time check rather than by the
network, so they rely on the check staying green.

---

## 2. No Kubernetes on the main path

**Decision:** `docker compose up` and the application runs. Kubernetes manifests
may be added later as an optional folder.

**Why:** a project that needs a cluster to demonstrate itself does not get
demonstrated. Deployment is also not what a backend design is judged on.

**Instead:** the properties that matter for any orchestrator are implemented —
graceful shutdown, liveness and readiness probes, configuration from environment
variables, and a stateless application.

---

## 3. Money is a value type, stored as integer minor units

**Decision:** `record Money(long minorUnits, Currency currency)`.

**Why:** the important question is not `long` versus `BigDecimal` — it is
whether money is a *type* or a *number*. A bare amount travelling separately
from its currency allows adding dollars to euros, and that compiles in every
representation. Wrapping the pair makes the mistake impossible.

Given a wrapper, `long` is the better internal representation:

- Exact, with no scale round-tripping through the database
- `equals` behaves — `BigDecimal("2.0").equals(BigDecimal("2.00"))` is `false`,
  which makes tests lie
- Invalid states cannot be stored; half a cent has no representation
- Rounding happens once, deliberately, instead of at every division

**Rejected:** raw primitives (no protection against mixing currencies) and
`BigDecimal` as the internal type (equality traps, and division throws unless
every call names a `RoundingMode`).

**Fraction digits** come from `java.util.Currency`, so JPY (0 digits) and KWD
(3 digits) work without special cases.

`BigDecimal` survives in exactly one place — the fee calculation — where a
percentage is rounded once with an explicit `RoundingMode.HALF_UP` and converted
straight back to a `long`.

---

## 4. Entries carry a signed amount, not a direction column

**Decision:** positive is a debit, negative is a credit.

**Why:** the invariant becomes a single `sum(amount) = 0` instead of comparing
two aggregates. Simpler in SQL, simpler in Java, and it makes the database
trigger short enough to read.

**Consequence:** callers never handle signs. The builder exposes `debit(...)`
and `credit(...)`, both taking positive amounts, and owns the negation. Passing
a negative amount to either is rejected, because double negation would silently
flip the meaning.

---

## 5. The balance invariant lives in the type system, then in the database

**Decision:** a `LedgerTransactionRequest` cannot be constructed unbalanced —
`build()` refuses. Postgres also enforces `sum(amount) = 0` per currency with a
deferred constraint trigger.

**Why the type:** the service method that posts a transaction needs no runtime
balance check, because an unbalanced request cannot reach it. An invariant
enforced by construction cannot be forgotten by a new code path.

**Why the database too:** the application check protects the paths I remembered.
The database protects the ones I did not — migrations, `psql` sessions, future
processes, and bugs. In a ledger, those are exactly the situations where the
books go wrong silently.

**Cost:** a deferred constraint fires at `COMMIT`, so Spring wraps it as
`TransactionSystemException` and it cannot be caught in the method that caused
it. That is why the application check remains the primary one — it fails fast,
in the right place, with a message written for a human.

**Also enforced by the database:**

- Entries are immutable. `UPDATE` and `DELETE` raise an exception; corrections
  are posted as reversing transactions, as in real accounting.
- A composite foreign key on `(account_id, currency)` makes a cross-currency
  entry impossible without any trigger.

---

## 6. Accounts are per merchant per currency; the hierarchy is deferred

**Decision:** each merchant has their own payable account in each currency,
alongside system-wide clearing and revenue accounts. A `parent_id` column exists
but is unused.

**Why:** the question that decides this is *"how much do you owe me right
now?"*. With shared accounts, a merchant balance means scanning every entry ever
written and filtering. With per-merchant accounts it is one indexed account, and
later one snapshot row.

**Rejected:** a full account hierarchy. Roll-ups would need recursive queries,
posting to non-leaf accounts would have to be forbidden, and cycles prevented —
for reports that a single `account_type` column already answers with a plain
`GROUP BY`. The `parent_id` column costs one line and keeps the option open
without paying for it.

---

## 7. Posting to the ledger is idempotent, guarded by a content hash

**Decision:** posting the same reference twice returns the existing transaction
id. The same reference with *different* entries is rejected as a conflict,
detected by comparing a SHA-256 hash of the entries.

**Why idempotent:** a repeated post is normally not a bug. A provider call times
out and the capture is re-run; a Kafka consumer sees the same message twice.
Making the second call throw would force every caller to wrap it in a try/catch
and treat a normal retry as an error.

**Why the hash:** plain idempotency would silently discard real data if the same
reference arrived with different entries. The hash keeps the convenience while
still surfacing a genuine bug.

**Known gap:** this is check-then-insert, so two threads can both find nothing.
The unique constraint keeps the books correct; the loser gets a raw exception
rather than the existing id. See the payment idempotency approach below for how
this is solved properly where it matters more.

---

## 8. Payment state and ledger entries commit in one transaction

**Decision:** `PaymentService.capture()` is `@Transactional` and calls the
ledger directly. No events, no internal outbox.

**Why:** the outbox pattern solves the dual-write problem — changing a database
*and* telling another system when no transaction spans both. Payment and ledger
share one Postgres. `@Transactional` gives atomicity for free, guaranteed by the
database, with no moving parts. There is no dual-write problem here to solve.

**Rejected:** publishing an event and posting to the ledger in a separate
transaction. That would create a window where a payment reads `CAPTURED` while
the ledger has no entry for it — eventual consistency *inside* the money system,
requiring a repair job, alerting on stuck events, and a story for every read
that spans both. All of that to replace one transaction.

**The outbox is still used** — in phase 4, at the real boundary: publishing to
Kafka and delivering merchant webhooks, where no shared transaction exists.
Knowing when the pattern applies matters more than having applied it.

**Note:** authorization writes no ledger entries. It is a promise from the
provider, not a movement of money. Only capture posts.

---

## 9. Idempotency keys claim before doing the work

**Decision:** the key is inserted first, as an `IN_PROGRESS` claim, in its own
committed transaction (`REQUIRES_NEW`). Only then does the work start.

**Why:** check-then-work leaves a window. Two requests both look for the key,
both find nothing, and both proceed — and that window is widest exactly when it
hurts, because a client retries *because* the server was slow. Claiming first
makes the unique constraint on `(merchant_id, idempotency_key)` the lock, with
the winner decided by the database rather than by application code.

The claim must commit in a separate transaction. Inside the main transaction it
would stay invisible to the concurrent request until the end — which is the
window being closed.

**Implementation detail that matters:** the insert uses
`ON CONFLICT DO NOTHING` rather than catching a constraint violation. Once a
violation fires, JPA marks the transaction rollback-only and the persistence
context is unusable, so the follow-up read fails and the request returns 500.
Returning a row count instead of raising keeps the transaction usable.

**What is stored:** the key, a hash of the request, and the full response body
and status. Storing only the key would replay successes but re-run everything
after a validation error, and could not detect key reuse with a different body.

**Expiry:** `IN_PROGRESS` records are considered abandoned after 5 minutes.
Without a timeout, a crash between claiming and completing would poison that key
permanently and the merchant could never retry it.

---

## 10. A retry arriving mid-flight gets 409 with `Retry-After`

**Decision:** when a second request arrives while the first still holds the key,
return `409` with a `Retry-After` header and a distinct problem type.

**Why not wait and replay:** polling until the first request finishes couples
two requests' fates. Request B's latency becomes dependent on request A, and B
is already a retry — meaning the client timed out once. Adding waiting and
database polling at that moment builds a feedback loop: slowness causes retries,
retries cause waiting, waiting causes more slowness. The client already has
retry logic; `Retry-After` hands the waiting to the party that knows how to do
it without holding server resources.

**Why not 425 Too Early:** semantically closer, but it is specified for TLS
early data, and some proxies and HTTP clients treat unfamiliar 4xx codes as
permanent failures. A merchant's client might not retry at all.

**How the two 409 cases are told apart:** by the problem `type` and a
`retryable` field in the body, not by the status code —
`idempotency-key-in-use` (retryable) versus `idempotency-key-conflict` (not).

---

## 11. Payment state changes go through an explicit state machine

**Decision:** `PaymentStatus` holds the full transition table. The entity has no
`setStatus`; the only way to change state is `transitionTo`, which consults the
table and throws on an illegal move.

**Why:** every rule about what may follow what is readable in six lines instead
of scattered across `if` statements. An illegal transition is not caught by code
review or by a test — it cannot be written.

**Optimistic locking** (`@Version`) is used on payments because payment rows are
mutated. Two concurrent captures must not both succeed; the loser fails, which
is the correct outcome. The ledger needs no locking because it is append-only —
nothing reads a balance, modifies it, and writes it back.
