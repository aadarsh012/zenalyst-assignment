package com.zenalyst.housing.allocation;

import com.zenalyst.housing.intake.Category;

/**
 * The pools seats are drawn from, in the order they are filled.
 *
 * <p>{@link #OPEN} is not a category. It is the unreserved pool, and <em>every</em> eligible
 * candidate competes for it regardless of what category they belong to. That is the whole of the
 * migration rule: a reserved-category candidate who wins an open seat on merit consumes an open
 * seat, and their category's reserved seats remain fully available to others.
 *
 * <p>Filling OPEN first is therefore not an implementation detail. Filling the reserved pools
 * first would let a strong SC candidate consume an SC seat that a weaker SC candidate needed,
 * while an open seat they had earned went to someone ranked below them — which converts a
 * reservation into a ceiling.
 */
public enum SeatPool {

    /** Unreserved. Open to everyone on merit. Filled first. */
    OPEN(null),

    SC(Category.SC),
    ST(Category.ST),
    OBC(Category.OBC),
    EWS(Category.EWS);

    private final Category category;

    SeatPool(Category category) {
        this.category = category;
    }

    /** The category this pool is reserved for, or {@code null} for {@link #OPEN}. */
    public Category category() {
        return category;
    }

    public boolean isReserved() {
        return category != null;
    }

    /** Whether a candidate may compete for this pool at all. */
    public boolean admits(AllocationCandidate candidate) {
        return category == null || candidate.category() == category;
    }
}
