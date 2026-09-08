package com.zenalyst.housing.identity;

import java.time.Instant;
import java.util.UUID;

/**
 * The minimum an application needs to expose to be deduplicated: who it is, and where it sits in
 * the ordering that decides which of several duplicates is the real one.
 *
 * <p><strong>The canonical application is the earliest submission, ties broken by the lower
 * application number.</strong> That rule is arbitrary in the sense that some rule was needed, and
 * deliberate in every other sense. It is deterministic, so re-running deduplication cannot change
 * who won. It is explicable to an applicant in one sentence. And it favours the first attempt,
 * which is the one the applicant made before they started worrying about whether it had gone
 * through.
 *
 * <p>Note that for paper applications this orders by <em>when the form was handed in</em>, not
 * when it was typed up — so an applicant whose paper form was submitted first keeps priority over
 * their own later online resubmission, even though the online one reached the database sooner.
 */
public record ApplicationRef(UUID id, String applicationNo, Instant submittedAt)
        implements Comparable<ApplicationRef> {

    @Override
    public int compareTo(ApplicationRef other) {
        int bySubmission = submittedAt.compareTo(other.submittedAt);
        return bySubmission != 0 ? bySubmission : applicationNo.compareTo(other.applicationNo);
    }
}
