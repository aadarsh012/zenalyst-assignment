# Housing Allocation Backend

A public housing scheme has 600 flats and about 4,000 applications. Some arrived online, some on
paper that was typed in later, and a fair number of people applied twice because they were not sure
the first one went through. Flats are given out by published rules: a draw within categories, with
reserved quotas and a preference for people already living in the area.

The final list will be challenged. By an applicant, by a newspaper, and quite possibly in court.

That is the design constraint. Allotting 600 flats is a sort. The hard part is that anyone must be
able to take the published data, re-run the draw themselves, and get the same 600 names. And each
of the 3,400 people who did not get a flat must be able to find out exactly why.

Java 17, Spring Boot 3.5, PostgreSQL 16, Flyway, JobRunr. 290 tests.

---

## Run it

### Install what you need

One time setup. Maven is not on this list: the repo ships its own (`./mvnw`).

**Docker Desktop.** Download it from
[docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/), install it,
and start it. PostgreSQL runs inside Docker, so there is no database to install yourself.

**Java 17.**

```bash
# macOS
brew install openjdk@17
sudo ln -sfn $(brew --prefix)/opt/openjdk@17/libexec/openjdk.jdk \
             /Library/Java/JavaVirtualMachines/openjdk-17.jdk

# Debian or Ubuntu
sudo apt install openjdk-17-jdk
```

Homebrew does not put Java on your path by itself. The symlink is what lets macOS find it. Check
with `java -version`, which should report 17.

**make, git and Python 3.** On macOS all three come with the Xcode command line tools:

```bash
xcode-select --install
```

On Debian or Ubuntu: `sudo apt install make git python3`.

Python runs the demo and the verification script. They use only the standard library, so any
version from 3.8 up is fine.

**jq** is optional. One example further down uses it to pull a token out of a response:
`brew install jq`, or `sudo apt install jq`.

### Start it

```bash
make up      # PostgreSQL 16 in Docker
make run     # the application on :8080, leave this running
```

Use `make`, not `./mvnw` directly. The Makefile points `JAVA_HOME` at your Java 17 install, so
`make` works even when your shell has no Java on its path.

Then in another shell:

```bash
make demo-small    # 400 applicants, 60 flats, about 10 seconds
make demo          # 4,000 applicants, 600 flats, about 7 minutes
```

`make demo` runs a whole housing scheme through the public API. It never touches the database
directly, so everything it does you could do with `curl`.

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

Other targets: `make test` (unit tests, no Docker), `make verify` (everything), `make psql`,
`make help`.

API docs at `/swagger-ui.html`. Background job dashboard at `:8000/dashboard`.

---

## Prove it to yourself

You should not have to take the result on trust. Here are four ways to check it, in increasing
order of how convincing they are. `make demo` prints the draw id and registry root you will need.

### 1. Rebuild the register yourself

**What gets published.** When the register is frozen, every candidate row is hashed. Those hashes
are combined in pairs, over and over, until a single 64-character value is left. That value is the
**registry root**, and it is published before the draw is run. Change one row afterwards, add a
candidate, or remove one, and the root comes out different.

**What the script checks.** `scripts/verify-registry.py` answers two questions using only data the
API publishes:

1. *Do the published rows really produce the published root?* It downloads all the candidate rows,
   rebuilds the tree itself, and compares its own answer with ours. If they differ, the register has
   been changed since it was published.
2. *Was one particular applicant among those rows?* For any application number it fetches an
   **inclusion proof**: the applicant's own row plus about a dozen other hashes. Combine them and
   you get the root back. That proves the row was one of the inputs to the draw, without needing to
   download the whole register.

**Why it is a separate script.** It shares no code with the service and imports nothing beyond
Python's standard library. The hashing logic is about fifteen lines, so anyone can write their own
version in any language. A number that can only be checked using our software is not proof of
anything.

```bash
python3 scripts/verify-registry.py <registryRoot> <applicationNo>
```
```
published root : d7a9b1ee…          <- what the API says the register hashes to
rows published : 432
recomputed root: d7a9b1ee…          <- what the rows actually hash to, worked out here

The published rows produce the published root.

inclusion proof for DEMO-SMALL-000186: 9 hashes
  row  : {"applicationNo":"DEMO-SMALL-000186","category":"EWS","eligible":true, ...}
  check: VERIFIED, this row was among the inputs
```

