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

**Decision:** the capture path is `@Transactional` and calls the ledger
directly. No events, no internal outbox between payment and ledger. One
transaction writes the payment row, the ledger entries, and the outbox row for
the outside world.

**Why:** the outbox pattern solves the dual-write problem — changing a database
*and* telling another system when no transaction spans both. Payment and ledger
share one Postgres. `@Transactional` gives atomicity for free, guaranteed by the
database, with no moving parts. There is no dual-write problem here to solve.

**Rejected:** publishing an event and posting to the ledger in a separate
transaction. That would create a window where a payment reads `CAPTURED` while
the ledger has no entry for it — eventual consistency *inside* the money system,
requiring a repair job, alerting on stuck events, and a story for every read
that spans both. All of that to replace one transaction.

**The outbox is used** — at the real boundary: publishing to Kafka and
delivering merchant webhooks, where no shared transaction exists. It writes to
the same Postgres in the same transaction as the capture, which is exactly why
it works there and would have been pointless here. Knowing when the pattern
applies matters more than having applied it. See decision 14.

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

**Cost:** the loser's `OptimisticLockingFailureException` has no handler, so it
comes back as a 500 rather than the 409 the sequential case gets. The outcome is
right, the status code is not.

---

## 12. The provider returns a sealed result, and Failed is not Unknown

**Decision:** `PspResult` is a sealed interface with five cases — `Authorized`,
`Captured`, `Declined`, `Failed`, `Unknown`. The rule that assigns them: if the
request definitely never reached the provider it is `Failed`; if it may have
been applied it is `Unknown`.

Concretely, the adapter treats a `ConnectException` as `Failed` (nothing was
sent), a read timeout as `Unknown` (it was on the wire), any answer the provider
actually gave — including a 500 — as `Unknown` after retries are exhausted, and
a call the circuit breaker refused as `Failed` (by definition nothing left the
process).

**Why sealed:** the compiler enumerates the cases. Adding a sixth outcome later
breaks every `switch` that handles them, in the right places, at compile time.
The alternative — a status enum plus a nullable reference plus a nullable reason
— makes "declined with no reason" and "authorized with no reference"
representable, and something has to check for them at runtime forever.

**Why the distinction matters more than the type:** it decides whether a payment
may be failed. Failing an `Unknown` authorization means telling a merchant their
customer was not charged while the provider is holding the customer's money.
Retrying an `Unknown` capture without an idempotency key means charging twice.
Both are the kind of bug that shows up in a support queue, not a stack trace.
`Failed` is safe to act on immediately; `Unknown` is only ever resolved by
asking the provider what really happened.

**Rejected:** collapsing the two into one failure case, which is what a plain
`try/catch` around an HTTP client gives you by default. That is the default
precisely because the difference is invisible from the client's side — which is
why it has to be encoded deliberately.

**Cost:** the reason strings inside `Failed` and `Unknown` are raw exception
messages. They are fine for `psp_attempt` rows and logs; they are not a
classification anyone should branch on, and they are never published in events.

---

## 13. Two pending statuses, not one status plus a column

**Decision:** `AUTHORIZATION_PENDING` and `CAPTURE_PENDING` are first-class
statuses in the state machine, in the enum, and in the database check
constraint.

**Rejected:** keeping `CREATED`/`AUTHORIZED` and adding a boolean like
`verification_pending`, or a single `PENDING` status plus a `pending_operation`
column.

**Why:** the transition table is the entire safety argument for the state
machine, and a flag beside it is not covered by that argument. With separate
statuses, "a capture that is in flight cannot be canceled" is one missing entry
in a table — `CAPTURE_PENDING` allows only `CAPTURED` and `FAILED`. With a flag,
it is an `if` that someone has to remember to write in every path, and the
type system has nothing to say about it.

A single `PENDING` has the same problem in a smaller way: the verification job
has to consult a second field to know which provider call to make, and every
query for "stuck captures" becomes a two-column predicate. Two statuses cost one
extra row in the transition table and read correctly in a status column during
an incident.

**Consequence:** the pending state is committed *before* the provider is called,
not after the answer comes back. A crash between the two leaves a row that says
"a call may be in flight", which the verification job picks up. If the status
were only written afterwards, a crash would leave a payment that looks untouched
while the provider holds an authorization for it.

