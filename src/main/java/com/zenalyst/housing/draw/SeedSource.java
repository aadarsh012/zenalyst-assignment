package com.zenalyst.housing.draw;

/**
 * Supplies a draw's randomness in two steps: a public commitment now, and the seed itself later.
 *
 * <p>The split is the entire mechanism. Between the two calls the candidate register is already
 * frozen and its root already published, so by the time anybody learns the seed, the set of people
 * it will be applied to can no longer change.
 */
public interface SeedSource {

    SeedSourceType type();

    /**
     * Produces what is published when a draw is created.
     *
     * @return the commitment, plus any secret this source needs kept until reveal
     */
    Commitment commit();

    /**
     * Discloses the seed.
     *
     * @throws com.zenalyst.housing.platform.error.ApiException if the seed is not yet available —
     *         a beacon round that has not happened cannot be revealed early, and saying so is
     *         better than inventing a value
     */
    String reveal(Draw draw);

    /**
     * What a draw records at commit time.
     *
     * @param commitmentHash published now, meaningless until the seed is revealed; null for sources
     *                       that commit to something other than a hash
     * @param beaconRound    published now, and knowable by anyone; null for hash commitments
     * @param seed           kept until reveal; null for sources where nothing is known yet
     * @param salt           kept until reveal, so the commitment cannot be brute-forced from a
     *                       small seed space
     */
    record Commitment(String commitmentHash, Long beaconRound, String seed, String salt) {
    }
}