Nine hashes for 432 candidates, thirteen for 4,000. Small enough to print on a slip of paper and
hand to an applicant.

### 2. Try to edit the result

Open a database shell with `make psql` and attempt the three edits somebody covering their tracks
would want to make:

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

### 3. Give a flat to somebody who did not win

Drop the trigger first, so the database defence is out of the way and only the arithmetic is left:

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

Both applicants are named. Put them back and it verifies again.

### 4. Edit one audit event

```
verified=false  firstBreakAtSeq=878  kind=CONTENT_ALTERED
Event 878 stores hash ac44fcf7… but its contents hash to 8d15d7df…
```

The checker recomputes each event's hash from the event's own contents. Comparing stored hashes to
each other would catch a deleted event but not an edited one.

---

## Why it is built this way

Fifteen decisions are written up in [`adr/`](adr/), each one saying what it cost. Five of them
shape everything else.

**The draw is a pure function.** `Allocator.allocate(frozenRegistry, rules, seed)` uses no database,
no clock and no randomness except the seed. An ArchUnit test enforces this, and it was written in
phase 0 before the package existed. Order is decided by `HMAC-SHA256(seed, applicationNo)` rather
than a seeded shuffle, because a shuffle depends on the order of the input list and on the JDK's
`Random`. Someone re-running it in Python would get different names and could reasonably conclude
we had cheated.

**Freeze before you draw.** The list of candidates is fixed and its root published before any seed
exists. An authority that could still change the candidate list after seeing the seed could pick
the outcome. See [ADR-0009](adr/0009-freeze-before-the-draw.md).

**Open seats are filled first.** Someone from a reserved category who wins on open merit takes an
open seat, which leaves their category's reserved seats free for others. Filling reserved pools
first would turn a reservation into a ceiling. Reservations for women, disabled applicants,
ex-service applicants and local residents are taken out of each pool rather than added to it, and
anyone displaced is named in the result. See
[ADR-0010](adr/0010-open-seats-first-and-horizontal-carve-outs.md).

**Doubt goes to a human.** An exact match on identity number, or on name plus birthday plus phone,
merges automatically. A merely similar name never does. It becomes a review that a person decides,
and their decision is recorded either way. An unverified certificate does not disqualify anyone; it
means competing on open merit, because a scheme that disqualifies people over its own paperwork
backlog is handing out flats based on its own admin. See
[ADR-0006](adr/0006-fuzzy-matches-are-never-merged-automatically.md) and
[ADR-0008](adr/0008-unverified-claims-do-not-disqualify.md).

**Nothing is edited.** The audit log, the frozen register, a published draw and its allotments all
refuse `UPDATE` and `DELETE` in the database itself. A wrong result is replaced by a new draw that
points back at the old one, and the old one stays published and still verifies. A published list
that can be quietly amended proves nothing. See
[ADR-0014](adr/0014-corrections-supersede-they-never-edit.md).

---

## The API

36 endpoints, two of them development only. The ones worth looking at:

| | |
|---|---|
| `POST /api/v1/schemes/{code}/applications` | Apply. Honours `Idempotency-Key` |
| `POST /api/v1/schemes/{code}/applications:import` | Bulk paper import, with a per-row report |
| `POST /api/v1/schemes/{code}/deduplication:run` | Resolve duplicates across four tiers |
| `POST /api/v1/schemes/{code}/registry:freeze` | Freeze the register, publish its root |
| `POST /api/v1/schemes/{code}/draws` then `/reveal`, `/execute`, `/publish` | The draw |
| **`GET  /api/v1/applications/{no}/explain`** | **Why this applicant got a flat, or did not** |
| **`POST /api/v1/draws/{id}/verify`** | **Re-run the draw from the published inputs** |
| `GET  /api/v1/audit/verify` | Recheck the audit log, name the first break |
| `GET  /api/v1/draws/{id}/results.csv` | The published list, with its inputs in the header |
| `POST /api/v1/schemes/{code}/objections` | Challenge a published result |

