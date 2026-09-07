# 1. Flyway owns the schema; Hibernate only validates

- **Status:** accepted
- **Phase:** 0

## Context

Spring Boot makes it trivially easy to let Hibernate manage the schema with
`ddl-auto: update`. For most projects the cost of that convenience is a slow accumulation of
drift: the schema in production is whatever sequence of entity edits happened to be deployed, and
nobody can say precisely when a column appeared or why.

This project is different in one specific way. Its central claim is that a published allocation
can be re-derived from published inputs. If challenged, we have to be able to state what the
database looked like at the moment of the draw, and show that it has not changed since.

## Decision

Flyway owns the schema. Every structural change is a numbered, immutable migration under
`src/main/resources/db/migration`. Hibernate runs with `ddl-auto: validate` and is never
permitted to create or alter a table; a mismatch between entities and schema fails application
startup rather than being silently repaired.

Applied migrations are never edited. One migration per phase keeps the history readable as a
changelog.

Demo and fixture data stay out of migrations (see `scripts/dev-seed.sql`), so the migration
history is purely structural.

## Consequences

**Gained.** The schema has a history that can be read, replayed and cited. `validate` turns
entity/schema drift into a startup failure — the loudest, earliest possible signal — instead of
a runtime surprise on an unusual query path.

**Given up.** Adding a field is now two edits rather than one, and a forgotten migration breaks
the build. This is the intended trade: the friction is small, and it falls at development time
rather than at the point where someone is asking a court to accept our numbers.