**Cost:** eight statuses instead of six, and two of them exist only because
networks fail. Every consumer of the API has to understand that
`AUTHORIZATION_PENDING` is not a step in a happy path — it is the absence of an
answer, and it is why `authorize` can return `202`.

---

## 14. The outbox is hand-built, not Modulith's event registry and not Debezium

**Decision:** an `outbox_event` table, an `OutboxService.append` that is
`Propagation.MANDATORY`, and a `@Scheduled` relay that polls and publishes.
About 200 lines.

**Rejected: Spring Modulith's event publication registry.** It is the same
pattern, already on the classpath, and it would have been fewer lines. It is
built for *internal* application events — a `@TransactionalEventListener` in
another module — with the registry tracking incomplete publications so they can
be resubmitted. What is needed here is a durable log of messages for external
consumers, with an explicit wire format, an ordering key, and headers. Bending
the registry into that means the event payload is a Java type that Kafka now
depends on, and the retry semantics belong to the framework rather than to a
relay I can reason about. The library is a good fit for a problem one step to
the left of this one.

**Rejected: Debezium and change data capture.** Reading the WAL is the
production answer at scale: no polling, no relay to run, no double write. It was
rejected for three reasons. It adds Kafka Connect, a connector, and replication
slots to a project whose whole premise is `docker compose up`. It moves the
event schema from something I designed to something derived from table columns,
so a column rename becomes a consumer-visible break. And it publishes rows, not
events — `payment.captured` with a merchant net amount is not the same thing as
"a row in `payment` changed", and reconstructing the second into the first is
work moved to every consumer.

**Why this is not decision 8 contradicting itself:** decision 8 rejected an
outbox between payment and ledger. Both live in one Postgres, so one
`@Transactional` gives atomicity for free — there is no dual write, so the
pattern solves nothing and only adds a window of inconsistency inside the money
system. Here the two systems are Postgres and Kafka. No transaction spans them.
The dual write is real, and the outbox is the standard way to turn "write to the
database, then publish" into a single atomic act. The pattern did not become
correct — the boundary changed. That is the whole point of the two entries
sitting next to each other.

**Cost:** polling latency (one second by default), a table that grows until
something archives it, and a relay that has to be running for anything to be
published. All three are visible and none is silent, which is the trade being
made.

---

## 15. `SELECT … FOR UPDATE SKIP LOCKED`, a bounded batch, and a short send timeout

**Decision:** both the outbox relay and the webhook dispatcher claim work with
`for update skip locked`, take a fixed batch (50 events, 20 deliveries), and
publish with a 5-second send timeout.

**Why `SKIP LOCKED`:** it is a queue with no queue. Two relay instances polling
the same table would otherwise either collide on the same rows and one would
fail, or need a `claimed_by`/`claimed_at` column with a lease and a reaper for
crashed claimants. `SKIP LOCKED` makes the row lock itself the lease: the
database hands each worker a disjoint set, and a crash releases everything the
dead transaction held, immediately, with no expiry to tune.

**Why bounded:** the batch is held inside one transaction, and the rows it holds
are locked for its whole duration. `select … where published_at is null` with no
limit means one slow poll can lock ten thousand rows and block every other
worker for as long as it takes. A bounded batch caps the blast radius of a bad
poll; the next tick picks up where it left off one second later.

**Why the send timeout:** `KafkaTemplate.send(…).get()` with no timeout waits as
long as the producer's own configuration allows — and it waits *inside the
locked transaction*. A broker that accepts connections but never acknowledges
would hold locks and a database connection indefinitely. Five seconds bounds it,
the send throws, the row stays unpublished, and the relay retries on the next
tick. The producer is separately configured with `delivery.timeout.ms=5000`, so
the two agree.

**Why the batch stops at the first failure:** events for one aggregate must not
be reordered, and the relay has no cheap way to know whether the next event in
the batch belongs to the same payment as the one that just failed. Stopping is
the conservative choice.

**Cost:** stopping is conservative for *all* aggregates, so one permanently
failing event blocks everyone else's. Skipping per aggregate is the correct fix
and is not implemented. Bounded batches also mean throughput is capped at
batch ÷ poll interval, which is fine at this scale and would not be at a real
one.

