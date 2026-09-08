package com.zenalyst.housing.intake;

/** How an application reached the system. */
public enum ApplicationChannel {

    /** Submitted directly by the applicant. Submission and recording are the same instant. */
    ONLINE,

    /**
     * Handed in on paper at a counter and typed in later. Submission and recording are different
     * instants, sometimes weeks apart, and only the first one bears on the deadline.
     */
    PAPER
}
