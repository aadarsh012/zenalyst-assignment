# 4. Idempotency: the record commits with the work

- **Status:** accepted
- **Phase:** 1

## Context

The brief says a fair number of people applied twice because they were not sure the first one
went through. That sentence describes two different problems wearing the same clothes.

One is a person who genuinely filled in the form twice, on two days, perhaps with a slightly
different spelling. That is a real duplicate application. It must be detected, linked and
resolved visibly — never silently discarded — and that is deduplication's job.

The other is a single act of applying that produced two HTTP requests: a retry after a timeout, a
double-clicked button, a mobile connection that dropped after the server had already committed.
Turning that into two applications would mean this system manufacturing the very duplicates it
exists to resolve.

## Decision

Clients may supply an `Idempotency-Key` header. The key is the primary key of
`idempotency_record`, and **the record is written inside the same transaction as the work it
describes**. The application row, the audit event recording its acceptance, and the proof that
this key was consumed all commit together or not at all.

A repeat of the same key with the same body replays the stored response byte for byte, with
`Idempotent-Replay: true`. A repeat with a *different* body is refused with `409` rather than
answered: replaying the first response there would tell the caller their second, different
application had succeeded, when it was never attempted.

The stored response is `TEXT`, not `JSONB`. `jsonb` reorders keys and discards whitespace, so a
replay would return a subtly different document from the original — which is precisely the
guarantee the header exists to make.

The key is optional. Requiring it would reject applications from anyone whose client does not
send one, which is not a trade a public intake endpoint can make.

## Consequences

**Gained.** There is no window in which the work is durable but the record of it is not, and no
"in progress" marker to reap if the process dies mid-request. Recovery needs no reconciliation
job, because there is no intermediate state to reconcile.

**Given up — this is what exactly-once costs here.** Two genuinely concurrent requests carrying
the same key both do the work; the loser's transaction rolls back on the primary key collision
before committing anything, and it receives a `409` telling it to retry. Effort is duplicated,
effects are not.

The alternative — reserving the key in a separate committed transaction before starting — avoids
the duplicated effort but introduces exactly the crashed-process state this design does not have,
and something must then decide how long to wait before declaring a key abandoned. For an intake
endpoint at this volume, wasted effort is much the cheaper failure. At a volume where the work
were expensive, the trade would reverse.

**Not covered.** Without a key, a resubmission does create a second application, and correctly
so: with nothing to correlate the two requests, the system cannot distinguish a retry from a
person applying twice. Deduplication resolves it afterwards — and resolves it visibly, which is
the property that matters.
