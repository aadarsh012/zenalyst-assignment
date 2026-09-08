package com.zenalyst.housing.identity;

import java.time.Instant;
import java.util.List;

/**
 * What a deduplication pass concluded.
 *
 * @param applicationsExamined every application in the scheme, duplicates included
 * @param distinctApplicants   how many actual people those applications represent
 * @param duplicateGroups      people who applied more than once
 * @param applicationsLinked   applications now excluded from the draw as duplicates
 * @param reviewsRaised        fuzzy pairs newly put in front of a human by this run
 * @param reviewsPending       fuzzy pairs still awaiting a decision, this run's included
 */
public record DeduplicationReport(
        String schemeCode,
        Instant completedAt,
        int applicationsExamined,
        int distinctApplicants,
        int exactMatchPairs,
        int duplicateGroups,
        int applicationsLinked,
        int reviewsRaised,
        int reviewsPending,
        List<DuplicateGroupSummary> groups) {

    public DeduplicationReport {
        groups = List.copyOf(groups);
    }
}
