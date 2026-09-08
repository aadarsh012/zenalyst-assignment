package com.zenalyst.housing.registry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A moment at which the inputs to a draw stopped moving.
 *
 * <p>Publishing {@code registryRoot} commits the authority to an exact set of candidates with
 * exact attributes, before any seed exists. Everything the draw does afterwards is a function of
 * this snapshot and that seed.
 *
 * <p>{@code rulesHash} is published alongside the root, and answers a different question. The root
 * proves <em>who</em> was in the draw; the rules hash proves <em>what they were judged by</em>. A
 * result carrying both cannot be defended against with "you moved the income limit afterwards".
 */
@Entity
@Table(name = "frozen_registry")
public class FrozenRegistry {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "scheme_id", nullable = false, updatable = false)
    private UUID schemeId;

    @Column(name = "registry_root", nullable = false, updatable = false)
    private String registryRoot;

    @Column(name = "candidate_count", nullable = false, updatable = false)
    private int candidateCount;

    @Column(name = "eligible_count", nullable = false, updatable = false)
    private int eligibleCount;

    @Column(name = "rules_hash", nullable = false, updatable = false)
    private String rulesHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "rules_document", nullable = false, updatable = false)
    private String rulesDocument;

    @Column(name = "frozen_at", nullable = false, updatable = false)
    private Instant frozenAt;

    @Column(name = "frozen_by", nullable = false, updatable = false)
    private String frozenBy;

    protected FrozenRegistry() {
        // for JPA
    }

    public static FrozenRegistry of(
            UUID schemeId, String registryRoot, int candidateCount, int eligibleCount,
            String rulesHash, String rulesDocument, Instant frozenAt, String frozenBy) {

        FrozenRegistry registry = new FrozenRegistry();
        registry.id = UUID.randomUUID();
        registry.schemeId = schemeId;
        registry.registryRoot = registryRoot;
        registry.candidateCount = candidateCount;
        registry.eligibleCount = eligibleCount;
        registry.rulesHash = rulesHash;
        registry.rulesDocument = rulesDocument;
        registry.frozenAt = frozenAt;
        registry.frozenBy = frozenBy;
        return registry;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSchemeId() {
        return schemeId;
    }

    public String getRegistryRoot() {
        return registryRoot;
    }

    public int getCandidateCount() {
        return candidateCount;
    }

    public int getEligibleCount() {
        return eligibleCount;
    }

    public String getRulesHash() {
        return rulesHash;
    }

    public String getRulesDocument() {
        return rulesDocument;
    }

    public Instant getFrozenAt() {
        return frozenAt;
    }

    public String getFrozenBy() {
        return frozenBy;
    }
}
