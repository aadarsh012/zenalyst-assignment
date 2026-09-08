# Architecture decision records

Decisions are recorded here as they are taken, not reconstructed at the end of the project.
Each record states the context, the decision, and — the part that actually matters later — what
was given up by deciding that way.

An ADR is immutable once merged. A decision that is later reversed gets a new record that
supersedes the old one; the old one stays, because the reasoning that was correct at the time is
evidence about how the system came to be shaped this way.

| ADR | Decision | Phase |
|---|---|---|
| [0001](0001-flyway-owns-the-schema.md) | Flyway owns the schema; Hibernate only validates | 0 |
| [0002](0002-audit-log-is-append-only.md) | The audit log is append-only and hash-chained | 0 |
| [0003](0003-identity-numbers-are-never-stored.md) | National identity numbers are never stored, only a scheme-scoped token | 1 |
| [0004](0004-idempotency-and-the-cost-of-exactly-once.md) | The idempotency record commits with the work it describes | 1 |
| [0005](0005-submission-date-is-not-the-data-entry-date.md) | The submission date is not the data-entry date | 1 |
| [0006](0006-fuzzy-matches-are-never-merged-automatically.md) | Fuzzy matches are never merged automatically | 2 |
| [0007](0007-duplicate-links-are-derived-not-accumulated.md) | Duplicate links are rebuilt on every run, not accumulated | 2 |
| [0008](0008-unverified-claims-do-not-disqualify.md) | An unverified claim costs the benefit, never the place | 3 |
| [0009](0009-freeze-before-the-draw.md) | Freeze the register, and publish its root, before any seed exists | 3 |
| [0010](0010-open-seats-first-and-horizontal-carve-outs.md) | Fill open seats first; carve horizontal reservations out of each pool | 4 |
| [0011](0011-commit-reveal-and-why-a-beacon-is-stronger.md) | Commit to the seed before revealing it — and why a beacon is stronger | 5 |
| [0012](0012-the-draw-runs-as-a-job-with-retries-off.md) | The draw runs as a background job, with automatic retries off | 5 |
| [0013](0013-explain-is-assembled-not-reconstructed.md) | The explanation is assembled from stored facts, never reconstructed | 6 |
