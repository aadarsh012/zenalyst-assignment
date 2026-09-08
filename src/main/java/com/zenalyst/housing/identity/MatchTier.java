package com.zenalyst.housing.identity;

/**
 * The tiers at which two applications can be recognised as the same person, in descending order
 * of certainty.
 *
 * <p>{@link #autoMerges} is the important property. The first three tiers rest on an exact match
 * of something the applicant supplied deliberately, so the system merges them without asking.
 * {@link #PROBABLE} rests on two strings looking alike, and never merges on its own — see
 * ADR-0006.
 */
public enum MatchTier {

    /**
     * The same national identity number. As close to certainty as this system gets: the number is
     * unique to a person, checksum-validated on arrival, and not something two different people
     * mistype into agreement.
     */
    GOVERNMENT_ID(true, "same identity number"),

    /**
     * Same normalised name, same date of birth, same phone number. Two people can share a name
     * and, rarely, a birthday; sharing a mobile number as well means one household at minimum,
     * and in practice one person applying twice.
     */
    NAME_DOB_PHONE(true, "same name, date of birth and phone number"),

    /**
     * Same normalised name, same date of birth, same email mailbox. Equivalent strength to the
     * phone tier, and it catches the applicant who used one channel on Monday and the other on
     * Friday.
     */
    NAME_DOB_EMAIL(true, "same name, date of birth and email address"),

    /**
     * Similar name, same date of birth. Enough to be worth a human's attention and never enough
     * to act on automatically.
     */
    PROBABLE(false, "similar name and same date of birth");

    private final boolean autoMerges;
    private final String description;

    MatchTier(boolean autoMerges, String description) {
        this.autoMerges = autoMerges;
        this.description = description;
    }

    /** Whether a match at this tier may be acted on without a human confirming it. */
    public boolean autoMerges() {
        return autoMerges;
    }

    /** Plain-English reason, shown to applicants and operators rather than a tier name. */
    public String description() {
        return description;
    }
}
