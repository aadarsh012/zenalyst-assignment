package com.zenalyst.housing.allocation;

import java.util.List;

/**
 * What happened in one pool.
 *
 * @param cutoffPoolRank the rank of the lowest-ranked candidate selected on merit, or 0 if none
 *                       were. This is the number an unsuccessful applicant actually wants: "you
 *                       were 412th and the cutoff was 87th" is an answer.
 * @param displaced      candidates who were inside the merit cutoff and were nonetheless not
 *                       selected, because a horizontal reservation was short. Recorded because
 *                       they are the people with the strongest reason to ask, and the answer must
 *                       not have to be reconstructed later.
 * @param waitlist       the remaining competitors in merit order, so a surrender has an
 *                       unarguable successor
 */
public record PoolOutcome(
        SeatPool pool,
        int seats,
        int awarded,
        int competitors,
        int cutoffPoolRank,
        List<HorizontalOutcome> horizontal,
        List<String> displaced,
        List<String> waitlist) {

    public PoolOutcome {
        horizontal = List.copyOf(horizontal);
        displaced = List.copyOf(displaced);
        waitlist = List.copyOf(waitlist);
    }
}
