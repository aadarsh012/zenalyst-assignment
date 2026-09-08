# 9. Freeze the register, and publish its root, before any seed exists

- **Status:** accepted
- **Phase:** 3

## Context

The draw will be challenged. Two accusations are easy to make and, without preparation,
impossible to answer.

The first: *somebody was added, removed, or reclassified after the fact.* If the draw ran against
"the register" — a live table that changes as applications are corrected and certificates are
verified — then nobody, including the authority, can say afterwards precisely which rows were
present when the lottery was drawn.

The second: *the authority chose the outcome.* If the candidate list could still change after a
seed was known, an official could try seeds against candidate sets until one produced a
satisfactory list.

## Decision

The register is **frozen** before the draw. A freeze snapshots every application together with the
eligibility conclusions about it, sorts the rows by application number, renders each to canonical
JSON, and folds them into a Merkle tree. The root is published, along with a hash of the
eligibility rules in force.

**Ordering is the point.** The root is published before any seed exists. An authority that could
still change the candidate list after seeing the seed could choose the outcome; one that has
already committed to the list cannot.

The rules hash answers a second question the root does not. The root proves *who* was in the draw;
the rules hash proves *what they were judged by*. Publishing both makes "you raised the income
limit afterwards" answerable.

Freezing unchanged data twice produces an identical root. That reproducibility is the property
being relied on, not a pleasant accident — it is what lets anyone holding the published rows
recompute the root and get ours.

The tree follows RFC 6962: leaves prefixed `0x00`, internal nodes `0x01`, and an odd node promoted
rather than duplicated. Both details are defences, not decoration. Without domain separation, a
leaf whose bytes happened to be two concatenated digests hashes identically to the node above
them, letting a differently-shaped tree pass as the original. Duplicating odd nodes is the more
common implementation and carries Bitcoin's CVE-2012-2459 flaw, under which two distinct candidate
lists collapse to one root — which would destroy the uniqueness the commitment depends on.

Every candidate gets an **inclusion proof**: around twelve hashes for a register of four thousand,
with which one applicant verifies their own row was among the inputs without downloading the
register and without trusting this service.

The frozen rows carry only what the allocator consumes — application number, effective category,
gender, the horizontal flags, eligibility and its reason codes. No names, addresses, contact
details or identity tokens. The table is meant to be publishable in full, which is only safe to
intend if it contains nothing that should not be published.

Ineligible applicants are frozen too, with their reason codes, rather than omitted. Four thousand
people applied and the published register must account for all four thousand; a register that
silently contains fewer rows than there were applicants invites exactly the question it cannot
answer.

## Consequences

**Gained.** The draw becomes a function of one snapshot and one seed. Both accusations above stop
being matters of trust and become matters of arithmetic. An applicant can prove their own inclusion
from a printed page.

**Given up.** A correction discovered after freezing cannot be quietly applied — it requires a new
freeze, with a new root, and the difference between the two roots is public. That is more friction
than editing a row, and the friction is the feature. Phase 7's objection process is the sanctioned
route for exactly this.

Also given up: the frozen register is a copy, so a scheme frozen several times stores its
candidates several times over. At four thousand rows this is negligible, and it is what makes each
freeze independently verifiable rather than reconstructed from a diff.
