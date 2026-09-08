package com.zenalyst.housing.identity;

/** Where a fuzzy match has got to. */
public enum ReviewStatus {

    /** Raised by the fuzzy tier, waiting for a human. Affects nothing until decided. */
    PENDING,

    /** A human confirmed the two applications are one person. They are now linked. */
    CONFIRMED_DUPLICATE,

    /**
     * A human decided they are two different people. Recorded permanently so that re-running
     * deduplication does not put the same pair back in front of the same person next week.
     */
    NOT_DUPLICATE
}
