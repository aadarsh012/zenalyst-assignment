# 10. Fill open seats first, and carve horizontal reservations out of each pool

- **Status:** accepted
- **Phase:** 4

## Context

Six hundred flats are divided vertically — an open pool plus reserved pools for SC, ST, OBC and
EWS — and horizontally, by reservations for women, persons with disabilities, ex-servicepersons and
local residents that apply *within* every vertical pool.

Two questions decide whether the result is defensible, and both have an obvious wrong answer.

**In what order are the pools filled?** If the reserved pools are filled first, a strong SC
candidate consumes an SC seat. A weaker SC candidate who would have taken that seat is left out,
while an open seat the strong candidate had earned on merit goes to somebody ranked below them.
The reservation has become a ceiling: the reserved category ends up with exactly its quota and
never more, no matter how well its candidates do.

**Are horizontal reservations extra seats or a share of existing ones?** Treating thirty per cent
for women as thirty additional seats in a hundred-seat pool allots a hundred and thirty flats from
a hundred.

## Decision

**The open pool is filled first, from every eligible candidate regardless of category.** A
reserved-category candidate who ranks high enough consumes an open seat, and their category's
reserved seats remain fully available to others. This is the migration rule, and it is why a
category with four reserved seats can end up housing more than four of its applicants.

**Horizontal reservations are carved out of each pool, never added to it.** Thirty per cent of a
hundred seats means thirty of those hundred. Where too few qualifying candidates reach the merit
cutoff, the shortfall is met by the highest-ranked qualifying candidates below it, displacing the
lowest-ranked selectees.

Shares are rounded **half-up**, stated explicitly because the number has to be reproducible. Half of
six seats is three; thirty per cent of six is two, not one — rounding down would quietly erase a
small pool's reservation.

Displacement is implemented as *set-aside-then-fill* rather than *select-then-displace*. The two
produce the same selection: the seats a reservation claims are exactly the seats displacement would
free. The second needs a set of protected selectees to stop one reservation from undoing another,
and that bookkeeping is where the bugs live. Provenance is recomputed against the merit baseline
afterwards, so a candidate who would have won anyway is recorded as `MERIT` rather than miscredited
to a reservation they happened to qualify for — a distinction that is both factually and personally
significant.

**Displaced applicants are named in the result.** They were inside the merit cutoff and did not get
a flat, which gives them the strongest reason of anyone to ask what happened, and the answer must
not have to be reconstructed later.

**Local residence is modelled as a horizontal reservation** rather than as its own mechanism. A
share of each pool set aside for candidates with an attribute is exactly what it is, and a second
implementation of one idea is a second place for it to be wrong. A tie-break formulation was
considered and rejected: ordering is by 256-bit HMAC, so exact ties do not occur, and a policy that
never fires is worse than no policy because it looks like one.

## Consequences

**Gained.** Reservations act as floors rather than ceilings. Every seat carries its pool, its
basis, its rank and the ticket that produced it, so "why did they get one and I didn't" is
answerable per applicant rather than in aggregate. The allocator is a pure function of *(frozen
registry, rule version, seed)* — enforced by ArchUnit, not by convention — so anyone can re-run it.

**Given up.** The order in which horizontal reservations are processed affects who is selected when
several are short at once, so that order is part of the published rules and changes the rules hash.
That is more ceremony than a comment in a config file, and it is what makes the sequence something
an outsider can check rather than something they have to trust.

**Not addressed.** Every pool draws from one undifferentiated seat inventory. Real schemes allot
flats of different sizes and in different blocks, and preference between them is a further
allocation problem this system does not model. It would sit on top of this one rather than change
it: first who gets a flat, then which flat.
