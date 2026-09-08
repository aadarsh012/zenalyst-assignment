package com.zenalyst.housing.identity;

import java.time.Instant;
import java.util.List;

/**
 * The answer to "is this application competing for a flat, and if not, why not?".
 *
 * <p>Written for the applicant rather than for an operator. Someone who submitted twice and
 * received two application numbers will eventually look up the one that was set aside, and they
 * are owed a specific reason and a pointer to the application that survived — not a status of
 * {@code DUPLICATE} with nothing behind it.
 */
public record IdentityResponse(
        String applicationNo,
        Role role,
        String reason,
        String canonicalApplicationNo,
        MatchTier matchedAtTier,
        Instant linkedAt,
        List<Duplicate> duplicates,
        List<PendingReview> pendingReviews) {

    public enum Role {
        /** This application stands for the person and competes in the draw. */
        CANONICAL,
        /** This application was found to be another submission by the same person. */
        DUPLICATE
    }

    /** An application linked to this one, when this one is the canonical. */
    public record Duplicate(String applicationNo, MatchTier tier, String reason, Instant linkedAt) {
    }

    /** A fuzzy match involving this application that a human has not yet decided. */
    public record PendingReview(String reviewId, String otherApplicationNo, String similarity) {
    }
}
