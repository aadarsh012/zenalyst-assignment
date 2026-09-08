package com.zenalyst.housing.eligibility;

import jakarta.validation.constraints.NotNull;

/**
 * An operator recording that they have examined a document.
 *
 * @param evidenceReference which document was seen. Without it the verification is one person's
 *                          recollection, and an applicant disputing it has nothing to point at.
 */
public record VerifyClaimRequest(
        @NotNull ClaimType claim,
        @NotNull ClaimVerification.Outcome outcome,
        String evidenceReference,
        String note) {
}
