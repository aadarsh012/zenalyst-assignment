package com.zenalyst.housing.eligibility;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * An operator's decision on one declared attribute of one application.
 *
 * <p>Immutable. Changing one's mind means recording a new verification, which the unique
 * constraint on {@code (application_id, claim)} prevents — deliberately. A verification that could
 * be overwritten would leave no evidence that the earlier view had existed, and "the certificate
 * was accepted and then it wasn't" is precisely what an applicant contesting the outcome needs to
 * be able to establish.
 */
@Entity
@Table(name = "claim_verification")
public class ClaimVerification {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim", nullable = false, updatable = false)
    private ClaimType claim;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, updatable = false)
    private Outcome outcome;

    @Column(name = "evidence_reference", updatable = false)
    private String evidenceReference;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "verified_by", nullable = false, updatable = false)
    private String verifiedBy;

    @Column(name = "verified_at", nullable = false, updatable = false)
    private Instant verifiedAt;

    protected ClaimVerification() {
        // for JPA
    }

    public static ClaimVerification of(
            UUID applicationId, ClaimType claim, Outcome outcome,
            String evidenceReference, String note, String verifiedBy, Instant verifiedAt) {

        ClaimVerification verification = new ClaimVerification();
        verification.id = UUID.randomUUID();
        verification.applicationId = applicationId;
        verification.claim = claim;
        verification.outcome = outcome;
        verification.evidenceReference = evidenceReference;
        verification.note = note;
        verification.verifiedBy = verifiedBy;
        verification.verifiedAt = verifiedAt;
        return verification;
    }

    public UUID getId() {
        return id;
    }

    public UUID getApplicationId() {
        return applicationId;
    }

    public ClaimType getClaim() {
        return claim;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getEvidenceReference() {
        return evidenceReference;
    }

    public String getNote() {
        return note;
    }

    public String getVerifiedBy() {
        return verifiedBy;
    }

    public Instant getVerifiedAt() {
        return verifiedAt;
    }

    /** What the operator concluded. Absence of a row is a third state: nobody has looked yet. */
    public enum Outcome {
        VERIFIED, REJECTED
    }
}
