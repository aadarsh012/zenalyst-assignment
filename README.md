# Housing Allocation Backend

A public housing scheme has **600 flats and about 4,000 applications**. Some arrived online, some
on paper that was typed in later, and a fair number of people applied twice because they were not
sure the first one went through. Flats are allotted by published rules — a draw within categories,
with reserved quotas and a preference for existing residents.

**The final list will be challenged** — by an applicant, by a newspaper, and quite possibly in
court.

That last sentence is the design constraint, and the reason this is not a CRUD service. Allotting
600 flats is a sort. The hard problem is that every published output must be **independently
re-derivable from published inputs by somebody who does not trust us**, and every one of the 3,400
unsuccessful applicants must be able to get a specific answer to *"why not me?"*.

Java 17 · Spring Boot 3.5 · PostgreSQL 16 · Flyway · JobRunr · 289 tests

---

## Run it

**You need:** Docker, Java 17, `make`. Maven ships with the repo.

```bash
make up      # PostgreSQL 16 in Docker
make run     # the application on :8080 — leave this running
```

Then, in another shell:

```bash
make demo-small    # 400 applicants, 60 flats — about 10 seconds
make demo          # 4,000 applicants, 600 flats — about 7 minutes
```

`make demo` runs an entire housing scheme through the **public API** — nothing touches the database
directly, so anything it does you could do with `curl`:

```
 3. Importing paper applications      3,220 accepted, 0 rejected
 4. Submitting online applications    1,100 submitted
 5. Verifying certificates            4,601 recorded
 6. Resolving duplicates              4,320 applications -> 4,067 people
                                      253 linked automatically, 23 awaiting a human
 8. Freezing the register             4,320 candidates, 3,991 eligible
 9. Committing to a seed              the seed itself is not in the response: True
10. Revealing the seed                SHA-256(seed + ':' + salt) == commitment: True
11. Drawing                           600 flats allotted
12. Verifying                         PASS REGISTRY_ROOT   PASS SEED_COMMITMENT
                                      PASS RULES_HASH      PASS ALLOTMENT
                                      PASS RESULT_HASH     PASS AUDIT_CHAIN (8,930 events)
```

Other useful targets: `make test` (unit tests only, no Docker), `make verify` (everything),
`make psql`, `make help`.

API docs at `/swagger-ui.html`. Background-job dashboard at `:8000/dashboard`.

---

## Prove it to yourself

The system's whole claim is that you do not have to believe it. Four checks, in ascending order of
how convincing they are. `make demo` prints the draw id and registry root you need.

**1 — Rebuild the register in a different language.** `scripts/verify-registry.py` uses the Python
standard library and shares no code with the service:

```bash
python3 scripts/verify-registry.py <registryRoot> <applicationNo>
```
```
published root : d7a9b1ee…
recomputed root: d7a9b1ee…
The published rows produce the published root.
inclusion proof for DEMO-SMALL-000186: 9 hashes — VERIFIED
```

**2 — Try to edit the result.** With the triggers left alone (`make psql`):

```sql
UPDATE audit_event SET actor = 'x' WHERE seq = 878;
DELETE FROM allotment WHERE draw_id = '<drawId>';
UPDATE draw SET seats_awarded = 999 WHERE id = '<drawId>';
```
```
ERROR:  audit_event is append-only; UPDATE is not permitted
ERROR:  draw results are written once; DELETE on allotment is not permitted
ERROR:  draw … is published and cannot be changed
```

**3 — Give a flat to somebody who did not win.** Drop the trigger first, so the database defence is
out of the way and only the arithmetic is left:

```bash
docker compose exec -T postgres psql -U housing -d housing -c \
 "ALTER TABLE allotment DISABLE TRIGGER allotment_no_mutation;
  UPDATE allotment SET application_no='<a-waitlisted-one>'
   WHERE draw_id='<drawId>' AND application_no='<a-winner>';
  ALTER TABLE allotment ENABLE TRIGGER allotment_no_mutation;"

curl -s -X POST localhost:8080/api/v1/draws/<drawId>/verify
```
```
verified: false        FAIL  ALLOTMENT
DEMO-SMALL-000315 should hold a flat (OPEN/MERIT/1) but has no allotment;
DEMO-SMALL-000150 holds a flat that the published inputs do not award
```

