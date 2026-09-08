# 7. Duplicate links are rebuilt on every run, not accumulated

- **Status:** accepted
- **Phase:** 2

## Context

Deduplication runs as a pass over the whole register rather than as a check when each application
arrives. It has to: duplicates arrive out of order, and application 4,000 may be a duplicate of
application 12, which an arrival-time check would never see.

That leaves the question of what a second run does to the conclusions of the first.

The obvious approach is incremental — add links for newly discovered matches, leave existing ones
alone. It has a defect that only shows up in this system's particular circumstances. The canonical
application, the one that stands for a person, is the earliest *submission*, and for paper
applications the submission date is the counter receipt date rather than the date it was typed in
(ADR-0005). So a paper form handed in on 3 March and entered in April predates an online
application submitted on 10 March that has been the canonical one for weeks. Once it exists, the
canonical must change. An incremental design would leave the register asserting that the later
application was the original, which is both wrong and exactly the sort of thing an applicant
notices.

## Decision

`duplicate_link` is a **projection**, not a history. Each run deletes the scheme's links and
recomputes them from two sources: the fingerprint matches, and the fuzzy matches a human has
confirmed.

What was decided survives. Human judgements live in `duplicate_review`, which is never deleted.
What the system did lives in the audit chain, which cannot be. Deleting links loses neither, and
regenerating them from both sources means one code path reconciles automatic and human input
rather than two paths that can disagree.

The result is that deduplication is a pure function of the register plus the recorded human
decisions. Run it twice, get the same answer. Run it after a late paper entry, and the canonical
application moves to where it should always have been.

## Consequences

**Gained.** Re-runnable and deterministic, which is what makes the outcome auditable — anyone with
the register and the review decisions can reproduce the links. Late-arriving paper applications are
handled correctly without a special case. A confirmed review takes effect immediately, because
confirming one simply triggers a rebuild.

**Given up.** `duplicate_link` cannot answer "when was this link first created?" or "what did we
believe last Tuesday?". Both are answerable from the audit chain, which records each run and each
decision, but they require reading the chain rather than querying a column.

Also given up: rebuilding is O(register) rather than O(new applications). At four thousand rows
this is milliseconds. At a scale where it were not, the right response would be to make the run a
background job rather than to make it incremental — the determinism is worth more than the
arithmetic.
