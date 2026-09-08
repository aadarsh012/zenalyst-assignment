package com.zenalyst.housing.draw;

import java.util.Optional;

/**
 * Reads the drand public randomness beacon.
 *
 * <p>An interface because tests must not depend on a third party's uptime, and because a
 * deployment may prefer a different beacon — the NIST one, or a locally operated League of Entropy
 * node — without anything else changing.
 */
public interface DrandClient {

    /** The most recent round the beacon has produced. */
    long latestRound();

    /**
     * The randomness of one round.
     *
     * @return empty if that round has not happened yet. A round in the future has no value, and
     *         returning something rather than nothing would be the one failure mode this whole
     *         mechanism exists to prevent.
     */
    Optional<String> randomnessAt(long round);
}
