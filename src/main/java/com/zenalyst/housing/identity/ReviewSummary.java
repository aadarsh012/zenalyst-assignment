package com.zenalyst.housing.identity;

import java.time.Instant;

/**
 * One item in the operator's review queue.
 *
 * <p>Carries the two names and the shared date of birth, not just identifiers. The decision this
 * queue exists for is made by looking at two names side by side; a list of UUIDs and scores would
 * force the operator to open two records for every row.
 */
public record ReviewSummary(
        String reviewId,
        String applicationNoA,
        String applicationNoB,
        String nameA,
        String nameB,
        String dateOfBirth,
        String similarity,
        ReviewStatus status,
        Instant raisedAt,
        Instant decidedAt,
        String decidedBy,
        String decisionNote) {
}
