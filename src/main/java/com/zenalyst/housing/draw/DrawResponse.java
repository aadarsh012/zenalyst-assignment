package com.zenalyst.housing.draw;

import java.time.Instant;

/**
 * A draw as published.
 *
 * <p>The seed and salt are withheld while the draw is {@code COMMITTED}. A hash-committed draw
 * necessarily holds its seed from the moment it is created — a commitment has to commit to
 * something — and this record is the single place that distinction is enforced. Returning it early
 * would defeat the entire ceremony.
 *
 * @param verification plain-English instructions for checking the commitment without our help
 */
public record DrawResponse(
        String drawId,
        String schemeCode,
        DrawStatus status,
        String registryRoot,
        String rulesVersion,
        String rulesHash,
        SeedSourceType seedSource,
        String seedCommitment,
        Long beaconRound,
        String seed,
        String seedSalt,
        Integer seatsAwarded,
        String resultHash,
        Instant committedAt,
        String committedBy,
        Instant revealedAt,
        Instant executedAt,
        Instant publishedAt,
        String publishedBy,
        String failureReason,
        String verification) {

    static DrawResponse of(Draw draw, String schemeCode, String rulesVersion) {
        boolean seedIsPublic = draw.getStatus() != DrawStatus.COMMITTED;

        return new DrawResponse(
                draw.getId().toString(), schemeCode, draw.getStatus(),
                draw.getRegistryRoot(), rulesVersion, draw.getRulesHash(),
                draw.getSeedSource(), draw.getSeedCommitment(), draw.getBeaconRound(),
                seedIsPublic ? draw.getSeed() : null,
                seedIsPublic ? draw.getSeedSalt() : null,
                draw.getSeatsAwarded(), draw.getResultHash(),
                draw.getCommittedAt(), draw.getCommittedBy(), draw.getRevealedAt(),
                draw.getExecutedAt(), draw.getPublishedAt(), draw.getPublishedBy(),
                draw.getFailureReason(),
                verificationInstructions(draw, seedIsPublic));
    }

    private static String verificationInstructions(Draw draw, boolean seedIsPublic) {
        if (draw.getSeedSource() == SeedSourceType.DRAND_BEACON) {
            return ("The seed is drand round %d. Fetch https://api.drand.sh/public/%d and compare its "
                    + "randomness with the seed shown here. The round number was published before the "
                    + "round existed, so it could not have been chosen for its result.")
                    .formatted(draw.getBeaconRound(), draw.getBeaconRound());
        }
        return seedIsPublic
                ? "Check that SHA-256(seed + \":\" + seedSalt) equals the seedCommitment published "
                        + "when this draw was created."
                : "The seed is not public yet. When it is revealed, check that "
                        + "SHA-256(seed + \":\" + seedSalt) equals the seedCommitment shown here.";
    }
}
