# 8. An unverified claim costs the benefit, never the place

- **Status:** accepted
- **Phase:** 3

## Context

Applicants declare things about themselves that change which pool they compete in: a reserved
category, residence in the area, a disability, military service, an income within the EWS ceiling.
Each is backed by a certificate that somebody has to look at.

At the point the draw runs, those certificates will be in three states. Some verified. Some
examined and refused. And — for a scheme with four thousand applicants and a finite number of
clerks — a great many simply not yet reached.

The obvious model has two states: verified, or not. Under it, an applicant whose certificate is
sitting in a pile is treated identically to one whose certificate was examined and refused, and
both are disqualified. The scheme is then allocating flats partly on the basis of its own
administrative backlog, and the people it excludes are disproportionately those who applied late,
at a busy counter, in a busy ward.

## Decision

Two kinds of rule, and only the first can remove anyone from the draw.

**Disqualifying rules** are objective and few: below the minimum age, or a duplicate of another
application by the same person. Failing one means not competing.

**Claim rules** decide which pool an applicant competes in. Failing one costs them the benefit
they claimed and nothing else. An unverified category certificate means competing on open merit as
GEN. An unverified residence proof means no local preference. The applicant still competes.

Three claim states are recorded, not two: `PASS`, `FAIL`, and `NOT_VERIFIED`. The last is distinct
on purpose — an applicant whose document nobody has read has done nothing wrong, and the record
should not imply they have. The eligibility response names the outstanding documents, so "why am I
in the general category?" has an answer the applicant can act on.

An EWS claim requires both a declared income within the ceiling and a verified income certificate.
Income over the limit is not a disqualification either; it drops the applicant to GEN.

Every decision is a list of checks with stable codes and plain-English detail. Never a bare
boolean: an applicant told only "not eligible" has nothing to check, nothing to correct, and
nothing to appeal against.

Age is measured at the scheme's **closing date**, not at the moment the evaluation runs. Otherwise
an applicant who turned eighteen during processing would be eligible or not according to the order
their file was picked up in.

## Consequences

**Gained.** Administrative delay cannot disqualify anyone. The distinction between "refused" and
"not yet examined" survives into the published record. Verification can continue right up to the
freeze, with each verified certificate simply moving that applicant into the pool they proved they
belong in.

**Given up.** An applicant whose certificate is genuinely valid but unprocessed at the moment of
the freeze competes on open merit rather than on their reservation. That is a real cost borne by a
real person, and it is smaller than the alternative — being excluded outright — but it is not zero.
It puts an obligation on the authority to clear the verification queue before freezing, and the
`claimsAwaitingVerification` field exists so that obligation is visible rather than implicit.

**Not addressed.** Nothing here verifies that an applicant does not already own property. That
needs a property register this system has no access to, and a self-declaration nobody checks would
be theatre. It is better to state the gap than to simulate the control.