### Applying

```bash
curl -s -X POST localhost:8080/api/v1/schemes/DEMO-2026/applications \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: my-key-1' \
  -d '{"fullName":"Ramesh Kumar","dateOfBirth":"1990-02-01",
       "governmentId":"234567890124","phone":"+91 98765 43210",
       "addressLine":"12 Nehru Road","wardCode":"W-07",
       "category":"OBC","gender":"MALE","localResident":"true",
       "disability":"false","exServiceperson":"false","annualIncome":"250000"}'
```

Send it twice with the same `Idempotency-Key` and you get the same response back and one
application, not two. Reuse the key with a different body and you get a `409` rather than a wrong
answer.

`governmentId` must have a valid Verhoeff check digit, so a mistyped number is caught at the counter
instead of surfacing weeks later as a duplicate that failed to match. The number is checked and then
thrown away: only a scheme-scoped HMAC and the last four digits are stored. See
[ADR-0003](adr/0003-identity-numbers-are-never-stored.md).

### Explaining an outcome

Application numbers are `{schemeCode}-{sequence}`. `make demo` prints a real one to try at the end of its run.

```bash
curl -s localhost:8080/api/v1/applications/DEMO-2026-000021/explain \
  -H "Authorization: Bearer $TOKEN"
```
```
outcome: WAITLISTED

You have not been allotted a flat. You are number 48 on the waiting list for the OPEN
pool, where you were ranked 60. If the 47 ahead of you give up a flat, yours is next.

  OPEN   you ranked 60 of 60   seats 12   merit cutoff at rank 12

check it yourself:
  - Your place in the draw is HMAC-SHA256(seed, "DEMO-2026-000021") = fd9134fe…
    Work it out yourself; it depends on nothing but those two values.
  - Your row was one of the inputs: combine canonicalJson with inclusionProof
    to get the registry root 3fc6b93d…
  - SHA-256(seed + ":" + salt) equals the commitment 72beebe9…, which was
    published when the draw was created.
```

"You were not selected" restates the question. "You were 60th and the last seat went to 12th"
answers it.

### Authentication

Endpoints that can change the outcome need a bearer token. Endpoints that check the outcome do not,
because a check only the authority can run is worth nothing.

```bash
TOKEN=$(curl -s -X POST localhost:8080/api/v1/dev/token \
  -H 'Content-Type: application/json' \
  -d '{"subject":"registrar","roles":["ADMIN"]}' | jq -r .token)
```

| Role | Can |
|---|---|
| *(none)* | apply, object, verify a draw, verify the audit log, download the register and results |
| `APPLICANT` | read their own file. The token's subject is their application number |
| `OPERATOR` | import paper forms, verify certificates, run deduplication, judge fuzzy matches |
| `AUDITOR` | read any applicant's file, change nothing |
| `ADMIN` | publish rules, freeze, run the draw, decide objections |

The token endpoint only exists under the `dev` profile. That is `@Profile("dev")` rather than a
config setting, so elsewhere the bean is not created at all. Both development secrets stop the
application from starting outside development.

---

## Assumptions

The brief is one paragraph, so a lot had to be decided. These are the choices that would change the
system if they turned out to be wrong.

- **The quota structure is Indian-style.** Vertical categories (GEN, SC, ST, OBC, EWS) are exclusive
  buckets, with horizontal reservations taken out of each. The brief said "categories, reserved
  quotas and a preference for people already living in the area", and this is the standard reading.
- **Local residence is a sub-quota, not a tie-break.** With 256-bit hashes deciding order, exact
  ties do not happen, so a tie-break would be a rule that never fires.
- **Ambiguous dates are read day first.** `01/02/1990` is the first of February. That is the
  convention of the forms this reads, and it is genuinely ambiguous, so the original submission is
  kept and a disputed date can be looked at again.
- **Age is measured at the closing date**, so everyone is judged at the same moment however long
  their file sat in a queue.
