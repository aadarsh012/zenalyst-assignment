package com.zenalyst.housing.draw;

/**
 * The stages of a draw. Movement is forwards only.
 *
 * <p>The order encodes the ceremony. A seed cannot be revealed before it has been committed to; a
 * draw cannot execute before its seed is known; a result cannot be published before it exists. Each
 * of those would be a way of choosing an outcome rather than discovering one.
 */
public enum DrawStatus {

    /** A register, a rule version and a commitment are fixed. Nobody knows the seed yet. */
    COMMITTED,

    /** The seed is public. Anyone can now check it against the commitment. */
    REVEALED,

    /** The allocation is being computed. */
    RUNNING,

    /** The result exists and is readable, but has not been declared final. */
    COMPLETED,

    /** Final. Nothing about this draw may change again, enforced by a database trigger. */
    PUBLISHED,

    /** Execution failed. The result was rolled back entirely; the failure reason is recorded. */
    FAILED
}
