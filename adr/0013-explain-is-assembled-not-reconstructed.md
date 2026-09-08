# 13. The explanation is assembled from stored facts, never reconstructed

- **Status:** accepted
- **Phase:** 6

## Context

The brief ends by saying the final list will be questioned by an applicant, by a newspaper, and
quite possibly in court. Those are three different questions and they want three different answers.

The applicant asks *why not me?* The newspaper asks *can I check this?* The court asks *is this what
you said it would be?*

A system that produces a correct allotment and cannot answer those has not finished the job. The
temptation is to answer them by reconstruction — work out afterwards, from whatever is still in the
database, what probably happened. That fails in a specific and damaging way: two people asking the
same question at different times can get different answers, and neither can be shown to be the one
the draw actually used.

## Decision

**Every fact in an explanation is read, not derived.** The lottery ticket, the rank, the pool
standings, the cutoff, the inclusion proof — each is stored at the moment it was decided and looked
up afterwards. Earlier phases were built to make this a lookup: the frozen registry stores each
candidate's exact bytes, the draw stores the full merit order and not merely the winners, and
`draw_waitlist` stores where an applicant actually came as well as their place in the queue.

Those last two deserve saying plainly. Storing only the six hundred winners would make "you were
412th" unanswerable, and three and a half thousand people are entitled to that number. And a queue
position is not a rank: a horizontal top-up displaces somebody from inside the cutoff, so the first
person waiting may be ranked above somebody who was allotted a flat. Reporting the position as
though it were the rank would be a small, confident lie.

**The explanation says what would have had to be different.** "You were ranked 60; the last seat on
merit went to rank 12" is an answer. "You were not selected" is a restatement of the question.

**Nothing asks to be trusted.** Every response carries instructions for checking the parts of it that
do not require us: recompute your own HMAC from the published seed, fold your row up through the
inclusion proof to the published root, check the seed against its commitment, or re-derive the whole
draw. A verification endpoint only the authority can run proves nothing.

**Verification recomputes rather than compares stored values.** The audit chain is rehashed from
each event's contents, not checked link-by-link against stored hashes — the latter catches a deleted
event but not an edited one. The draw is re-derived by running the allocator again on the frozen
register, not by re-reading what was written.

**The published file carries its inputs and no personal data.** Every hash needed to check the
allotment is in the header; the rows carry an application number, a pool, a basis and a rank. A
national newspaper's worth of names and addresses is a disclosure the draw does not require.

## Consequences

**Gained.** A tampered allotment is caught and both affected applicants are named. An edited audit
event is caught and identified by sequence number. An applicant can verify their own place in the
draw on paper, with a hash function and nothing else.

**Given up.** Storing the whole merit order and per-pool ranks costs roughly `candidates × pools`
rows per draw — a few tens of thousands at this scale, which is nothing, and would need thought at a
scale where a draw had millions of entrants. The alternative, recomputing on demand, is cheap and
deterministic and was rejected anyway: an explanation that is recomputed is an explanation that can
change if the code changes, and the point is that it cannot.

**Not addressed.** These endpoints are unauthenticated, which means anyone can read anyone's
explanation. The content is deliberately impersonal — no name, no address, no contact details — so
the disclosure is bounded, but an application number is guessable and a determined party could
enumerate the register. Phase 8 puts the per-applicant endpoint behind authentication; the
verification and export endpoints should stay open, because their value is that anybody can use
them.
