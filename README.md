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

A running Spring Boot service backed by PostgreSQL that accepts applications through both
channels and records every acceptance in a tamper-evident log.

- **Application intake, online and on paper.** Online submissions are accepted over JSON; paper
  forms typed up afterwards are imported as CSV, with a per-row report.
- **Normalisation.** Names, phone numbers, emails and dates of birth are reduced to canonical
  forms so that deduplication can compare them. National identity numbers are checked against
  their Verhoeff check digit at the door.
- **Identity numbers are never stored** — only a scheme-scoped HMAC token and the last four
  digits ([ADR-0003](adr/0003-identity-numbers-are-never-stored.md)).
- **Idempotency.** A retried or double-clicked submission returns the original response instead
  of creating a second application
  ([ADR-0004](adr/0004-idempotency-and-the-cost-of-exactly-once.md)).
- **Submission date separated from data-entry date**, so a paper form handed in before the
  deadline counts even when typed up weeks later
  ([ADR-0005](adr/0005-submission-date-is-not-the-data-entry-date.md)).
- **Append-only audit log** — a hash-chained `audit_event` table that refuses `UPDATE`, `DELETE`
  and `TRUNCATE` at the database level, now actually being written to.
- **Scheme registry** — the root record for a scheme: flats, application window, status.
- **RFC 9457 error model** — every error is an `application/problem+json` document with a stable
  `type` URI; no whitelabel pages, no stack traces on public endpoints.
- **Flyway-owned schema** — Hibernate validates against it and is never allowed to change it.

Not yet built: deduplication, eligibility, the registry freeze, the rules engine, the allocator,
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

### Container runtime

Both `docker compose` and Testcontainers follow `DOCKER_HOST`, and the `Makefile` works out what
to set:

- **Docker Desktop** — nothing is set; the docker CLI finds Desktop's socket on its own. The
  `Makefile` does add `~/.docker/bin` to `PATH`, because Desktop's `config.json` names
  `credsStore: desktop` and the helper binary lives there. Without it, a docker CLI installed
  from Homebrew cannot find the helper and *every* image pull fails — including public images
  that need no credentials at all.
- **Colima** — `DOCKER_HOST` and `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` are set explicitly,
  because Colima's socket is somewhere the default lookup misses.
- **An explicit `DOCKER_HOST` from you** always wins over both.

**Do not leave both running at once.** Colima forwards published ports through an SSH tunnel that
keeps listening after you switch, so `localhost:5432` can reach Colima's Postgres while
`docker compose exec` reaches Desktop's. Migrations then apply to one database while the
application talks to the other, and the symptom is a schema that Flyway insists is up to date but
that has no tables in it. Run `colima stop` before switching.

---

## API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/schemes` | List all schemes |
| `GET` | `/api/v1/schemes/{code}` | One scheme by its code |
| `POST` | `/api/v1/schemes/{code}/applications` | Submit an application online |
| `POST` | `/api/v1/schemes/{code}/applications:import` | Import a CSV of paper applications |
| `GET` | `/api/v1/applications/{applicationNo}` | Retrieve one application |
| `GET` | `/actuator/health` | Liveness, including database connectivity |

### Submitting online

```bash
curl -s -X POST localhost:8080/api/v1/schemes/MHS-2026/applications \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: my-key-1' \
  -d '{
    "fullName": "Ramesh Kumar",
    "dateOfBirth": "1990-02-01",
    "governmentId": "234567890124",
    "phone": "+91 98765 43210",
    "email": "ramesh@example.com",
    "addressLine": "12 Nehru Road, Ward 7",
    "wardCode": "W-07",
    "category": "OBC",
    "gender": "MALE",
    "localResident": "true",
    "disability": "false",
    "exServiceperson": "false",
    "annualIncome": "250000"
  }'
```

Repeat that command verbatim: the response is identical and no second application is created.
The `Idempotent-Replay` response header says which of the two happened. Change the body while
reusing the key and the request is refused with `409` rather than answered with the earlier
response.

`governmentId` must carry a valid Verhoeff check digit. `234567890124` is valid;
`234567890125` is the same number with one digit mistyped and is rejected.

### Importing paper applications

```bash
curl -s -X POST 'localhost:8080/api/v1/schemes/MHS-2026/applications:import?enteredBy=clerk-3' \
  -F 'file=@scripts/sample-paper-applications.csv'
```

The sample file contains five good rows and one deliberately broken one. The response is `200`
with a per-row report — partial success is the normal case for a batch of hand-written forms, not
an error. Re-run the same command: the rows that already landed come back as
`ALREADY_IMPORTED` rather than being imported twice.

### Reading an application back

```bash
curl -s localhost:8080/api/v1/applications/MHS-2026-000001
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

**`application`** — one submitted application, from either channel.

Applications are never modified and never deleted. A duplicate is not removed, it is linked; an
ineligible application is not discarded, it is marked with its reasons. Four thousand people
applied and the published result must account for all four thousand, so a row that has quietly
vanished cannot be accounted for. The entity has no setters.

The submission is kept verbatim in `raw_payload` alongside every normalised field, so that
whatever the applicant actually wrote remains available if our parsing of it is ever disputed.
The one exception is the identity number, which is redacted — see
[ADR-0003](adr/0003-identity-numbers-are-never-stored.md).

**`idempotency_record`** — the replay ledger. Written in the same transaction as the work it
describes, so the two commit together.

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

105 tests: 75 unit and architecture tests that need no Docker, and 30 integration tests against
a real PostgreSQL.

| Suite | Count | Covers |
|---|---|---|
| `NormalisationTest` | 56 | names, phones, emails, dates, Verhoeff check digits |
| `CanonicalJsonTest` | 8 | deterministic rendering: key order, numbers, escaping, code-point sorting |
| `AuditHashTest` | 6 | hash determinism and what it depends on |
| `ArchitectureTest` | 5 | package boundaries and allocator purity guardrails |
| `OnlineIntakeIT` | 7 | acceptance, retrieval, accumulated validation errors, identity redaction |
| `PaperImportIT` | 6 | partial failure, safe re-upload, receipt-vs-entry dates, quoted CSV fields |
| `IdempotencyIT` | 4 | replay fidelity, key reuse, concurrent submission |
| `AuditChainIT` | 4 | the chain recomputes end to end from stored fields |
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
