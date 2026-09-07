# 2. The audit log is append-only and hash-chained

- **Status:** accepted
- **Phase:** 0

## Context

The allocation will be challenged. "We have an audit log" is worth very little on its own,
because the obvious follow-up question is whether the log could have been edited after the fact
to match the outcome being defended. An ordinary log table with `UPDATE` rights answers that
question badly.

There is also a sequencing problem. An audit log added in the final phase of a project records
only the final phase. To be evidence about how a decision was reached, it has to exist before the
first decision is taken — which is why the table is created in phase 0, well before anything
writes to it.

## Decision

`audit_event` is append-only and hash-chained. Each row carries `prev_hash`, the hash of its
predecessor, and its own `hash` over its canonical contents; the genesis row's `prev_hash` is 64
zeroes. Altering or removing any row invalidates every hash after it.

`UPDATE`, `DELETE` and `TRUNCATE` are refused by a database trigger. Corrections are recorded as
new compensating events. History is never edited.

Hashes are computed in application code, not in a trigger. The chain has to be recomputable by a
third party in any language from the published fields; computing it inside PostgreSQL would make
the chain a property of our database rather than of the published data.

## Consequences

**Gained.** Tampering does not become impossible — anyone with DDL rights can drop a trigger —
but it becomes *detectable*, and it stops being possible by accident. The chain travels with the
published data, so verification does not require access to our systems.

**Given up.** Writes must be serialised to compute `prev_hash`, which makes the audit log a
single ordered sequence rather than something that can be written concurrently at will. At this
system's write volume that costs nothing. At a much higher volume it would need revisiting, and
that is a decision for a different system.

Phase 8 adds the second layer of defence: revoking `UPDATE` and `DELETE` from the application's
database role, so the application could not mutate history even if the trigger were removed.
