package com.zenalyst.housing.draw;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.audit.AuditAction;
import com.zenalyst.housing.audit.AuditWriter;
import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.platform.error.ProblemType;
import com.zenalyst.housing.registry.FrozenRegistry;
import com.zenalyst.housing.registry.RegistryFreezeService;
import com.zenalyst.housing.rules.RuleService;
import com.zenalyst.housing.rules.RuleVersion;
import com.zenalyst.housing.rules.RuleVersionRepository;
import com.zenalyst.housing.scheme.Scheme;
import com.zenalyst.housing.scheme.SchemeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jobrunr.scheduling.JobScheduler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The draw ceremony: commit, reveal, execute, publish.
 *
 * <h2>Why four steps and not one</h2>
 *
 * <p>Each boundary removes a way of choosing the outcome rather than discovering it.
 *
 * <p><b>Commit</b> fixes the register, the rule version and the seed commitment together. From
 * here the candidate list cannot change, because its root is already public.
 *
 * <p><b>Reveal</b> makes the seed public. It happens after the commitment, so the seed cannot be
 * adjusted once anybody can see what it produces.
 *
 * <p><b>Execute</b> computes the allotment. It runs as a persisted background job rather than
 * inside the HTTP request, because a draw over four thousand candidates should not depend on a
 * load balancer's patience, and a crashed process should leave a job that can be retried rather
 * than a question about what happened.
 *
 * <p><b>Publish</b> declares the result final, after which a database trigger refuses to let it
 * change.
 */
@Service
public class DrawService {

    private final SchemeRepository schemes;
    private final RegistryFreezeService registry;
    private final RuleService rules;
    private final RuleVersionRepository ruleVersions;
    private final DrawRepository draws;
    private final AuditWriter audit;
    private final JobScheduler jobs;
    private final DrawExecutionJob executionJob;
    private final SupersessionAuthority supersessionAuthority;
    private final Clock clock;
    private final Map<SeedSourceType, SeedSource> seedSources;
    private final SeedSourceType configuredSeedSource;

    public DrawService(
            SchemeRepository schemes,
            RegistryFreezeService registry,
            RuleService rules,
            RuleVersionRepository ruleVersions,
            DrawRepository draws,
            AuditWriter audit,
            JobScheduler jobs,
            DrawExecutionJob executionJob,
            SupersessionAuthority supersessionAuthority,
            Clock clock,
            List<SeedSource> availableSeedSources,
            @Value("${housing.draw.seed-source}") SeedSourceType configuredSeedSource) {
        this.schemes = schemes;
        this.registry = registry;
        this.rules = rules;
        this.ruleVersions = ruleVersions;
        this.draws = draws;
        this.audit = audit;
        this.jobs = jobs;
        this.executionJob = executionJob;
        this.supersessionAuthority = supersessionAuthority;
        this.clock = clock;
        this.seedSources = availableSeedSources.stream()
                .collect(java.util.stream.Collectors.toMap(SeedSource::type, source -> source));
        this.configuredSeedSource = configuredSeedSource;
    }

    /**
     * Creates a draw and publishes its commitment.
     *
     * <p>Refuses if the scheme has no frozen register or no active rule version. Both are things a
     * draw is <em>of</em>; without them there is nothing to draw and no rules to draw by.
     */
    @Transactional
    public DrawResponse commit(String schemeCode, String committedBy) {
        return commit(schemeCode, committedBy, null);
    }

