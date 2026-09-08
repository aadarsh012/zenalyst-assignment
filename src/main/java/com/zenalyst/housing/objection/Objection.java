package com.zenalyst.housing.objection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A challenge to a published result.
 *
 * <p>Objections are the sanctioned route for changing an outcome, and the only one. Everything else
 * in this system refuses to be edited — the audit chain, the frozen register, a published draw and
 * its allotments — precisely so that a correction has to arrive through here, in writing, with a
 * name attached and a reason recorded.
 */
@Entity
@Table(name = "objection")
public class Objection {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "scheme_id", nullable = false, updatable = false)
    private UUID schemeId;

    @Column(name = "draw_id", updatable = false)
    private UUID drawId;

    @Column(name = "application_no", updatable = false)
    private String applicationNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "ground", nullable = false, updatable = false)
    private ObjectionGround ground;

    @Column(name = "statement", nullable = false, updatable = false)
    private String statement;

    @Column(name = "supporting_reference", updatable = false)
    private String supportingReference;

    @Column(name = "filed_by", nullable = false, updatable = false)
    private String filedBy;

    @Column(name = "filed_at", nullable = false, updatable = false)
    private Instant filedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ObjectionStatus status;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "remedy")
    private String remedy;

    protected Objection() {
        // for JPA
    }

    public static Objection file(
            UUID schemeId, UUID drawId, String applicationNo, ObjectionGround ground,
            String statement, String supportingReference, String filedBy, Instant filedAt) {

        Objection objection = new Objection();
        objection.id = UUID.randomUUID();
        objection.schemeId = schemeId;
        objection.drawId = drawId;
        objection.applicationNo = applicationNo;
        objection.ground = ground;
        objection.statement = statement;
        objection.supportingReference = supportingReference;
        objection.filedBy = filedBy;
        objection.filedAt = filedAt;
        objection.status = ObjectionStatus.OPEN;
        return objection;
    }

    /**
     * Records the adjudication.
     *
     * @param remedy what upholding it requires somebody to do. Meaningless for a rejection, and
     *               essential for an acceptance: an objection upheld with no stated remedy leaves
     *               nobody knowing what was supposed to happen next.
     * @throws IllegalStateException if already decided — a second decision would erase the first,
     *                               and a disagreement between adjudicators is exactly what an
     *                               applicant contesting the outcome would want to see
     */
    public void decide(ObjectionStatus outcome, String reason, String remedy, String by, Instant at) {
        if (status != ObjectionStatus.OPEN) {
            throw new IllegalStateException(
                    "objection %s was already %s".formatted(id, status));
        }
        if (outcome == ObjectionStatus.OPEN) {
            throw new IllegalArgumentException("a decision cannot be OPEN");
        }
        this.status = outcome;
        this.decisionReason = reason;
        this.remedy = outcome == ObjectionStatus.UPHELD ? remedy : null;
        this.decidedBy = by;
        this.decidedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSchemeId() {
        return schemeId;
    }

    public UUID getDrawId() {
        return drawId;
    }

    public String getApplicationNo() {
        return applicationNo;
    }

    public ObjectionGround getGround() {
        return ground;
    }

    public String getStatement() {
        return statement;
    }

    public String getSupportingReference() {
        return supportingReference;
    }

    public String getFiledBy() {
        return filedBy;
    }

    public Instant getFiledAt() {
        return filedAt;
    }

    public ObjectionStatus getStatus() {
        return status;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public String getDecisionReason() {
        return decisionReason;
    }

    public String getRemedy() {
        return remedy;
    }
}
