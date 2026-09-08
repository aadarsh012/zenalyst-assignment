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

- **Deduplication.** Four tiers of matching resolve the people who applied more than once. The
  three exact tiers merge automatically; resemblance is queued for a human and never acted on
  alone ([ADR-0006](adr/0006-fuzzy-matches-are-never-merged-automatically.md)).
- **Duplicates are linked, never deleted.** A set-aside application stays in the register,
  readable, and can tell its applicant in plain English why it no longer competes and which of
  their applications does.

Not yet built: eligibility, the registry freeze, the rules engine, the allocator, the draw itself,
and the transparency endpoints.

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
| `POST` | `/api/v1/schemes/{code}/deduplication:run` | Run a deduplication pass |
| `GET` | `/api/v1/applications/{applicationNo}/identity` | Does this application still compete, and if not why |
| `GET` | `/api/v1/schemes/{code}/duplicate-reviews` | The fuzzy matches awaiting a human |
| `POST` | `/api/v1/duplicate-reviews/{id}/decision` | Record an operator's judgement |
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

### Deduplication

```bash
curl -s -X POST 'localhost:8080/api/v1/schemes/MHS-2026/deduplication:run?runBy=operator-1'
```

A pass over the whole register, not a check at intake: duplicates arrive out of order, and
application 4,000 may be a duplicate of application 12. Safe to run repeatedly — the same register
always produces the same conclusions
([ADR-0007](adr/0007-duplicate-links-are-derived-not-accumulated.md)).

Four tiers, in descending certainty:

| Tier | Matches on | Merges automatically |
|---|---|---|
| `GOVERNMENT_ID` | the same identity number | yes |
| `NAME_DOB_PHONE` | name, date of birth and phone | yes |
| `NAME_DOB_EMAIL` | name, date of birth and email | yes |
| `PROBABLE` | similar name, same date of birth | **no — queued for a human** |

Matching is transitive. If A and B share an identity number and B and C share a phone number, all
three are one person even though A and C have nothing in common. Merging pairs independently would
leave that person holding two entries in a draw allowing one per household.

```bash
# what an applicant is told
curl -s localhost:8080/api/v1/applications/MHS-2026-000002/identity

# the review queue, and a decision
curl -s 'localhost:8080/api/v1/schemes/MHS-2026/duplicate-reviews?status=PENDING'
curl -s -X POST "localhost:8080/api/v1/duplicate-reviews/$REVIEW_ID/decision?decidedBy=clerk-anita" \
  -H 'Content-Type: application/json' \
  -d '{"outcome":"CONFIRMED_DUPLICATE","note":"Confirmed with applicant by phone."}'
```

A decision takes effect immediately, and the response is the fresh report. Rejections are
remembered, so re-running never asks the same question twice.

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

**`application_fingerprint`** — the match evidence, materialised. Two applications sharing a
fingerprint at a tier agreed on exactly the fields that tier is made of. Stored rather than
computed on the fly so that "why did you decide these were the same person?" is answered by a
stored fact, not by a query someone has to be trusted about.

**`duplicate_link`** — which applications were set aside, for which reason. Rebuilt on every run;
the decisions behind it live in `duplicate_review` and the audit chain.

**`duplicate_review`** — fuzzy matches and the human judgements on them. The only mutable entity in
the system, and deliberately so: this is where a person decides something about another person's
application, and it should be unambiguous who decided what and when.

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
├── scheme/          the scheme: flats, application window, status
├── intake/          both channels, normalisation, CSV import, idempotency
├── normalisation/   pure functions reducing input to comparable form
├── identity/        fingerprinting, the merge graph, the review queue
├── audit/           the hash chain
└── platform/        errors, hashing, idempotency, clock
```

Every package carries a `package-info.java` saying what it does and which class to read first.
Start with `identity/DeduplicationService` or `intake/IntakeService`; both read top to bottom as
the whole of their story.

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

132 tests: 90 unit and architecture tests that need no Docker, and 42 integration tests against a
real PostgreSQL.

| Suite | Count | Covers |
|---|---|---|
| `NormalisationTest` | 56 | names, phones, emails, dates, Verhoeff check digits |
| `CanonicalJsonTest` | 8 | deterministic rendering: key order, numbers, escaping, code-point sorting |
| `AuditHashTest` | 6 | hash determinism and what it depends on |
| `ArchitectureTest` | 5 | package boundaries and allocator purity guardrails |
| `OnlineIntakeIT` | 7 | acceptance, retrieval, accumulated validation errors, identity redaction |
| `PaperImportIT` | 6 | partial failure, safe re-upload, receipt-vs-entry dates, quoted CSV fields |
| `IdempotencyIT` | 4 | replay fidelity, key reuse, concurrent submission |
| `MergeGraphTest` | 9 | transitivity, canonical selection, order-independence |
| `FingerprintsTest` | 6 | determinism, tier separation, field-boundary forgery |
| `DeduplicationIT` | 12 | all four tiers, review decisions, re-run idempotency |
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