    /**
     * Creates a draw, optionally superseding a published one.
     *
     * @param supersedesDrawId the published draw this replaces. Permitted only where an objection
     *                         against it has been upheld — see
     *                         {@link #requireSupersessionIsAuthorised}.
     */
    @Transactional
    public DrawResponse commit(String schemeCode, String committedBy, UUID supersedesDrawId) {
        Scheme scheme = scheme(schemeCode);
        FrozenRegistry frozen = registry.latestFor(schemeCode);
        RuleVersion ruleVersion = rules.activeVersion(scheme);

        draws.findBySchemeIdAndStatusIn(scheme.getId(),
                        List.of(DrawStatus.COMMITTED, DrawStatus.REVEALED, DrawStatus.RUNNING))
                .ifPresent(inFlight -> {
                    throw ApiException.conflict(
                            "Draw %s for '%s' is still %s. Finish or abandon it before starting another."
                                    .formatted(inFlight.getId(), schemeCode, inFlight.getStatus()),
                            Map.of("drawId", inFlight.getId().toString(),
                                    "status", inFlight.getStatus().name()));
                });

        if (supersedesDrawId != null) {
            requireSupersessionIsAuthorised(scheme, supersedesDrawId);
        }

        SeedSource source = sourceFor(configuredSeedSource);
        Draw draw = draws.saveAndFlush(Draw.commit(
                scheme.getId(), frozen.getId(), frozen.getRegistryRoot(),
                ruleVersion.getId(), ruleVersion.getRulesHash(),
                source.type(), source.commit(), clock.instant(), committedBy, supersedesDrawId));

        audit.append(committedBy, AuditAction.DRAW_COMMITTED, "draw", draw.getId().toString(),
                commitPayload(schemeCode, draw));

        return DrawResponse.of(draw, schemeCode, ruleVersion.getVersion());
    }

    /**
     * Refuses a supersession that nobody asked for.
     *
     * <p>An authority able to re-draw at will can draw repeatedly until it likes the answer, and
     * the commitment ceremony would not catch it — every individual draw would verify perfectly.
     * Requiring an upheld objection makes replacing a result something that has to be asked for in
     * writing, adjudicated in writing, and left in the audit trail.
     *
     * <p>An authority that finds its own error files its own objection and upholds it. That is not
     * a loophole; it is the paper trail working as intended.
     */
    private void requireSupersessionIsAuthorised(Scheme scheme, UUID supersedesDrawId) {
        Draw superseded = draws.findById(supersedesDrawId)
                .orElseThrow(() -> ApiException.notFound("draw", supersedesDrawId.toString()));

        if (!superseded.getSchemeId().equals(scheme.getId())) {
            throw ApiException.conflict(
                    "Draw %s belongs to a different scheme.".formatted(supersedesDrawId),
                    Map.of("drawId", supersedesDrawId.toString()));
        }
        if (superseded.getStatus() != DrawStatus.PUBLISHED) {
            throw ApiException.conflict(
                    "Only a published draw can be superseded; draw %s is %s."
                            .formatted(supersedesDrawId, superseded.getStatus()),
                    Map.of("drawId", supersedesDrawId.toString(),
                            "status", superseded.getStatus().name()));
        }
        draws.findBySupersedesDrawId(supersedesDrawId).ifPresent(existing -> {
            throw ApiException.conflict(
                    "Draw %s has already been superseded by draw %s."
                            .formatted(supersedesDrawId, existing.getId()),
                    Map.of("drawId", supersedesDrawId.toString(),
                            "supersededBy", existing.getId().toString()));
        });

        if (!supersessionAuthority.isSupersessionAuthorised(supersedesDrawId)) {
            throw ApiException.conflict(
                    ("Draw %s cannot be superseded: no objection against it has been upheld. "
                            + "A published result is replaced only where a challenge has been "
                            + "accepted in writing.").formatted(supersedesDrawId),
                    Map.of("drawId", supersedesDrawId.toString()));
        }
    }

    @Transactional
    public DrawResponse reveal(UUID drawId, String revealedBy) {
        Draw draw = lockedDraw(drawId);
        requireStatus(draw, DrawStatus.COMMITTED, "reveal the seed of");

        String seed = sourceFor(draw.getSeedSource()).reveal(draw);
        draw.reveal(seed, clock.instant());
        draws.saveAndFlush(draw);

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("drawId", draw.getId().toString());
        // The seed goes into the chain because it is public from this instant, and the chain is
        // where a challenger will look for the moment it became so.
        payload.put("seed", seed);
        if (draw.getSeedSalt() != null) {
            payload.put("seedSalt", draw.getSeedSalt());
        }
        audit.append(revealedBy, AuditAction.DRAW_SEED_REVEALED, "draw", draw.getId().toString(), payload);

        return response(draw);
    }

