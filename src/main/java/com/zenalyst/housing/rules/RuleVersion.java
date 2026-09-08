package com.zenalyst.housing.rules;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One published version of a scheme's quota matrix.
 *
 * <p>Superseded versions are never deleted. A draw held last month was held under the rules of
 * last month, and an allotment nobody can explain because the rules that produced it were
 * overwritten is an allotment nobody can defend.
 *
 * <p>The document itself is immutable; only {@code status} moves, and only forwards.
 */
@Entity
@Table(name = "rule_version")
public class RuleVersion {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "scheme_id", nullable = false, updatable = false)
    private UUID schemeId;

    @Column(name = "version", nullable = false, updatable = false)
    private String version;

    @Column(name = "rules_hash", nullable = false, updatable = false)
    private String rulesHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules_document", nullable = false, updatable = false)
    private String rulesDocument;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "activated_by")
    private String activatedBy;

    protected RuleVersion() {
        // for JPA
    }

    public static RuleVersion draft(
            UUID schemeId, String version, String rulesHash, String rulesDocument,
            Instant createdAt, String createdBy) {

        RuleVersion ruleVersion = new RuleVersion();
        ruleVersion.id = UUID.randomUUID();
        ruleVersion.schemeId = schemeId;
        ruleVersion.version = version;
        ruleVersion.rulesHash = rulesHash;
        ruleVersion.rulesDocument = rulesDocument;
        ruleVersion.status = Status.DRAFT;
        ruleVersion.createdAt = createdAt;
        ruleVersion.createdBy = createdBy;
        return ruleVersion;
    }

    public void activate(Instant at, String by) {
        if (status != Status.DRAFT) {
            throw new IllegalStateException("rule version %s is %s and cannot be activated"
                    .formatted(version, status));
        }
        this.status = Status.ACTIVE;
        this.activatedAt = at;
        this.activatedBy = by;
    }

    public void supersede() {
        if (status != Status.ACTIVE) {
            throw new IllegalStateException("only an active rule version can be superseded");
        }
        this.status = Status.SUPERSEDED;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSchemeId() {
        return schemeId;
    }

    public String getVersion() {
        return version;
    }

    public String getRulesHash() {
        return rulesHash;
    }

    public String getRulesDocument() {
        return rulesDocument;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public String getActivatedBy() {
        return activatedBy;
    }

    public enum Status {
        /** Created and reviewable, but no draw may use it. */
        DRAFT,
        /** In force. At most one per scheme, enforced by a partial unique index. */
        ACTIVE,
        /** Replaced. Kept, because the draws held under it must remain explicable. */
        SUPERSEDED
    }
}
