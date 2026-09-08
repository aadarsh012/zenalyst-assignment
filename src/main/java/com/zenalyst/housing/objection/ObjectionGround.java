package com.zenalyst.housing.objection;

/**
 * What an objection says went wrong.
 *
 * <p>A fixed list rather than free text, because the grounds determine what a remedy would have to
 * be, and because "how many objections were about duplicate matching?" is a question an authority
 * should be able to answer about its own scheme.
 */
public enum ObjectionGround {

    /** The applicant says a rule was applied to them incorrectly. */
    ELIGIBILITY_WRONGLY_ASSESSED,

    /** A certificate was refused that should have been accepted. */
    CATEGORY_CLAIM_WRONGLY_REFUSED,

    /**
     * Two different people were merged into one. The most serious of the identity grounds: it means
     * somebody's application was set aside as a copy of a stranger's.
     */
    WRONGLY_TREATED_AS_DUPLICATE,

    /** An application was made but never appeared among the frozen candidates. */
    APPLICATION_MISSING_FROM_REGISTER,

    /**
     * The draw itself, rather than one applicant's treatment. Anybody may raise this — a journalist
     * who cannot reproduce the published root has as much standing as an applicant.
     */
    DRAW_IMPROPERLY_CONDUCTED,

    OTHER
}
