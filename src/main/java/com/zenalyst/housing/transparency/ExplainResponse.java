package com.zenalyst.housing.transparency;

import com.zenalyst.housing.eligibility.EligibilityDecision;
import com.zenalyst.housing.registry.MerkleTree;
import java.util.List;

/**
 * The complete account of what happened to one application.
 *
 * <p>This is the answer to "why not me?", and it is the reason the rest of the system is built the
 * way it is. Every earlier phase exists so that this response can be assembled from stored facts
 * rather than reconstructed, argued for, or apologised for.
 *
 * <p>It is written for the applicant. {@code summary} says in one paragraph what happened;
 * everything below it is the working, in the order somebody would want to check it — was I in the
 * draw, was I eligible, was my row among the inputs, where did I come, and what was the cutoff.
 *
 * @param outcome        what happened, in a word
 * @param summary        what happened, in a paragraph an applicant can read
 * @param verifyYourself instructions for checking the parts of this that do not require trusting us
 */
public record ExplainResponse(
        String applicationNo,
        Outcome outcome,
        String summary,
        Identity identity,
        Eligibility eligibility,
        RegistryEntry registry,
        DrawSummary draw,
        Lottery lottery,
        List<PoolStanding> pools,
        Allotment allotment,
        Waitlist waitlist,
        List<String> verifyYourself) {

    public ExplainResponse {
        pools = List.copyOf(pools);
        verifyYourself = List.copyOf(verifyYourself);
    }

    public enum Outcome {
        /** A flat was allotted. */
        ALLOTTED,
        /** No flat, but in line for one if somebody surrenders. */
        WAITLISTED,
        /** In the draw, and did not come high enough in any pool. */
        NOT_SELECTED,
        /** Not in the draw: found ineligible, or superseded as a duplicate. */
        NOT_IN_DRAW,
        /** The scheme has not drawn yet. */
        NO_DRAW_YET
    }

    /** Whether this application stands for the applicant, or another of theirs does. */
    public record Identity(
            boolean isCanonical,
            String canonicalApplicationNo,
            String matchedAtTier,
            int otherSubmissionsFound) {
    }

    public record Eligibility(
            boolean eligible,
            String declaredCategory,
            String effectiveCategory,
            boolean localResident,
            boolean disability,
            boolean exServiceperson,
            List<EligibilityDecision.Check> checks) {

        public Eligibility {
            checks = List.copyOf(checks);
        }
    }

    /**
     * Proof that this applicant's row was among the inputs to the draw.
     *
     * @param inclusionProof the sibling hashes needed to fold {@code canonicalJson} up to
     *                       {@code registryRoot} — checkable without this service
     */
    public record RegistryEntry(
            String registryRoot,
            int leafIndex,
            int candidateCount,
            String canonicalJson,
            String leafHash,
            List<MerkleTree.ProofStep> inclusionProof) {

        public RegistryEntry {
            inclusionProof = List.copyOf(inclusionProof);
        }
    }

    public record DrawSummary(
            String drawId,
            String status,
            String rulesVersion,
            String rulesHash,
            String seedSource,
            String seedCommitment,
            Long beaconRound,
            String seed,
            String resultHash) {
    }

    /**
     * Where this applicant came in the draw, and how to recompute it.
     *
     * @param formula the exact expression, so the rank can be reproduced from the published seed
     */
    public record Lottery(
            String ticket,
            String formula,
            int overallRank,
            int candidatesInDraw) {
    }

    /**
     * How this applicant fared in one pool they competed in.
     *
     * <p>{@code cutoffPoolRank} is the number that actually answers the question. "You were 412th
     * and the last seat went to 87th" is an answer; "you were not selected" is not.
     */
    public record PoolStanding(
            String pool,
            int poolRank,
            int seats,
            int awarded,
            int competitors,
            int cutoffPoolRank,
            boolean selected,
            String note) {
    }

    public record Allotment(String pool, String basis, int poolRank, String horizontalCategory) {
    }

    public record Waitlist(String pool, int position, int poolRank, int aheadOfYou) {
    }
}
