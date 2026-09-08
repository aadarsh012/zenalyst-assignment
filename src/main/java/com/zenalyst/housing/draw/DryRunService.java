package com.zenalyst.housing.draw;

import com.zenalyst.housing.allocation.AllocationCandidate;
import com.zenalyst.housing.allocation.AllocationResult;
import com.zenalyst.housing.allocation.AllocationRules;
import com.zenalyst.housing.allocation.Allocator;
import com.zenalyst.housing.allocation.PoolOutcome;
import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.registry.FrozenCandidate;
import com.zenalyst.housing.registry.RegistryFreezeService;
import com.zenalyst.housing.rules.RuleService;
import com.zenalyst.housing.rules.RuleVersion;
import com.zenalyst.housing.scheme.Scheme;
import com.zenalyst.housing.scheme.SchemeRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the allocator against a frozen register and returns the result without keeping any of it.
 *
 * <p>A rehearsal exists because a quota matrix is easy to get wrong in ways only visible in its
 * output — a reservation that turns out to be unfillable, a pool that leaves seats empty, a total
 * that is one flat short. Discovering any of those after the seed is public means either living
 * with the result or re-drawing, and re-drawing a public lottery is a thing an authority does at
 * most once.
 *
 * <p>Nothing is written. No audit event either: a rehearsal changed nobody's chances, and the
 * chain is for acts that did.
 *
 * <p>The seed is supplied by the caller. In the real draw it will be committed to before it is
 * revealed; here it is simply an input, and the natural thing to rehearse with is a seed that is
 * <em>not</em> the one you intend to use.
 */
@Service
public class DryRunService {

    /** Enough of a waitlist to see that it is ordered sensibly, without burying the response. */
    private static final int WAITLIST_PREVIEW = 10;

    private final SchemeRepository schemes;
    private final RegistryFreezeService registry;
    private final RuleService rules;

    public DryRunService(SchemeRepository schemes, RegistryFreezeService registry, RuleService rules) {
        this.schemes = schemes;
        this.registry = registry;
        this.rules = rules;
    }

    @Transactional(readOnly = true)
    public DryRunResponse run(String schemeCode, String seed) {
        Scheme scheme = schemes.findByCode(schemeCode)
                .orElseThrow(() -> ApiException.notFound("scheme", schemeCode));

        var frozen = registry.latestFor(schemeCode);
        List<FrozenCandidate> frozenCandidates = registry.candidatesOf(frozen.getRegistryRoot());

        RuleVersion ruleVersion = rules.activeVersion(scheme);
        AllocationRules allocationRules = rules.toAllocationRules(ruleVersion);

        List<AllocationCandidate> candidates = frozenCandidates.stream()
                .map(DryRunService::toAllocationCandidate)
                .toList();

        AllocationResult result = Allocator.allocate(candidates, allocationRules, seed);

        return new DryRunResponse(
                schemeCode,
                frozen.getRegistryRoot(),
                ruleVersion.getVersion(),
                ruleVersion.getRulesHash(),
                seed,
                false,
                frozenCandidates.size(),
                result.eligibleCandidates(),
                result.totalSeats(),
                result.awards().size(),
                result.pools().stream().map(DryRunService::summarise).toList(),
                result.awards());
    }

    /**
     * The frozen row is the allocator's input, unchanged.
     *
     * <p>Reading from the freeze rather than from the live application table is the point of having
     * frozen anything: the draw must run on the rows whose root was published, not on whatever the
     * register has become since.
     */
    private static AllocationCandidate toAllocationCandidate(FrozenCandidate frozen) {
        return new AllocationCandidate(
                frozen.getApplicationNo(),
                frozen.getEffectiveCategory(),
                frozen.getGender(),
                frozen.isEffectiveDisability(),
                frozen.isEffectiveExServiceperson(),
                frozen.isEffectiveLocalResident(),
                frozen.isEligible());
    }

    private static DryRunResponse.PoolSummary summarise(PoolOutcome outcome) {
        return new DryRunResponse.PoolSummary(
                outcome.pool(), outcome.seats(), outcome.awarded(), outcome.competitors(),
                outcome.cutoffPoolRank(), outcome.horizontal(), outcome.displaced(),
                outcome.waitlist().size(),
                outcome.waitlist().stream().limit(WAITLIST_PREVIEW).toList());
    }
}