Both applicants named. Swap them back and it verifies again.

**4 — Edit one audit event.**

```
verified=false  firstBreakAtSeq=878  kind=CONTENT_ALTERED
Event 878 stores hash ac44fcf7… but its contents hash to 8d15d7df…
```

The verifier rehashes each event from its *contents* rather than comparing stored hashes to each
other — the latter catches a deleted event but not an edited one.

---

## Why it is built this way

Fifteen decisions are recorded in [`adr/`](adr/), each stating what was given up. The five that
shape everything else:

**The draw is a pure function.** `Allocator.allocate(frozenRegistry, rules, seed)` touches no
database, no clock and no randomness beyond the seed — enforced by an ArchUnit test written in
phase 0, before the package existed. Ordering is `HMAC-SHA256(seed, applicationNo)`, deliberately
not a seeded shuffle: a shuffle depends on input order and on the JDK's `Random`, so a journalist
reimplementing it in Python would get different names and reasonably conclude we had cheated.

**Freeze before you draw.** The candidate register is snapshotted into a Merkle tree and its root
published *before* any seed exists. An authority that could still change the candidate list after
seeing the seed could choose the outcome. Every applicant gets an inclusion proof — 13 hashes for a
register of 4,000. ([ADR-0009](adr/0009-freeze-before-the-draw.md))

**Open seats are filled first.** A reserved-category candidate who wins on open merit takes an
*open* seat, leaving their category's reserved seats available to others. Filling reserved pools
first turns a reservation into a ceiling. Horizontal reservations (women, PwD, ex-service, local
residence) are carved *out of* each pool, not added to it, and displaced applicants are named in
the result. ([ADR-0010](adr/0010-open-seats-first-and-horizontal-carve-outs.md))

**Uncertainty is escalated, not resolved.** Exact matches on identity number, or name + birthday +
phone, merge automatically. A *resembling* name never does — it becomes a review for a human, whose
decision is recorded either way. An unverified certificate does not disqualify anyone; it means
competing on open merit, because a scheme that disqualifies people for its own verification backlog
is allocating flats on the basis of its own admin.
([ADR-0006](adr/0006-fuzzy-matches-are-never-merged-automatically.md),
[ADR-0008](adr/0008-unverified-claims-do-not-disqualify.md))

**Nothing is edited, ever.** The audit chain, the frozen register, a published draw and its
allotments all refuse `UPDATE` and `DELETE` at the database level. A wrong result is *superseded* by
a new draw that links back to it; the original stays published and still verifies. A published
allotment that can be quietly amended is one that proves nothing.
([ADR-0014](adr/0014-corrections-supersede-they-never-edit.md))

---

## The API

35 endpoints. The ones worth looking at:

| | |
|---|---|
| `POST /api/v1/schemes/{code}/applications` | Apply. Honours `Idempotency-Key` |
| `POST /api/v1/schemes/{code}/applications:import` | Bulk paper import, per-row report |
| `POST /api/v1/schemes/{code}/deduplication:run` | Resolve duplicates across four tiers |
| `POST /api/v1/schemes/{code}/registry:freeze` | Freeze the register, publish its Merkle root |
| `POST /api/v1/schemes/{code}/draws` → `/reveal` → `/execute` → `/publish` | The draw ceremony |
| **`GET  /api/v1/applications/{no}/explain`** | **Why this applicant got a flat, or did not** |
| **`POST /api/v1/draws/{id}/verify`** | **Re-derive the draw from its published inputs** |
| `GET  /api/v1/audit/verify` | Rehash the audit chain; name the first break |
| `GET  /api/v1/draws/{id}/results.csv` | The published list, with its inputs in the header |
| `POST /api/v1/schemes/{code}/objections` | Challenge a published result |

