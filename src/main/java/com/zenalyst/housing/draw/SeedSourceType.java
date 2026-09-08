package com.zenalyst.housing.draw;

/**
 * Where a draw's randomness comes from.
 *
 * <p>The two differ in what the authority could do if it wanted to cheat, which is the only axis
 * that matters. See ADR-0011.
 */
public enum SeedSourceType {

    /**
     * The authority generates a seed, publishes a hash of it, and reveals it afterwards.
     *
     * <p>Proves the seed was fixed before it was revealed. Does <em>not</em> prove it was not
     * chosen: an authority could generate a thousand seeds, run the draw against each, and publish
     * the commitment for whichever produced the list it wanted. Detecting that from outside is
     * impossible, because every step looks correct.
     */
    AUTHORITY_COMMITTED,

    /**
     * The seed is a future round of a public randomness beacon, whose round number is published in
     * advance.
     *
     * <p>The authority cannot know the value, so it cannot shop for one. It closes the hole the
     * option above leaves open, at the cost of depending on a third party being reachable.
     */
    DRAND_BEACON
}
