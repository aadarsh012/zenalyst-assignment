package com.zenalyst.housing.identity;

import java.util.UUID;

/**
 * Orders identifiers the way PostgreSQL does.
 *
 * <p>{@link UUID#compareTo} compares the two halves of the identifier as <em>signed</em> 64-bit
 * longs. PostgreSQL compares {@code uuid} values as <em>unsigned</em> bytes. The two therefore
 * disagree for any identifier whose leading bit is set — which is about half of all randomly
 * generated ones.
 *
 * <p>That disagreement is invisible until something depends on both orderings at once. Here,
 * {@code duplicate_review} constrains its pairs to be stored in ascending id order so that the
 * same two applications cannot be queued twice in opposite order; Java picks the order, and
 * PostgreSQL checks it. Using {@code UUID.compareTo} to pick it makes roughly half of all inserts
 * fail the constraint, non-deterministically, depending on which identifiers were generated.
 *
 * <p>Comparing the canonical lowercase hexadecimal form reproduces PostgreSQL's byte ordering
 * exactly, because the hex digits sort in the same order as the bytes they encode.
 */
public final class ApplicationIds {

    private ApplicationIds() {
    }

    public static int compare(UUID left, UUID right) {
        return left.toString().compareTo(right.toString());
    }

    /** A stable key for an unordered pair, independent of which way round it was supplied. */
    public static String pairKey(UUID left, UUID right) {
        return compare(left, right) <= 0
                ? left + ":" + right
                : right + ":" + left;
    }
}
