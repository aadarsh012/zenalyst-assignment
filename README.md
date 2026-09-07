# Housing Allocation Backend

A backend for a public housing scheme: roughly **4,000 applications for 600 flats**, allocated
by a published rule set — a draw within categories, with reserved quotas and a preference for
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

---

## Status

| Phase | Scope | State |
|---|---|---|
| 0 | Skeleton, schema baseline, append-only audit substrate, error model | ✅ complete |
| 1 | Intake and normalisation (online + paper), idempotency | planned |
| 2 | Multi-tier deduplication and the human review queue | planned |
| 3 | Eligibility and registry freeze (merkle root) | planned |
| 4 | Rules engine and the allocator | planned |
| 5 | The draw: seed commitment, reveal, execution | planned |
| 6 | Explainability, audit verification, public export | planned |
| 7 | Objections and re-draw | planned |
| 8 | Auth, observability, documentation, seed-data generator | planned |

---

## Quickstart

**Prerequisites:** Java 17, Docker (or Colima), and `make`. Maven is not required — the project
ships a wrapper.

```bash
make up       # start PostgreSQL 16
make seed     # apply migrations, then load the demo scheme
make run      # start the application on :8080
```

Migrations can also be applied and inspected without starting the application, which is useful
when you want to look at the schema before running anything:

```bash
make migrate        # apply pending migrations
make migrate-info   # show what has been applied
make schema         # print the current tables
```

In another shell:

```bash
curl -s localhost:8080/actuator/health
curl -s localhost:8080/api/v1/schemes
curl -s localhost:8080/api/v1/schemes/MHS-2026
curl -si localhost:8080/api/v1/schemes/NOPE | head -20   # RFC 9457 problem document
```

`make help` lists every target.

### Running with Colima

The `Makefile` detects Colima automatically and exports `DOCKER_HOST` and
`TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE` on your behalf. If you use Docker Desktop instead,
nothing is set and the defaults apply. If you have both installed, note that they compete for
`~/.docker/config.json`; setting `DOCKER_HOST` explicitly, as the `Makefile` does, avoids the
ambiguity entirely.

---

## Design

### The three ideas the system rests on

**1. Verifiable randomness — commit, then reveal.** Before the draw, the authority publishes
`SHA-256(seed ‖ salt)`. Only after the candidate registry is frozen does it publish the `seed`
itself. Anyone can recompute the commitment and confirm the seed was fixed *before* the
authority could see who it would favour.

Each candidate's position in the draw is then:

```
ticket = HMAC-SHA256(key = seed, msg = canonicalCandidateKey)
order  = ticket ascending, ties broken by canonicalCandidateKey
```

This is deliberately **not** `Collections.shuffle(list, new Random(seed))`. That result depends
on the order of the input list and on the JDK's `Random` implementation, so a journalist
re-running it in Python would get different names and reasonably conclude we had cheated. An
HMAC over a canonical key is stable across languages, JVM versions and database row ordering.
Verifiability is a property of the algorithm, not of our infrastructure.

**2. Freeze before you draw.** The candidate registry is snapshotted immutably before the seed
is revealed. Each row is serialised as canonical JSON, hashed, and the sorted leaves are folded
into a **merkle root** that is published alongside the seed commitment. This gives every
applicant an **inclusion proof**: cryptographic evidence that their row, exactly as it stood,
was among the inputs that produced the result — without having to trust our word for it.

**3. Append-only, hash-chained audit.** Every decision-affecting action appends to
`audit_event`, where each row carries the hash of its predecessor. `UPDATE`, `DELETE` and
`TRUNCATE` are refused by a database trigger. Corrections are recorded as new compensating
events; history is never edited. Tampering does not become impossible — it becomes *detectable*.

### Package layout

```
com.zenalyst.housing
├── scheme/        the root aggregate: flats, application window, status
├── intake/        REST + CSV import, immutable raw submissions        [phase 1]
├── identity/      multi-tier fingerprinting, merge, review queue      [phase 2]
├── eligibility/   rule evaluation producing reason codes              [phase 3]
├── registry/      freeze, canonical JSON, merkle root and proofs      [phase 3]
├── rules/         versioned rule documents                            [phase 4]
├── allocation/    ★ PURE: allocator, tickets, pools, waitlists        [phase 4]
├── draw/          orchestration, JobRunr jobs, persistence            [phase 5]
├── transparency/  /explain, /verify, public export                    [phase 6]
├── objection/     grievances, re-draw as a new version                [phase 7]
├── audit/         hash-chained append-only log                        [phase 6]
└── platform/      idempotency, error model, security, observability
```

The `allocation` package is enforced pure by `ArchitectureTest`: it may not reference Spring,
JPA, the system clock, or any source of randomness other than the published seed. The entire
defensibility of the system rests on the allocator being a pure function of *(frozen registry,
rule version, seed)*, and a single stray `Instant.now()` would quietly make that claim false
while every other test continued to pass. So the build checks it rather than trusting review.

### Schema ownership

Flyway owns the schema; Hibernate runs with `ddl-auto: validate` and never creates or alters a
table. A schema that has drifted from its migration history cannot be audited, and this system's
entire value proposition is that it can be.

---

## Testing

```bash
make test     # unit + architecture tests — fast, no Docker required
make verify   # everything, including Testcontainers integration tests
```

| Layer | Covers |
|---|---|
| Unit | pure logic: hashing, canonical JSON, merkle, the allocator |
| Architecture (ArchUnit) | the allocator stays pure; no package cycles |
| Integration (Testcontainers) | real PostgreSQL 16, real migrations, real HTTP |

Integration tests share a single PostgreSQL container started once per JVM, and configure it
with `--locale=C` to match `docker-compose.yml`. Text ordering under a locale-sensitive
collation varies by host, and a system whose defence is "you can reproduce our results" cannot
have its ordering depend on where it runs.

---

## Architecture decisions

Longer-form rationale lives in [`adr/`](adr/), written as decisions are taken rather than
reconstructed afterwards.

| ADR | Decision |
|---|---|
| [0001](adr/0001-flyway-owns-the-schema.md) | Flyway owns the schema; Hibernate only validates |
| [0002](adr/0002-audit-log-is-append-only.md) | The audit log is append-only and hash-chained |

---

## Repository conventions

- One branch per phase (`phase/0-skeleton`, `phase/1-intake`, …), merged with `--no-ff` so the
  phase boundaries stay visible in the history.
- One Flyway migration per phase. Applied migrations are never edited.
- `docs/` holds the original assignment material and is intentionally not committed.

### On the append-only claim

`BaselineSchemaIT` asserts against a real PostgreSQL that `audit_event` refuses `UPDATE`,
`DELETE` and `TRUNCATE`. "Append-only" is exactly the sort of property that gets stated in a
README, believed by everyone, and is quietly untrue two years later because a migration dropped
the trigger. Asserting it in the build keeps the claim honest.

You can confirm it by hand:

```bash
make psql
INSERT INTO audit_event (occurred_at, actor, action, subject_type, subject_id, prev_hash, hash)
VALUES (now(), 'you', 'MANUAL_TEST', 'scheme', 'MHS-2026', repeat('0',64), repeat('1',64));
UPDATE audit_event SET actor = 'tampered';   -- ERROR: audit_event is append-only
DELETE FROM audit_event;                     -- ERROR: audit_event is append-only
TRUNCATE audit_event;                        -- ERROR: audit_event is append-only
```
