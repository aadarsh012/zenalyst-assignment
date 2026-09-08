package com.zenalyst.housing.registry;

import java.time.Instant;

/**
 * What gets published when a register is frozen.
 *
 * <p>These eight fields are the announcement. {@code registryRoot} and {@code rulesHash} are the
 * two that matter to a sceptic: the first commits the authority to a candidate list, the second to
 * the thresholds those candidates were judged by.
 */
public record FreezeResponse(
        String schemeCode,
        String registryId,
        String registryRoot,
        String rulesHash,
        int candidateCount,
        int eligibleCount,
        Instant frozenAt,
        String frozenBy) {
}
