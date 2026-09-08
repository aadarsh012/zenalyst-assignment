package com.zenalyst.housing.registry;

import com.zenalyst.housing.intake.Category;
import com.zenalyst.housing.intake.Gender;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * One candidate as frozen: exactly the attributes the draw consumes, and nothing else.
 *
 * <p>No name, no address, no contact details, no identity token. None of them decides who gets a
 * flat, and this table is meant to be publishable in full — which is only a safe thing to intend
 * if it contains nothing that should not be published.
 *
 * <p>{@code canonicalJson} is stored because it is the preimage of {@code leafHash}. A verifier
 * does not have to trust our re-derivation of anyone's attributes: they hash these exact bytes and
 * check the result against the published root.
 */
@Entity
@Table(name = "frozen_candidate")
@IdClass(FrozenCandidate.Key.class)
public class FrozenCandidate {

    @Id
    @Column(name = "registry_id", nullable = false, updatable = false)
    private UUID registryId;

    @Id
    @Column(name = "leaf_index", nullable = false, updatable = false)
    private int leafIndex;

    @Column(name = "application_no", nullable = false, updatable = false)
    private String applicationNo;

    @Column(name = "leaf_hash", nullable = false, updatable = false)
    private String leafHash;

    @Column(name = "canonical_json", nullable = false, updatable = false)
    private String canonicalJson;

    @Column(name = "eligible", nullable = false, updatable = false)
    private boolean eligible;

    @Enumerated(EnumType.STRING)
    @Column(name = "effective_category", nullable = false, updatable = false)
    private Category effectiveCategory;

    @Column(name = "effective_local_resident", nullable = false, updatable = false)
    private boolean effectiveLocalResident;

    @Column(name = "effective_disability", nullable = false, updatable = false)
    private boolean effectiveDisability;

    @Column(name = "effective_ex_serviceperson", nullable = false, updatable = false)
    private boolean effectiveExServiceperson;

    @Enumerated(EnumType.STRING)
    @Column(name = "gender", nullable = false, updatable = false)
    private Gender gender;

    protected FrozenCandidate() {
        // for JPA
    }

    public static FrozenCandidate of(
            UUID registryId, int leafIndex, String applicationNo, String leafHash,
            String canonicalJson, boolean eligible, Category effectiveCategory,
            boolean effectiveLocalResident, boolean effectiveDisability,
            boolean effectiveExServiceperson, Gender gender) {

        FrozenCandidate candidate = new FrozenCandidate();
        candidate.registryId = registryId;
        candidate.leafIndex = leafIndex;
        candidate.applicationNo = applicationNo;
        candidate.leafHash = leafHash;
        candidate.canonicalJson = canonicalJson;
        candidate.eligible = eligible;
        candidate.effectiveCategory = effectiveCategory;
        candidate.effectiveLocalResident = effectiveLocalResident;
        candidate.effectiveDisability = effectiveDisability;
        candidate.effectiveExServiceperson = effectiveExServiceperson;
        candidate.gender = gender;
        return candidate;
    }

    public UUID getRegistryId() {
        return registryId;
    }

    public int getLeafIndex() {
        return leafIndex;
    }

    public String getApplicationNo() {
        return applicationNo;
    }

    public String getLeafHash() {
        return leafHash;
    }

    public String getCanonicalJson() {
        return canonicalJson;
    }

    public boolean isEligible() {
        return eligible;
    }

    public Category getEffectiveCategory() {
        return effectiveCategory;
    }

    public boolean isEffectiveLocalResident() {
        return effectiveLocalResident;
    }

    public boolean isEffectiveDisability() {
        return effectiveDisability;
    }

    public boolean isEffectiveExServiceperson() {
        return effectiveExServiceperson;
    }

    public Gender getGender() {
        return gender;
    }

    /** Composite key: a candidate is identified by its registry and its position in it. */
    public static class Key implements Serializable {

        private UUID registryId;
        private int leafIndex;

        public Key() {
        }

        public Key(UUID registryId, int leafIndex) {
            this.registryId = registryId;
            this.leafIndex = leafIndex;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return leafIndex == key.leafIndex && Objects.equals(registryId, key.registryId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(registryId, leafIndex);
        }
    }
}
