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