### Applying

```bash
curl -s -X POST localhost:8080/api/v1/schemes/MHS-2026/applications \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: my-key-1' \
  -d '{"fullName":"Ramesh Kumar","dateOfBirth":"1990-02-01",
       "governmentId":"234567890124","phone":"+91 98765 43210",
       "addressLine":"12 Nehru Road","wardCode":"W-07",
       "category":"OBC","gender":"MALE","localResident":"true",
       "disability":"false","exServiceperson":"false","annualIncome":"250000"}'
```

Send it twice with the same `Idempotency-Key` — you get the identical response and **one**
application. Reuse the key with a different body and you get `409`, not a wrong answer. The
`governmentId` must carry a valid Verhoeff check digit, so a mistyped number is caught at the
counter rather than surfacing weeks later as a failed duplicate match.

The identity number is validated and then **discarded** — only a scheme-scoped HMAC and the last
four digits are stored. ([ADR-0003](adr/0003-identity-numbers-are-never-stored.md))

### Explaining an outcome

```bash
curl -s localhost:8080/api/v1/applications/DEMO-000021/explain \
  -H "Authorization: Bearer $TOKEN"
```
```
outcome: WAITLISTED

You have not been allotted a flat. You are number 48 on the waiting list for the OPEN
pool, where you were ranked 60. If the 47 ahead of you give up a flat, yours is next.

  OPEN   you ranked 60 of 60   seats 12   merit cutoff at rank 12

check it yourself:
  - Your place in the draw is HMAC-SHA256(seed, "DEMO-000021") = fd9134fe…
    Compute it yourself; it depends on nothing but those two values.
  - Your row was among the inputs: fold canonicalJson up through inclusionProof
    to reach the registry root 3fc6b93d…
  - SHA-256(seed + ":" + salt) equals the commitment 72beebe9…, published when
    the draw was created.
```

*"You were not selected"* is a restatement of the question. *"You were 60th and the last seat went
to 12th"* is an answer.

### Authentication

Endpoints that can **influence** the outcome need a bearer token. Endpoints that **check** it do
not — a verification only the authority can run proves nothing.

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/dev/token \
  -H 'Content-Type: application/json' \
  -d '{"subject":"registrar","roles":["ADMIN"]}' | jq -r .token)
