package com.zenalyst.housing.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A pair of applications that look like the same person, waiting for a human to say whether they
 * are.
 *
 * <p>This is the only mutable entity in the system, and the mutation is deliberately narrow: a
 * decision can be recorded once, and the pair itself can never change. Everything else here is
 * append-only precisely so that this one place — where a person exercises judgement about another
 * person's application — is unambiguous about who decided what, and when.
 *
 * <p>Rejected pairs are kept, not deleted. Deduplication is re-run as new applications arrive, and
 * without a memory of "we already looked at these two and they are different people" it would put
 * the same pair in front of the same operator every week until someone gave in and confirmed it.
 */
@Entity
@Table(name = "duplicate_review")
public class DuplicateReview {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "scheme_id", nullable = false, updatable = false)
    private UUID schemeId;

    /** Always the lower of the two ids; see the ordering constraint on the table. */
    @Column(name = "application_a_id", nullable = false, updatable = false)
    private UUID applicationAId;

    @Column(name = "application_b_id", nullable = false, updatable = false)
    private UUID applicationBId;

    @Column(name = "similarity", nullable = false, updatable = false)
    private BigDecimal similarity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", nullable = false, updatable = false)
    private String evidence;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReviewStatus status;

    @Column(name = "raised_at", nullable = false, updatable = false)
    private Instant raisedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decision_note")
    private String decisionNote;

    protected DuplicateReview() {
        // for JPA
    }

    public static DuplicateReview raise(
            UUID schemeId, UUID applicationAId, UUID applicationBId,
            BigDecimal similarity, String evidence, Instant raisedAt) {

        DuplicateReview review = new DuplicateReview();
        review.id = UUID.randomUUID();
        review.schemeId = schemeId;
        review.applicationAId = applicationAId;
        review.applicationBId = applicationBId;
        review.similarity = similarity;
        review.evidence = evidence;
        review.status = ReviewStatus.PENDING;
        review.raisedAt = raisedAt;
        return review;
    }

    /**
     * Records the operator's judgement.
     *
     * @throws IllegalStateException if this pair has already been decided — a second decision
     *                               would overwrite the first with no trace of it having existed,
     *                               and disagreements between operators are exactly the thing an
     *                               applicant would want to know about
     */
    public void decide(ReviewStatus outcome, String decidedBy, String note, Instant decidedAt) {
        if (status != ReviewStatus.PENDING) {
            throw new IllegalStateException(
                    "review %s was already decided as %s".formatted(id, status));
        }
        if (outcome == ReviewStatus.PENDING) {
            throw new IllegalArgumentException("a decision cannot be PENDING");
        }
        this.status = outcome;
        this.decidedBy = decidedBy;
        this.decisionNote = note;
        this.decidedAt = decidedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSchemeId() {
        return schemeId;
    }

    public UUID getApplicationAId() {
        return applicationAId;
    }

    public UUID getApplicationBId() {
        return applicationBId;
    }

    public BigDecimal getSimilarity() {
        return similarity;
    }

    public String getEvidence() {
        return evidence;
    }

    public ReviewStatus getStatus() {
        return status;
    }

    public Instant getRaisedAt() {
        return raisedAt;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public String getDecisionNote() {
        return decisionNote;
    }
}
