# 12. The draw runs as a background job, with automatic retries switched off

- **Status:** accepted
- **Phase:** 5

## Context

Executing a draw means ranking four thousand candidates, filling five pools, applying horizontal
reservations, and writing six hundred allotments, four thousand rankings and several thousand
waitlist entries. It is not enormous, but it is not a request-response operation either: it should
not depend on a load balancer's patience, and a process that dies halfway through should leave a
durable record of unfinished work rather than a question.

JobRunr persists jobs in the same database as the data they operate on, so a job and its subject
survive a restart together. That is the reason to use it here.

The harder question is what should happen when a draw fails.

## Decision

**Execution is a JobRunr job.** `POST /execute` moves the draw to `RUNNING` under a row lock and
returns `202`. The row lock is what makes it exactly-once: two simultaneous requests both reach the
status check, and without it both read `REVEALED` and both enqueue. With it the second waits, reads
`RUNNING`, and is refused.

**Everything is written in one transaction** — allotments, rankings, waitlists, pool summaries and
the draw's own status. A draw that had written half its allotments and then failed is the worst
state this system could reach: a partial public lottery that can be neither completed nor undone.

**Automatic retries are switched off.** JobRunr would retry ten times by default. That is right for a
bank statement sync and wrong here, because the allocation is *deterministic*: the same register,
rule version and seed produce the same result every time. A failure is therefore either transient —
a database or infrastructure fault somebody needs to see — or a defect, which retrying reproduces.
Neither is improved by nine more silent attempts against a public lottery at three in the morning.

Instead the failure and its reason are recorded on the draw in a separate transaction (the failing
one is about to roll back and would take the record with it), the draw becomes `FAILED`, and a
person decides. `POST /execute` accepts a failed draw, so retrying is one deliberate call.

**JobRunr keeps its tables in its own schema.** ADR-0001 says Flyway owns the schema; a library that
versions and migrates its own storage is not an exception to that so much as outside it, exactly as
`flyway_schema_history` is. Putting them in a `jobrunr` schema makes the boundary visible instead of
leaving six foreign tables in `public`.

**A published draw is immutable, enforced by trigger.** Allotments refuse `UPDATE` and `DELETE`
outright; a published draw refuses any change at all. A mistaken result is superseded by a new draw,
never edited — the same position as the audit log, for the same reason.

## Consequences

**Gained.** A crash mid-draw leaves the register untouched and a job that can be retried. Two
operators pressing the button at once produce one draw. A failure surfaces to a person with a reason
attached rather than being retried into eventual success or eventual silence.

**Given up.** A transient database blip now needs a human to press execute again, where an automatic
retry would have recovered unattended. For an operation that runs a handful of times in a scheme's
life and decides who gets housed, that is the right way round; for a job that ran every minute it
would not be.

**Operationally.** `execute` returning `202` means callers must poll `GET /draws/{id}`. JobRunr's
minimum poll interval is five seconds, so a draw begins within a few seconds of being queued rather
than instantly. The dashboard on port 8000 shows job state directly, which is where an operator
should look when a draw has not started.
