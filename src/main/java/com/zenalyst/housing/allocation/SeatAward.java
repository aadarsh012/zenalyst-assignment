package com.zenalyst.housing.allocation;

/**
 * One flat, and the complete account of why this applicant got it.
 *
 * <p>Every field exists to answer a question somebody will ask. Which pool, because "you were
 * allotted under SC" and "you were allotted on open merit" are different claims. What basis,
 * because an applicant displaced by a horizontal top-up is entitled to know that is what happened.
 * Which rank, because "you were 87th and there were 90 seats" is an answer and "you were selected"
 * is not.
 *
 * @param poolRank    position within this pool's competitors
 * @param overallRank position in the whole draw, across every candidate
 * @param ticket      the HMAC that produced the ranking, so it can be recomputed
 */
public record SeatAward(
        String applicationNo,
        SeatPool pool,
        Basis basis,
        HorizontalCategory horizontalCategory,
        int poolRank,
        int overallRank,
        String ticket) {

    public enum Basis {
        /** Ranked high enough in this pool to be selected without any reservation being applied. */
        MERIT,
        /**
         * Selected because a horizontal reservation in this pool was short. Ranked below the pool's
         * merit cutoff, and named as such rather than presented as having come top.
         */
        HORIZONTAL_TOP_UP
    }
}
