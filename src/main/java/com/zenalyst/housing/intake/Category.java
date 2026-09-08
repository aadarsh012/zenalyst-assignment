package com.zenalyst.housing.intake;

/**
 * Vertical reservation categories. These are mutually exclusive: an applicant is in exactly one.
 *
 * <p>Horizontal attributes — disability, gender, ex-service — are recorded as separate flags on
 * the application rather than as categories, because they cut across these buckets rather than
 * competing with them.
 */
public enum Category {
    GEN, SC, ST, OBC, EWS
}
