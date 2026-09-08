package com.zenalyst.housing.identity;

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
 * A record that one application is a duplicate of another.
 *
 * <p>Links are <em>derived</em>, not historical. Every deduplication run rebuilds them from the
 * fingerprint matches and the confirmed human reviews, which is what allows the canonical
 * application to change when a late-entered paper form turns out to predate the one currently
 * standing for a person. The history of how the system reached its conclusions lives in the audit
 * chain and in {@link DuplicateReview}; this table is the current answer.
 *
 * <p>The duplicate application itself is never deleted or altered. It stays in the register,
 * readable, with this row explaining why it does not compete for a flat.
 */
@Entity
@Table(name = "duplicate_link")
public class DuplicateLink {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "scheme_id", nullable = false, updatable = false)
    private UUID schemeId;

    @Column(name = "canonical_application_id", nullable = false, updatable = false)
    private UUID canonicalApplicationId;

    @Column(name = "duplicate_application_id", nullable = false, unique = true, updatable = false)
    private UUID duplicateApplicationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, updatable = false)
    private MatchTier tier;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", nullable = false, updatable = false)
    private String evidence;

    @Column(name = "decided_by", nullable = false, updatable = false)
    private String decidedBy;

    @Column(name = "decided_at", nullable = false, updatable = false)
    private Instant decidedAt;

    protected DuplicateLink() {
        // for JPA
    }

    public static DuplicateLink of(
            UUID schemeId, UUID canonicalApplicationId, UUID duplicateApplicationId,
            MatchTier tier, String evidence, String decidedBy, Instant decidedAt) {

        DuplicateLink link = new DuplicateLink();
        link.id = UUID.randomUUID();
        link.schemeId = schemeId;
        link.canonicalApplicationId = canonicalApplicationId;
        link.duplicateApplicationId = duplicateApplicationId;
        link.tier = tier;
        link.evidence = evidence;
        link.decidedBy = decidedBy;
        link.decidedAt = decidedAt;
        return link;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSchemeId() {
        return schemeId;
    }

    public UUID getCanonicalApplicationId() {
        return canonicalApplicationId;
    }

    public UUID getDuplicateApplicationId() {
        return duplicateApplicationId;
    }

    public MatchTier getTier() {
        return tier;
    }

    public String getEvidence() {
        return evidence;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
