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

    /**
     * A single value standing for the entire allotment.
     *
     * <p>Published with the result so that a recomputation can be compared in one line rather than
     * by diffing six hundred rows by eye. Defined as the SHA-256 of the canonical JSON array of
     * every award — application number, pool, basis and rank — in published order.
     *
     * <p>Deliberately covers the awards only. The merit order is derivable from the seed and the
     * frozen register by anybody, so hashing it would add length without adding commitment.
     */
    public String resultHash() {
        com.fasterxml.jackson.databind.node.ArrayNode array =
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode();
        for (SeatAward award : awards) {
            com.fasterxml.jackson.databind.node.ObjectNode node = array.addObject();
            node.put("applicationNo", award.applicationNo());
            node.put("basis", award.basis().name());
            node.put("pool", award.pool().name());
            node.put("poolRank", award.poolRank());
        }
        return com.zenalyst.housing.platform.hash.Hashing.sha256Hex(
                com.zenalyst.housing.platform.hash.CanonicalJson.render(array));
    }

    public Map<String, SeatAward> awardsByApplication() {
        return awards.stream().collect(java.util.stream.Collectors.toMap(
                SeatAward::applicationNo, award -> award,
                (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