- **A paper form's receipt date is its submission date.** A form handed in on 3 March and typed up
  on 19 March was submitted on 3 March. Using the typing date would disqualify people because a
  clerk was slow. See [ADR-0005](adr/0005-submission-date-is-not-the-data-entry-date.md).
- **Identity numbers are Aadhaar-shaped**: twelve digits with a Verhoeff check digit.
- **One draw at a time per scheme**, and a result is only replaced where an objection has been
  upheld in writing. An authority that can re-draw whenever it likes can keep drawing until it likes
  the answer, and each individual draw would still check out perfectly.

---

## What was left out, and why

Said plainly rather than left for you to notice.

**Rate limiting.** The public application endpoints need it. A limiter held in memory does nothing
useful once there is more than one instance, while looking in code review as though the problem were
handled. It belongs at the gateway.

**Applicant identity.** Nothing gives a real applicant a token carrying their own application
number. The development endpoint hands one to anybody. A real deployment would point the resource
server at an identity provider.

**Checking whether an applicant already owns property.** A real scheme asks this. Answering it needs
a property register this system cannot reach, and a self-declaration nobody checks would be for
show.

**Which flat you get.** Every pool draws from one undivided pile of seats. Real schemes have flats
of different sizes in different blocks, and choosing between them is a second allocation problem
that would sit on top of this one.

**Surrenders.** Waiting lists are worked out, stored and published, but nothing uses them. Offering
a returned flat to the next person in line is an operation this system does not have.

**A randomness beacon by default.** Both options are built. `AUTHORITY_COMMITTED` proves the seed
did not change after it was published. It does not prove the seed was not *chosen*: with the
register already frozen, an authority could try a thousand seeds privately and publish the
commitment for whichever it preferred. `DRAND_BEACON` closes that by committing to a future round of
a public beacon nobody controls. The weaker option is the default because it works without depending
on a third party being reachable, and
[ADR-0011](adr/0011-commit-reveal-and-why-a-beacon-is-stronger.md) says so rather than implying the
guarantee is stronger than it is.

**Fine-grained roles.** There are four. A real authority separates a counter clerk from a
verification officer from a district registrar, and would want two people to sign off the draw
rather than one `ADMIN` who can commit, reveal, execute and publish alone.

---

## Layout

```
com.zenalyst.housing
├── scheme/          the scheme: flats, application window, status
├── intake/          both channels, normalisation, CSV import, idempotency
├── normalisation/   pure functions that reduce input to a comparable form
├── identity/        fingerprinting, the merge graph, the review queue
├── eligibility/     the rules, certificate checks, reason codes
├── registry/        the freeze, the hash tree, inclusion proofs
├── rules/           versioned, hashed quota matrices
├── allocation/      PURE: the allocator, pools, tickets, waiting lists
├── draw/            commit, reveal, execute, publish
├── transparency/    explain, verify, audit check, public export
├── objection/       challenges, decisions, replacement draws
└── platform/        errors, hashing, idempotency, security, observability
```

Every package has a `package-info.java` saying what it does and which class to read first. Start
with `allocation/Allocator` or `transparency/ExplainService`.

Flyway owns the schema. Hibernate runs with `ddl-auto: validate` and is never allowed to change a
table. One migration per phase, never edited once applied.

## Tests

```bash
make test     # 165 unit and architecture tests, seconds, no Docker
make verify   # all 290, including 125 against a real PostgreSQL via Testcontainers
```

Tests run against real PostgreSQL rather than H2, because the design relies on `jsonb`, advisory
locks, partial unique indexes and exception-raising triggers. A suite on H2 would pass while leaving
every one of those claims untested.

Worth a look: `AllocatorTest` covers the open-seats-first rule, displacement, determinism across 25
shuffles, invariants over 60 random populations and a full 4,000 to 600 draw. `MerkleTreeTest`
covers proofs at every tree size, tamper resistance, RFC 6962 domain separation and CVE-2012-2459.
`ArchitectureTest` enforces the allocator's purity: adding an `Instant.now()` to that package fails
the build.

## Decisions

Fifteen records in [`adr/`](adr/), written as each decision was made rather than reconstructed
afterwards. Each says what the context was, what was decided, and what it cost.
