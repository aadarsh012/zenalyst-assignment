# 14. A wrong result is superseded, never corrected

- **Status:** accepted
- **Phase:** 7

## Context

A published draw will sometimes be wrong. Not because the lottery misbehaved — that part is
arithmetic — but because an input was wrong. A category certificate was refused in error and the
applicant competed on open merit. A duplicate link joined two different people and one of them was
set aside as a copy of a stranger. An application never reached the register at all.

Each of those is a real, foreseeable mistake, and each one demands a remedy. The obvious remedy is
to correct the record and reissue the list.

That is precisely what this system must never do. A published allotment that can be quietly amended
is a published allotment that proves nothing, and every root, commitment and hash published up to
this point would be worth exactly as much as the authority's word that it had not amended anything.
The whole apparatus of the previous six phases exists to make the result checkable by someone who
does not trust us; an edit button hands that back.

## Decision

**A wrong result is superseded by a new draw, never corrected.** The original keeps its seed, its
registry root, its allotment and its audit trail, stays `PUBLISHED`, and remains independently
verifiable forever. Anybody can fetch both draws and see exactly what changed.

**Supersession is recorded on the successor only.** That is forced rather than chosen: a published
draw cannot be updated at all — the trigger from ADR-0012 refuses every change — so it cannot be
marked as superseded. The constraint turns out to be exactly right. Nothing about the original
changes when it is replaced, including its status.

**Upholding an objection changes nothing by itself.** It records a finding, states a remedy, and
authorises a superseding draw. The correction to the underlying record goes through the ordinary
endpoints — verifying the certificate that was wrongly refused, rejecting the duplicate link — each
leaving its own audit event, exactly as it would have done had it happened before the first draw.
Re-freezing and re-drawing are further deliberate acts.

Four steps where one would do, and deliberately. An "uphold" button that silently corrected records
and reissued a list would be a single call that rewrote a public lottery, and the only evidence of
what it had done would be whatever it chose to log.

**Superseding requires an upheld objection.** An authority able to re-draw at will can draw
repeatedly until it likes the answer, and the commitment ceremony would not catch it — every
individual draw would verify perfectly, because each one genuinely did. Requiring a challenge that
was accepted in writing makes replacing a result something that has to be asked for, adjudicated and
left in the trail.

An authority that finds its own error files its own objection and upholds it. That is not a
loophole; it is the paper trail working as intended.

**A draw may be superseded once.** Two draws both claiming to replace the same one would leave
nobody able to say which allotment stands.

**Objections may be filed by anybody.** An applicant objects about their own treatment; a journalist
who cannot reproduce the published root objects about the conduct of the draw, and has as much
standing to do so.

## Consequences

**Gained.** Every published result stays true forever, including the wrong ones. The history of a
scheme is a chain of draws each of which verifies, with the reason for each replacement recorded in
writing next to it. An applicant contesting an outcome can point at the objection, its adjudication,
the correction and the new draw as four separate dated facts.

**Given up.** Correcting one applicant's certificate means re-running the whole draw, which changes
the seed and therefore reshuffles everybody. Applicants who won under the first draw may lose under
the second through no fault of their own — and they will, because the lottery is genuinely random
each time.

That is a real cost and it is the correct one. The alternative is patching a single row, which means
the published result no longer matches its published inputs, which means the next person to verify
it finds a discrepancy and cannot tell an honest correction from a fraud. A scheme should expect to
draw once, and the way to make that true is to clear the verification queue before freezing rather
than to make corrections cheap afterwards.

**Not addressed.** Nothing here handles a surrendered flat, which needs the waitlist rather than a
re-draw, or a partial correction affecting one pool. Both are real and neither changes this
decision: they would be new operations on top of it, not exceptions to it.
