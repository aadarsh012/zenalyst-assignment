package com.zenalyst.housing.draw;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.allocation.AllocationCandidate;
import com.zenalyst.housing.allocation.AllocationResult;
import com.zenalyst.housing.allocation.AllocationRules;
import com.zenalyst.housing.allocation.Allocator;
import com.zenalyst.housing.audit.AuditAction;
import com.zenalyst.housing.audit.AuditWriter;
import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.registry.FrozenCandidate;
import com.zenalyst.housing.registry.FrozenCandidateRepository;
import com.zenalyst.housing.rules.RuleService;
import com.zenalyst.housing.rules.RuleVersion;
import com.zenalyst.housing.rules.RuleVersionRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes and persists a draw's result.
 *
 * <p>Everything happens in one transaction: six hundred allotments, four thousand rankings, the
 * waitlists, the pool summaries and the draw's own status. A draw that had written half its
 * allotments and then failed would be the single worst state this system could be in — a partial
 * public lottery that nobody could either complete or undo. One transaction means a failure leaves
 * the register exactly as it was.
 */
@Service
public class DrawExecutionService {

    private final DrawRepository draws;
    private final DrawResultRepository results;
    private final FrozenCandidateRepository frozenCandidates;
    private final RuleVersionRepository ruleVersions;
    private final RuleService rules;
    private final AuditWriter audit;
    private final Clock clock;

    public DrawExecutionService(
            DrawRepository draws,
            DrawResultRepository results,
            FrozenCandidateRepository frozenCandidates,
            RuleVersionRepository ruleVersions,
            RuleService rules,
            AuditWriter audit,
            Clock clock) {
        this.draws = draws;
        this.results = results;
        this.frozenCandidates = frozenCandidates;
        this.ruleVersions = ruleVersions;
        this.rules = rules;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public void execute(UUID drawId, String actor) {
        Draw draw = draws.findByIdForUpdate(drawId)
                .orElseThrow(() -> ApiException.notFound("draw", drawId.toString()));

        if (draw.getStatus() != DrawStatus.RUNNING) {
            // Somebody else finished it, or it was abandoned. Not an error: the job is allowed to
            // arrive late, and doing nothing is the correct response to work already done.
            return;
        }

        RuleVersion ruleVersion = ruleVersions.findById(draw.getRuleVersionId())
                .orElseThrow(() -> ApiException.notFound("rule version", draw.getRuleVersionId().toString()));
        AllocationRules allocationRules = rules.toAllocationRules(ruleVersion);

        List<AllocationCandidate> candidates = frozenCandidates
                .findByRegistryIdOrderByLeafIndexAsc(draw.getRegistryId()).stream()
                .map(DrawExecutionService::toCandidate)
                .toList();

        AllocationResult result = Allocator.allocate(candidates, allocationRules, draw.getSeed());

        results.write(drawId, result);
        draw.markCompleted(result.awards().size(), result.resultHash(), clock.instant());
        draws.saveAndFlush(draw);

        audit.append(actor, AuditAction.DRAW_EXECUTED, "draw", drawId.toString(),
                executionPayload(draw, result));
    }

    /**
     * Records why execution failed, in its own transaction.
     *
     * <p>{@link Propagation#REQUIRES_NEW} because the failing transaction is about to roll back and
     * would take this record with it — leaving a draw that stopped for no stated reason, which is
     * the least useful thing to find in an incident.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(UUID drawId, String reason) {
        draws.findByIdForUpdate(drawId).ifPresent(draw -> {
            draw.markFailed(reason);
            draws.saveAndFlush(draw);

            ObjectNode payload = JsonNodeFactory.instance.objectNode();
            payload.put("drawId", drawId.toString());
            payload.put("reason", reason);
            audit.append("system", AuditAction.DRAW_FAILED, "draw", drawId.toString(), payload);
        });
    }

    private static AllocationCandidate toCandidate(FrozenCandidate frozen) {
        return new AllocationCandidate(
                frozen.getApplicationNo(), frozen.getEffectiveCategory(), frozen.getGender(),
                frozen.isEffectiveDisability(), frozen.isEffectiveExServiceperson(),
                frozen.isEffectiveLocalResident(), frozen.isEligible());
    }

    private ObjectNode executionPayload(Draw draw, AllocationResult result) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("drawId", draw.getId().toString());
        payload.put("eligibleCandidates", result.eligibleCandidates());
        payload.put("registryRoot", draw.getRegistryRoot());
        payload.put("resultHash", result.resultHash());
        payload.put("rulesHash", draw.getRulesHash());
        payload.put("seatsAwarded", result.awards().size());
        return payload;
    }
}
