package com.zenalyst.housing.identity;

import java.util.List;

/** One person and the applications of theirs that were set aside. */
public record DuplicateGroupSummary(
        String canonicalApplicationNo,
        List<LinkedApplication> duplicates) {

    public DuplicateGroupSummary {
        duplicates = List.copyOf(duplicates);
    }

    /**
     * @param reason plain English, because this is what an applicant is shown when they ask why
     *               one of their applications was set aside
     */
    public record LinkedApplication(String applicationNo, MatchTier tier, String reason) {
    }
}