---

## 16. One topic, keyed by payment id — not a topic per event type

**Decision:** every payment event goes to `payment-events`, with the payment id
as the message key.

**Why:** Kafka orders within a partition, and the key picks the partition. Key
by payment id and every event for one payment lands on one partition in the
order the relay published it, so a consumer can never see `payment.captured`
before `payment.authorized`. That ordering is the property consumers actually
need; it is worth more than any convenience a split would buy.

**Rejected: a topic per event type** (`payment.authorized`,
`payment.captured`, `payment.failed`). It reads tidier and lets a consumer
subscribe to only what it cares about. It also destroys ordering between the
events of one payment — three topics have no relative order at all — so every
consumer would need to reconstruct the sequence itself, from timestamps that
come from three different producers' clocks. It also makes adding a fourth event
type a topic-provisioning task rather than a code change. Filtering by
`event-type` is a header check; ordering is not recoverable once lost.

**Rejected: a topic per merchant.** Ordering would be preserved, but the topic
count grows with the customer list, and one large merchant would still be one
partition.

**Consequence:** consumers are told what kind of event they have via the
`event-type` header, not the topic, and the payload itself is untagged JSON.
`PaymentEventConsumer` reads the header, and only reaches into the body for
`merchantId`.

**Cost:** all consumers read all payment events, including the ones they ignore.
With three partitions, per-payment ordering also caps parallelism at three
consumers, and a merchant with a pathological volume can make one partition hot.
Both are the right problems to have at this size.

---

## 17. Events are fat: full state, not just an id

**Decision:** `payment.captured` carries the payment id, merchant id, merchant
reference, amount, fee, net, currency, provider reference and timestamp — enough
to act on without reading anything else.

**Rejected: the thin event / "event-carried notification" style,** where the
message is `{paymentId}` and the consumer calls back for the details. That looks
cleaner and keeps the schema tiny, and it has a specific failure mode: the
callback reads *current* state, not the state at the time of the event. A
consumer processing a delayed `payment.captured` for a payment that has since
been refunded reads `REFUNDED` and reports the wrong thing. It also turns every
event into a synchronous dependency on the payment API being up and fast, which
is precisely the coupling the event was supposed to remove — and puts a
read-amplification factor equal to the consumer count on the API.

For webhooks it would also be unusable: a merchant's server would have to hold
an API credential and make a call back into a system it does not run, on every
notification.

**Why it is safe here:** payment events describe facts that already happened and
never change afterwards. A fat event that is a week old is still *true*; it is
just old. Fat events are dangerous when they carry mutable state, which these do
not.

**Cost:** the payload is the public contract, so every field in it is something
that can never be removed — see the additive-only rule in `PaymentEvents`.
Larger messages, and any field added for one consumer is visible to all of them.
That is why nothing operational goes in: retry counts, provider error strings
and verification attempts stay in `psp_attempt` and the logs, where changing
them breaks nobody.

---

## 18. The consumer only records deliveries; a separate job owns the retries

**Decision:** `PaymentEventConsumer` does one thing — insert a
`webhook_delivery` row per active endpoint — and returns. It never makes an HTTP
call. `WebhookDispatcher`, a separate scheduled job, owns every attempt, the
backoff, and the dead-lettering.

**Why:** retrying inside the consumer means the retry state lives in the Kafka
offset. A merchant whose server takes 30 seconds to answer stalls the partition
for every other merchant on it; long enough and the broker decides the consumer
is dead and rebalances the group, at which point the work is redone from the
last committed offset — and a redelivery that was already sent gets sent again.
The failure of one merchant's endpoint becomes an incident for the consumer
group. Draining Kafka as fast as rows can be inserted, and letting a job with a
database-backed schedule do the waiting, keeps a slow merchant's problem inside
that merchant's rows.

It also makes the retry state *queryable*. "Which deliveries are stuck, for
whom, since when, with what error" is a `select`, not a question about consumer
lag.

**Rejected:** Kafka-native retry topics and a DLQ topic. That is a good design
when the retry is short and uniform. Webhook retries here run to hours across
eight attempts, per endpoint, with a schedule that has to survive a restart —
which is a database's job, not a log's.

