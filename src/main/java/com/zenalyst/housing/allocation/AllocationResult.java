package com.zenalyst.housing.allocation;

import java.util.List;
import java.util.Map;

/**
 * The complete outcome of a draw.
 *
 * <p>Includes the full merit order, not only the winners. Three and a half thousand people will
 * not get a flat, and each of them is entitled to know where they came — which is impossible to
 * answer later from a list of six hundred names.
 */
public record AllocationResult(
        String seed,
        String rulesHash,
        int totalSeats,
        int eligibleCandidates,
        List<SeatAward> awards,
        List<PoolOutcome> pools,
        List<RankedCandidate> meritOrder) {

    public AllocationResult {
        awards = List.copyOf(awards);
        pools = List.copyOf(pools);
        meritOrder = List.copyOf(meritOrder);
    }

    public Map<String, SeatAward> awardsByApplication() {
        return awards.stream().collect(java.util.stream.Collectors.toMap(
                SeatAward::applicationNo, award -> award,
                (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
