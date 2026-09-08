package com.zenalyst.housing.draw;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One draw.
 *
 * <p>Binds together the three things that determine the outcome: a frozen candidate register, a
 * published quota matrix, and a seed committed to before anybody could know what it would produce.
 * Given those, the six hundred names follow by arithmetic — which is the whole claim this system
 * makes.
 *
 * <p>The register root and rules hash are copied onto the draw rather than only referenced. A
 * foreign key says which row was used; a copied hash says what that row contained, and survives
 * the row being superseded.
 */
@Entity
@Table(name = "draw")
public class Draw {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "scheme_id", nullable = false, updatable = false)
    private UUID schemeId;

    @Column(name = "registry_id", nullable = false, updatable = false)
    private UUID registryId;

    @Column(name = "registry_root", nullable = false, updatable = false)
    private String registryRoot;

    @Column(name = "rule_version_id", nullable = false, updatable = false)
    private UUID ruleVersionId;

    @Column(name = "rules_hash", nullable = false, updatable = false)
    private String rulesHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "seed_source", nullable = false, updatable = false)
    private SeedSourceType seedSource;

    @Column(name = "seed_commitment", updatable = false)
    private String seedCommitment;

    @Column(name = "beacon_round", updatable = false)
    private Long beaconRound;

    @Column(name = "seed")
    private String seed;

    @Column(name = "seed_salt")
    private String seedSalt;

    @Column(name = "revealed_at")
    private Instant revealedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private DrawStatus status;

    @Column(name = "seats_awarded")
    private Integer seatsAwarded;

    @Column(name = "result_hash")
    private String resultHash;

    @Column(name = "committed_at", nullable = false, updatable = false)
    private Instant committedAt;

    @Column(name = "committed_by", nullable = false, updatable = false)
    private String committedBy;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "published_by")
    private String publishedBy;

    @Column(name = "failure_reason")
    private String failureReason;

    protected Draw() {
        // for JPA
    }

    public static Draw commit(
            UUID schemeId, UUID registryId, String registryRoot, UUID ruleVersionId, String rulesHash,
            SeedSourceType seedSource, SeedSource.Commitment commitment, Instant at, String by) {

        Draw draw = new Draw();
        draw.id = UUID.randomUUID();
        draw.schemeId = schemeId;
        draw.registryId = registryId;
        draw.registryRoot = registryRoot;
        draw.ruleVersionId = ruleVersionId;
        draw.rulesHash = rulesHash;
        draw.seedSource = seedSource;
        draw.seedCommitment = commitment.commitmentHash();
        draw.beaconRound = commitment.beaconRound();
        // Stored, not published. A hash commitment has to commit to something, so the seed exists
        // from this moment — it is simply withheld until reveal, and the response DTO enforces
        // that. A beacon source has nothing to store yet and leaves this null.
        draw.seed = commitment.seed();
        draw.seedSalt = commitment.salt();
        draw.status = DrawStatus.COMMITTED;
        draw.committedAt = at;
        draw.committedBy = by;
        return draw;
    }

    void reveal(String revealedSeed, Instant at) {
        requireStatus(DrawStatus.COMMITTED, "reveal");
        this.seed = revealedSeed;
        this.revealedAt = at;
        this.status = DrawStatus.REVEALED;
    }

    void markRunning() {
        if (status != DrawStatus.REVEALED && status != DrawStatus.FAILED) {
            throw new IllegalStateException(
                    "cannot execute a draw that is %s; it must be REVEALED, or FAILED to retry"
                            .formatted(status));
        }
        this.status = DrawStatus.RUNNING;
        this.failureReason = null;
    }

    void markCompleted(int seatsAwarded, String resultHash, Instant at) {
        requireStatus(DrawStatus.RUNNING, "complete");
        this.seatsAwarded = seatsAwarded;
        this.resultHash = resultHash;
        this.executedAt = at;
        this.status = DrawStatus.COMPLETED;
        this.failureReason = null;
    }

    void markFailed(String reason) {
        this.status = DrawStatus.FAILED;
        this.failureReason = reason;
    }

    void publish(Instant at, String by) {
        requireStatus(DrawStatus.COMPLETED, "publish");
        this.publishedAt = at;
        this.publishedBy = by;
        this.status = DrawStatus.PUBLISHED;
    }

    private void requireStatus(DrawStatus required, String action) {
        if (status != required) {
            throw new IllegalStateException(
                    "cannot %s a draw that is %s; it must be %s".formatted(action, status, required));
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getSchemeId() {
        return schemeId;
    }

    public UUID getRegistryId() {
        return registryId;
    }

    public String getRegistryRoot() {
        return registryRoot;
    }

    public UUID getRuleVersionId() {
        return ruleVersionId;
    }

    public String getRulesHash() {
        return rulesHash;
    }

    public SeedSourceType getSeedSource() {
        return seedSource;
    }

    public String getSeedCommitment() {
        return seedCommitment;
    }

    public Long getBeaconRound() {
        return beaconRound;
    }

    /**
     * The seed.
     *
     * <p><strong>Present before reveal for a hash-committed draw</strong>, because a commitment has
     * to commit to something. Never serialise this directly — {@code DrawResponse} withholds it
     * until the draw's status says it is public, and that is the only place the distinction is
     * enforced.
     */
    public String getSeed() {
        return seed;
    }

    public String getSeedSalt() {
        return seedSalt;
    }

    public Instant getRevealedAt() {
        return revealedAt;
    }

    public DrawStatus getStatus() {
        return status;
    }

    public Integer getSeatsAwarded() {
        return seatsAwarded;
    }

    public String getResultHash() {
        return resultHash;
    }

    public Instant getCommittedAt() {
        return committedAt;
    }

    public String getCommittedBy() {
        return committedBy;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public String getFailureReason() {
        return failureReason;
    }
}
