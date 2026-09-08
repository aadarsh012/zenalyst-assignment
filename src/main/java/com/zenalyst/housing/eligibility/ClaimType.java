package com.zenalyst.housing.eligibility;

/**
 * The things an applicant declares about themselves that change which pool they compete in.
 *
 * <p>Each is a claim until an operator has seen the document behind it. An unverified claim costs
 * the applicant the benefit they claimed, never their place in the draw.
 */
public enum ClaimType {

    /** Reserved-category certificate. Unverified means competing as GEN. */
    CATEGORY,

    /** Proof of residence in the area. Unverified means no local preference. */
    LOCAL_RESIDENCE,

    /** Disability certificate, for the horizontal reservation. */
    DISABILITY,

    /** Service record, for the ex-servicepersons' horizontal reservation. */
    EX_SERVICE,

    /** Income certificate. Required for an EWS claim to stand. */
    INCOME
}
