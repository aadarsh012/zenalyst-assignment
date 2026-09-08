package com.zenalyst.housing.allocation;

/** A candidate with the ticket that fixed their place in the draw. */
public record RankedCandidate(AllocationCandidate candidate, String ticket, int overallRank) {

    public String applicationNo() {
        return candidate.applicationNo();
    }
}
