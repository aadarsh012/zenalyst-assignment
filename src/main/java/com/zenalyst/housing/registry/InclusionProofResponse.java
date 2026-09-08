package com.zenalyst.housing.registry;

import java.util.List;

/**
 * Cryptographic evidence that one candidate was among the inputs to a draw.
 *
 * <p>{@code verified} is this service checking its own arithmetic, and is worth exactly nothing on
 * its own — a compromised server would happily return {@code true}. It is there as a smoke test.
 * The value of this response is {@code canonicalJson} and {@code proof}, with which the applicant
 * recomputes the root themselves and compares it with the one that was published.
 */
public record InclusionProofResponse(
        String applicationNo,
        String registryRoot,
        int leafIndex,
        int candidateCount,
        String canonicalJson,
        String leafHash,
        List<MerkleTree.ProofStep> proof,
        boolean verified) {

    public InclusionProofResponse {
        proof = List.copyOf(proof);
    }
}