**How duplicates are handled:** delivery is at-least-once, so the same event can
arrive twice. `webhook_delivery` has a unique constraint on
`(endpoint_id, event_id)` and the consumer catches the violation and moves on.
The database decides, not a "have I seen this?" lookup that would have its own
race.

**Cost:** two moving parts instead of one, and a poll interval of latency
between the event landing and the first delivery attempt. The consumer also
silently drops events for merchants with no registered endpoint, which is
correct but leaves no trace.

---

## 19. The signature covers `timestamp.payload`, not the payload alone

**Decision:** `X-Ledgerflow-Signature: v1=<hex>`, an HMAC-SHA256 over the string
`"<unix seconds>.<raw body>"`, with the same timestamp sent in
`X-Ledgerflow-Timestamp`.

**Why not the payload alone:** a signature over the body proves the body is
authentic, forever. Anyone who captures one delivery — a proxy, a log, a
misconfigured load balancer — can replay it verbatim at any point in the future,
and it will still verify, because nothing in the signed material says when it
was legitimate. Signing the timestamp with it lets the receiver enforce a
freshness window: reject anything older than a few minutes, and a captured
delivery is worthless outside that window. The timestamp has to be *inside* the
HMAC — sending it in a header the signature does not cover means an attacker
just rewrites it.

**Why the `.` separator:** it makes the signed string unambiguous. Concatenating
a numeric timestamp directly onto the payload creates a boundary an attacker can
shift — different (timestamp, payload) pairs producing identical signed bytes.
This is the Stripe scheme, followed deliberately rather than invented, because
merchants have already written verification code for it.

**Why the `v1=` prefix:** it makes the algorithm part of the wire format. Moving
from HMAC-SHA256 to something else later is a new prefix that receivers can
accept alongside the old one, instead of a flag day.

**Cost:** the receiver has to reconstruct the signed string exactly, which means
signing the *raw* body bytes — a receiver that re-serializes parsed JSON before
verifying will get a mismatch. That is inherent to payload signing, and it is
why the delivery stores the exact payload string it will send. The freshness
check is also the receiver's job; nothing here can enforce it. And the secret is
stored in plaintext in `webhook_endpoint.secret`, which is a real gap, not a
design choice — see the README's limitations.

---

## 20. A 4xx from a merchant is permanent; a 5xx is retryable

**Decision:** `2xx` marks the delivery `DELIVERED`. Any `4xx` except `429` marks
it `DEAD` immediately, after one attempt. `429`, every `5xx`, a timeout and a
connection error are retried with exponential backoff, up to eight attempts.

**Why:** the two classes are different statements. A `4xx` means the endpoint
received the request, understood it, and rejected it — a bad signature, a route
that no longer exists, a payload it will not accept. Nothing about that changes
by sending the identical bytes again in five seconds; retrying is just eight
copies of the same rejection, and it delays the moment anyone notices the
integration is broken. A `5xx`, a timeout or a refused connection says nothing
about the request at all — the endpoint is deployed, restarted, or overloaded.
That is exactly what backoff is for.

`429` sits on the 4xx side of the wire and the 5xx side of the meaning: it is
the endpoint explicitly asking for the request *later*. Treating it as permanent
would punish the merchants doing rate limiting properly.

**Rejected: retrying everything.** Simpler, and the usual default. It converts
every misconfiguration into eight attempts of load against a server that has
already said no, and it hides the difference between "your endpoint is down" and
"your endpoint is wrong" — which are different support tickets.

**Rejected: giving up on 5xx after one or two attempts.** A deploy that takes 90
seconds would drop live notifications for every merchant deploying at the time.
The backoff runs 5s → 10s → … → 320s capped at 10 minutes, which spans an
ordinary restart comfortably.

**Cost:** the classification is only as good as the merchant's status codes, and
plenty of servers answer 400 for a temporary condition or 200 for a failure they
have not noticed. There is also no `Retry-After` handling on a `429` — the fixed
backoff is used regardless of what the endpoint asked for. And a `DEAD` delivery
is currently the end of the line: nothing alerts, nothing replays, and nothing
disables an endpoint that has failed permanently for every event. That is a gap,
listed in the README, not a decision.
