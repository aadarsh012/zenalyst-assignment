package com.zenalyst.housing.identity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Two applications whose names resemble each other and whose dates of birth agree.
 *
 * <p>Carries the names themselves, not just the score. The operator deciding this is looking at
 * two strings and asking whether they are one person; a similarity of 0.87 tells them nothing on
 * its own, whereas "RAMESH CHANDRA KUMAR" against "RAMESH CHANDRA KUMARR" tells them almost
 * everything.
 */
public record FuzzyCandidate(
        UUID applicationAId,
        String applicationNoA,
        String nameKeyA,
        UUID applicationBId,
        String applicationNoB,
        String nameKeyB,
        LocalDate dateOfBirth,
        BigDecimal similarity) {

    /**
     * The same pair with the lower id first.
     *
     * <p>{@code duplicate_review} stores its pairs in id order and constrains them that way, so
     * that one pair of applications can never be queued twice in opposite order. The matching
     * query orders by application number instead, because ids are random and ordering on them
     * would make results differ between runs. Those two orderings disagree, and this is where
     * they are reconciled — so that the stored names still line up with the stored ids.
     *
     * <p>The comparison goes through {@link ApplicationIds}, not {@code UUID.compareTo}, for
     * reasons documented there.
     */
    public FuzzyCandidate inIdOrder() {
        if (ApplicationIds.compare(applicationAId, applicationBId) <= 0) {
            return this;
        }
        return new FuzzyCandidate(
                applicationBId, applicationNoB, nameKeyB,
                applicationAId, applicationNoA, nameKeyA,
                dateOfBirth, similarity);
    }
}
