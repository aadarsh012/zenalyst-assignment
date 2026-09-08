# 11. Commit to the seed before revealing it — and why a beacon is stronger

- **Status:** accepted
- **Phase:** 5

## Context

The draw is a lottery. Two accusations will be made about it, and they are different accusations.

**"You changed the seed once you saw the result."** Answered by committing to the seed before
revealing it: publish `SHA-256(seed ‖ salt)` when the draw is created, publish the seed afterwards,
and anybody can check the two agree.

**"You chose the seed."** Not answered by that at all. The register is frozen before the seed is
committed, so an authority can run the draw locally against a thousand candidate seeds, see which
produces the list it prefers, and publish the commitment for that one. Every published artefact
verifies. Every step looks correct. The commitment proves only that the seed did not change after
publication — never that it was not selected before.

That gap cannot be closed by care on the authority's side. It is closed by taking the choice away.

## Decision

Both mechanisms exist behind one interface, and the difference is stated rather than glossed.

**`AUTHORITY_COMMITTED`** (default). The authority generates 256 bits of seed and 128 bits of salt,
publishes `SHA-256(seed ‖ ":" ‖ salt)`, and reveals the pair after the register is frozen. The salt
matters: without it the commitment is `SHA-256(seed)` and anyone able to enumerate the seed space
can confirm a guess offline. The separator matters too — `"ab"+"cd"` and `"a"+"bcd"` must not
commit to the same thing.

**`DRAND_BEACON`**. At commit time the authority publishes a *round number* of the League of
Entropy's public randomness beacon — a round roughly ten minutes in the future. It cannot know what
that round will contain, so there is nothing to shop for. At reveal time the round exists, its value
is public, and the authority reads it exactly as anybody else can. Verification requires nothing
from this system: fetch `https://api.drand.sh/public/{round}` and compare.

The seed is withheld until reveal by the response type itself, not by remembering to. A
hash-committed draw necessarily *holds* its seed from the moment it is created, so exactly one place
decides whether it is public, and that place is `DrawResponse`.

The ceremony has four steps because each boundary removes a way of choosing an outcome rather than
discovering one: **commit** fixes the register and rules; **reveal** discloses the seed after the
list can no longer change; **execute** computes; **publish** makes it final.

## Consequences

**Gained.** With the beacon, the authority cannot influence the outcome even in principle. With the
hash commitment, it cannot change it after the fact. Which of the two is in force is recorded on
every draw, so the strength of the guarantee is visible rather than assumed.

**Given up.** The beacon makes the ceremony depend on a third party being reachable at two moments.
If drand is down at commit time the draw cannot start; if it is down at reveal time the draw cannot
proceed. Refusing is the correct response — a draw that invented a seed because a beacon was
unreachable would be worse than a draw that waited — but it is a real operational dependency, and it
is why the default is the weaker mechanism that works offline.

**Stated, not fixed.** Running `AUTHORITY_COMMITTED` in a real scheme leaves the seed-selection hole
open. A scheme where the outcome genuinely matters should run `DRAND_BEACON`, or hold the ceremony
in public with a physical source of randomness. Shipping the weaker option as the default and
describing it as sufficient would be the dishonest choice; shipping it while naming what it does not
prove is the honest one.
