# Housing Allocation Backend

A backend for a public housing scheme: roughly **4,000 applications for 600 flats**, allocated by
a published rule set — a draw within categories, with reserved quotas and a preference for
existing residents of the area.

Applications arrive through two channels (online, and on paper that is typed in later), a
significant number of people apply more than once because they were unsure the first one went
through, and **the final list will be challenged** — by an applicant, by a newspaper, and quite
possibly in court.

That last sentence is the design constraint. The hard problem here is not allocating 600 flats;
at this scale that is a sort. The hard problem is that every published output must be
**independently re-derivable from published inputs by someone who does not trust us**, and every
applicant must be able to get a complete, specific answer to "why not me?".

A result that is correct but unexplainable is a failed result.

> This README documents **what has been built**, not what is planned. It grows as the system
> does, so at any point it is an accurate description of the code in the repository.

---

## What is built

A running Spring Boot service backed by PostgreSQL, with the schema baseline and the audit
substrate in place:

- **Scheme registry** — the root record for a housing scheme: its flats, its application window,
  its status. Readable over HTTP.
- **Append-only audit log** — a hash-chained `audit_event` table that refuses `UPDATE`, `DELETE`
  and `TRUNCATE` at the database level.
- **RFC 9457 error model** — every error is an `application/problem+json` document with a stable
  `type` URI; no whitelabel pages, no stack traces on public endpoints.
- **Flyway-owned schema** — Hibernate validates against it and is never allowed to change it.
- **Test harness** — architecture tests that need no Docker, and integration tests against a real
  PostgreSQL 16 via Testcontainers.

Not yet built: application intake, deduplication, eligibility, the rules engine, the allocator,
the draw itself, and the transparency endpoints.

---

## Quickstart

**Prerequisites:** Java 17, Docker (or Colima), and `make`. Maven is not required — the project
ships a wrapper.

```bash
make up       # start PostgreSQL 16
make seed     # apply migrations, then load the demo scheme
make run      # start the application on :8080
```

Migrations can also be applied and inspected without starting the application:

```bash
make migrate        # apply pending migrations
make migrate-info   # show what has been applied
make schema         # print the current tables
```

`make help` lists every target.

### Running with Colima

The `Makefile` detects Colima automatically and exports `DOCKER_HOST` and
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` on your behalf. With Docker Desktop nothing is set and
the defaults apply. If you have both installed, note that they compete over
`~/.docker/config.json`; setting `DOCKER_HOST` explicitly, as the `Makefile` does, sidesteps the
ambiguity entirely.

---

## API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/schemes` | List all schemes |
| `GET` | `/api/v1/schemes/{code}` | One scheme by its code |
| `GET` | `/actuator/health` | Liveness, including database connectivity |

```bash
curl -s localhost:8080/api/v1/schemes
curl -s localhost:8080/api/v1/schemes/MHS-2026
curl -si localhost:8080/api/v1/schemes/NOPE     # 404 as application/problem+json
```

Errors follow RFC 9457. The `type` URI is a stable contract that integrators may branch on;
titles and detail messages may be reworded freely.

```json
{
  "type": "https://zenalyst.example/problems/not-found",
  "title": "Resource not found",
  "status": 404,
  "detail": "scheme 'NOPE' does not exist",
  "instance": "/api/v1/schemes/NOPE",
  "timestamp": "2026-09-07T15:52:02.766411Z",
  "resource": "scheme",
  "identifier": "NOPE"
}
```

Unhandled exceptions return an opaque body plus a `correlationId` that ties the response to a
server log entry. This API is public by design, so a stack trace on an error path would be an
information leak.

---

## Schema

Two tables, both created by `V1__baseline.sql`.

**`scheme`** — a housing scheme: flats, application window, status.

`applications_close_at` is a *submission* deadline, not a data-entry deadline. Paper forms are
typed in after the window closes, and treating the typing date as the submission date would
silently disqualify people who applied on time. That is precisely the class of defect this system
exists to make impossible, so the distinction is in the schema from the start.

**`audit_event`** — an append-only, hash-chained record of every decision-affecting act.