```

| Role | May |
|---|---|
| *(none)* | apply, object, verify a draw, verify the audit chain, download the register and results |
| `APPLICANT` | read **their own** file — the token's subject is their application number |
| `OPERATOR` | import paper forms, verify certificates, run deduplication, judge fuzzy matches |
| `AUDITOR` | read any applicant's file; change nothing |
| `ADMIN` | publish rules, freeze, run the draw, adjudicate objections |

The token endpoint exists only under the `dev` profile — `@Profile("dev")`, not a config flag, so
elsewhere the bean is absent and the route 404s. Both development secrets refuse to start the
application outside development.

---

## Assumptions

The brief is one paragraph, so a good deal had to be decided. The choices that would change the
system if they were wrong:

- **The quota matrix is Indian-style**: vertical categories (GEN/SC/ST/OBC/EWS) as exclusive
  buckets, with horizontal reservations carved out within each. The brief said "categories,
  reserved quotas and a preference for existing residents"; this is the standard reading.
- **Local-resident preference is a sub-quota**, not a tie-break. With 256-bit HMAC ordering, exact
  ties do not occur, so a tie-break would be a policy that never fires.
- **Ambiguous numeric dates are read day-first** — `01/02/1990` is 1 February. The convention of
  the forms this ingests, and genuinely ambiguous; the raw submission is preserved so a disputed
  date can be re-examined rather than argued about.
- **Age is measured at the scheme's closing date**, so every applicant is judged at the same
  instant regardless of when their file was processed.
- **A paper form's receipt date is its submission date.** A form handed in on 3 March and typed up
  on 19 March was submitted on 3 March; treating data-entry as submission would disqualify people
  because a clerk was slow. ([ADR-0005](adr/0005-submission-date-is-not-the-data-entry-date.md))
- **Identity numbers are Aadhaar-shaped** — 12 digits with a Verhoeff check digit.
- **One draw per scheme at a time**, and a result is replaced only where an objection has been
  upheld in writing. An authority able to re-draw at will can draw until it likes the answer, and
  every individual draw would still verify perfectly.

---

## What was left out, and why

Stated rather than quietly omitted:

**Rate limiting.** Public intake endpoints need it. An in-process limiter across several instances
limits nothing except the instance a request happens to reach, while looking in code review as
though the problem were solved. It belongs at the gateway.

**Applicant identity.** Nothing issues a real applicant a token bearing their own application
number; the development endpoint gives one to anybody. A deployment points the resource server at
an identity provider's JWKS.

**Property-ownership checks.** A real scheme asks whether an applicant already owns a home. That
needs a property register this system has no access to, and a self-declaration nobody verifies
would be theatre.

**Flat allocation.** Every pool draws from one undifferentiated inventory. Real schemes allot flats
of different sizes in different blocks, and preference between them is a second allocation problem
that would sit on top of this one: first who gets a flat, then which flat.

**Surrenders.** Waitlists are computed, persisted and exposed, but nothing consumes one. Offering a
surrendered flat to the next in line is an operation this system does not have.

**A randomness beacon by default.** Both are implemented. `AUTHORITY_COMMITTED` proves the seed did
not change after publication; it does **not** prove it was not *chosen* — with the register already
frozen, an authority could try a thousand seeds and publish the commitment for the one it liked.
`DRAND_BEACON` closes that by committing to a future round of a public beacon nobody controls. The
weaker one is the default because it works without a third party being reachable, and
[ADR-0011](adr/0011-commit-reveal-and-why-a-beacon-is-stronger.md) says so plainly rather than
implying the guarantee is stronger than it is.

**Coarse roles.** Four, where a real authority distinguishes a counter clerk from a verification
officer from a district registrar, and would want an approval step on the draw rather than one
`ADMIN` role that can commit, reveal, execute and publish alone.

---

## Layout

```
com.zenalyst.housing
├── scheme/          the scheme: flats, application window, status
├── intake/          both channels, normalisation, CSV import, idempotency
├── normalisation/   pure functions reducing input to comparable form
├── identity/        fingerprinting, the merge graph, the review queue
├── eligibility/     the rules, claim verification, reason codes
├── registry/        the freeze, the Merkle tree, inclusion proofs
├── rules/           versioned, hashed quota matrices
├── allocation/      ★ PURE: the allocator, pools, tickets, waitlists
├── draw/            the ceremony: commit, reveal, execute, publish
├── transparency/    explain, verify, audit check, public export
├── objection/       challenges, adjudication, superseding draws
└── platform/        errors, hashing, idempotency, security, observability
```

Every package has a `package-info.java` saying what it does and which class to read first. Start
with `allocation/Allocator` or `transparency/ExplainService`.

Flyway owns the schema; Hibernate runs `ddl-auto: validate` and may never alter a table. One
migration per phase, never edited once applied.

## Tests

```bash
make test     # 165 unit and architecture tests — seconds, no Docker
make verify   # all 289, including 124 against a real PostgreSQL via Testcontainers
```

Real PostgreSQL rather than H2, because the design leans on `jsonb`, advisory locks, partial unique
indexes and `RAISE EXCEPTION` triggers — a suite on H2 would pass while every claim went untested.

Notable: `AllocatorTest` covers the migration rule, horizontal displacement, determinism over 25
shuffles, invariants across 60 random populations and a full 4,000→600 draw. `MerkleTreeTest`
covers proofs at every tree size, tamper resistance, RFC 6962 domain separation and
CVE-2012-2459. `ArchitectureTest` enforces the allocator's purity — adding an `Instant.now()` to
that package fails the build.

## Decisions

Fifteen records in [`adr/`](adr/), written as each decision was taken rather than reconstructed
afterwards. Each states the context, the decision, and what was given up.