    /**
     * Queues the draw for execution.
     *
     * <p>The status moves to {@code RUNNING} under a row lock before the job is enqueued, so two
     * simultaneous requests cannot both queue one. The second finds the draw already running and
     * is told so.
     */
    @Transactional
    public DrawResponse execute(UUID drawId, String executedBy) {
        Draw draw = lockedDraw(drawId);
        // REVEALED is the normal path; FAILED means somebody is deliberately retrying a draw that
        // stopped, which is the only way a failed draw ever runs again.
        if (draw.getStatus() != DrawStatus.REVEALED && draw.getStatus() != DrawStatus.FAILED) {
            throw ApiException.conflict(
                    "Cannot execute a draw that is %s; it must be REVEALED, or FAILED to retry."
                            .formatted(draw.getStatus()),
                    Map.of("drawId", drawId.toString(), "status", draw.getStatus().name()));
        }

        draw.markRunning();
        draws.saveAndFlush(draw);

        // Enqueued against the Spring bean, so JobRunr resolves it from the context when the job
        // runs — possibly in another process, certainly after this transaction has committed.
        jobs.enqueue(() -> executionJob.run(drawId, executedBy));
        return response(draw);
    }

    @Transactional
    public DrawResponse publish(UUID drawId, String publishedBy) {
        Draw draw = lockedDraw(drawId);
        requireStatus(draw, DrawStatus.COMPLETED, "publish");

        draw.publish(clock.instant(), publishedBy);
        draws.saveAndFlush(draw);

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("drawId", draw.getId().toString());
        payload.put("resultHash", draw.getResultHash());
        payload.put("seatsAwarded", draw.getSeatsAwarded());
        audit.append(publishedBy, AuditAction.DRAW_PUBLISHED, "draw", draw.getId().toString(), payload);

        return response(draw);
    }

    @Transactional(readOnly = true)
    public DrawResponse get(UUID drawId) {
        return response(draws.findById(drawId)
                .orElseThrow(() -> ApiException.notFound("draw", drawId.toString())));
    }

    @Transactional(readOnly = true)
    public List<DrawResponse> listForScheme(String schemeCode) {
        Scheme scheme = scheme(schemeCode);
        return draws.findBySchemeIdOrderByCommittedAtDesc(scheme.getId()).stream()
                .map(this::response)
                .toList();
    }

    private DrawResponse response(Draw draw) {
        String schemeCode = schemes.findById(draw.getSchemeId())
                .map(Scheme::getCode).orElse("unknown");
        String rulesVersion = ruleVersions.findById(draw.getRuleVersionId())
                .map(RuleVersion::getVersion).orElse("unknown");
        String supersededBy = draws.findBySupersedesDrawId(draw.getId())
                .map(successor -> successor.getId().toString()).orElse(null);
        return DrawResponse.of(draw, schemeCode, rulesVersion, supersededBy);
    }

    private Draw lockedDraw(UUID drawId) {
        return draws.findByIdForUpdate(drawId)
                .orElseThrow(() -> ApiException.notFound("draw", drawId.toString()));
    }

    private void requireStatus(Draw draw, DrawStatus required, String action) {
        if (draw.getStatus() != required) {
            throw ApiException.conflict(
                    "Cannot %s a draw that is %s; it must be %s."
                            .formatted(action, draw.getStatus(), required),
                    Map.of("drawId", draw.getId().toString(),
                            "status", draw.getStatus().name(),
                            "requiredStatus", required.name()));
        }
    }

    private SeedSource sourceFor(SeedSourceType type) {
        SeedSource source = seedSources.get(type);
        if (source == null) {
            throw new ApiException(ProblemType.INTERNAL_ERROR,
                    "No seed source is configured for " + type);
        }
        return source;
    }

    /** Note what is absent: the seed. A commitment that published its own secret would commit to nothing. */
    private ObjectNode commitPayload(String schemeCode, Draw draw) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        if (draw.getBeaconRound() != null) {
            payload.put("beaconRound", draw.getBeaconRound());
        }
        payload.put("drawId", draw.getId().toString());
        payload.put("registryRoot", draw.getRegistryRoot());
        payload.put("rulesHash", draw.getRulesHash());
        payload.put("schemeCode", schemeCode);
        if (draw.getSeedCommitment() != null) {
            payload.put("seedCommitment", draw.getSeedCommitment());
        }
        payload.put("seedSource", draw.getSeedSource().name());
        if (draw.getSupersedesDrawId() != null) {
            payload.put("supersedes", draw.getSupersedesDrawId().toString());
        }
        return payload;
    }

    private Scheme scheme(String schemeCode) {
        return schemes.findByCode(schemeCode)
                .orElseThrow(() -> ApiException.notFound("scheme", schemeCode));
    }
}