Each row carries `prev_hash`, the hash of its predecessor, and its own `hash` over its canonical
contents; the genesis row's `prev_hash` is 64 zeroes. Altering or removing any row invalidates
every hash after it. `UPDATE`, `DELETE` and `TRUNCATE` are refused by a database trigger, and
corrections are recorded as new compensating events rather than by editing history.

Hashes are computed in application code, not in a trigger. The chain has to be recomputable by a
third party in any language from the published fields; computing it inside PostgreSQL would make
the chain a property of our database rather than of the published data.

The table exists now, before anything writes to it. An audit log added at the end of a project
records only the end of the project.

### Verifying the append-only claim by hand

`BaselineSchemaIT` asserts this against a real PostgreSQL, because "append-only" is exactly the
sort of property that gets stated in a README, believed by everyone, and is quietly untrue two
years later because a migration dropped the trigger. You can confirm it yourself:

```bash
make psql
```
```sql
INSERT INTO audit_event (occurred_at, actor, action, subject_type, subject_id, prev_hash, hash)
VALUES (now(), 'you', 'MANUAL_TEST', 'scheme', 'MHS-2026', repeat('0',64), repeat('1',64));

UPDATE audit_event SET actor = 'tampered';   -- ERROR: audit_event is append-only
DELETE FROM audit_event;                     -- ERROR: audit_event is append-only
TRUNCATE audit_event;                        -- ERROR: audit_event is append-only
```

---

## Code layout

```
com.zenalyst.housing
├── scheme/          the scheme record: entity, repository, read API
└── platform/error/  RFC 9457 problem types and the global exception handler
```

Flyway owns the schema; Hibernate runs with `ddl-auto: validate` and never creates or alters a
table. A mismatch between entities and schema fails application startup rather than being
silently repaired. A schema that has drifted from its migration history cannot be audited, and
auditability is this system's entire value proposition.

Fixture data is kept out of migrations (see `scripts/dev-seed.sql`) so the migration history
reads as a pure structural changelog.

---

## Testing

```bash
make test     # architecture tests — fast, no Docker required
make verify   # everything, including Testcontainers integration tests
```

| Suite | Count | Covers |
|---|---|---|
| `ArchitectureTest` | 5 | package boundaries and allocator purity guardrails |
| `BaselineSchemaIT` | 6 | migrations applied, entity/schema agreement, append-only enforcement |
| `SchemeApiIT` | 3 | HTTP layer, error model, health |

Integration tests share a single PostgreSQL container started once per JVM, configured with
`--locale=C` to match `docker-compose.yml`. Text ordering under a locale-sensitive collation
varies by host, and a system whose defence is "you can reproduce our results" cannot have its
ordering depend on where it runs.

### A note on the architecture tests

`ArchitectureTest` forbids the `com.zenalyst.housing.allocation` package from referencing Spring,
JPA, the system clock, or any source of randomness. **That package does not exist yet**, so those
four rules currently pass vacuously; they are guardrails placed ahead of the code they guard.

They are there because the defensibility of the finished system will rest on the allocator being
a pure function of *(frozen registry, rule version, seed)* — the same inputs producing the same
output on any machine, forever. A single stray `Instant.now()` would quietly falsify that while
every other test kept passing. Writing the rule before the code means it can never be added
later, once violations already exist and it is inconvenient.

The fifth rule — no cyclic dependencies between packages — is live today.

---

## Architecture decisions

Longer-form rationale lives in [`adr/`](adr/), written as decisions are taken rather than
reconstructed afterwards. Each record states the context, the decision, and what was given up.

| ADR | Decision |
|---|---|
| [0001](adr/0001-flyway-owns-the-schema.md) | Flyway owns the schema; Hibernate only validates |
| [0002](adr/0002-audit-log-is-append-only.md) | The audit log is append-only and hash-chained |

---

## Repository conventions

- One branch per unit of work, named for the feature it builds (`skeleton`, `intake`,
  `deduplication`, …), merged with `--no-ff` so the boundaries stay visible in the history.
- One Flyway migration per unit of work. Applied migrations are never edited.
- This README describes only what exists. It is updated as part of the work that changes it,
  never reconstructed at the end.
- `docs/` holds the original assignment material and is intentionally not committed.
