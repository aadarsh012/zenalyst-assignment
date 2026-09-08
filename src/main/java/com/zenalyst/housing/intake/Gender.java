package com.zenalyst.housing.intake;

/**
 * Recorded because a horizontal reservation for women applies within every vertical category.
 *
 * <p>{@code UNDISCLOSED} is a real answer, not a missing value: an applicant may decline to
 * state, and the system must accept the application rather than force a declaration. Such an
 * applicant simply does not compete for the reserved sub-quota.
 */
public enum Gender {
    FEMALE, MALE, OTHER, UNDISCLOSED
}
