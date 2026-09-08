package com.zenalyst.housing.registry;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.audit.AuditAction;
import com.zenalyst.housing.audit.AuditWriter;
import com.zenalyst.housing.eligibility.EligibilityDecision;
import com.zenalyst.housing.eligibility.EligibilityRules;
import com.zenalyst.housing.eligibility.EligibilityService;
import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.platform.hash.CanonicalJson;
import com.zenalyst.housing.scheme.Scheme;
import com.zenalyst.housing.scheme.SchemeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Takes an immutable, hashed snapshot of who is in the draw and on what terms.
 *
 * <h2>Why freeze at all</h2>
 *
 * <p>Without a freeze, "the draw was run on the register" means the register as it happened to be
 * at some unrecorded moment, which nobody can reconstruct afterwards. With one, the draw runs
 * against a specific set of rows whose root has already been published — so an accusation that
 * somebody was added, removed or reclassified after the fact is answerable by arithmetic instead
 * of by assurance.
 *
 * <p>The root is published <em>before</em> any seed exists. That ordering is the whole point: an
 * authority that could still change the candidate list after seeing the seed could choose an
 * outcome.
 *
 * <h2>Determinism</h2>
 *
 * <p>Candidates are sorted by application number, each rendered to canonical JSON, and the
 * resulting bytes hashed into a Merkle tree. Freezing unchanged data twice therefore produces an
 * identical root — which is not a coincidence to be tolerated but the property being relied on. It
 * is what lets anyone holding the published rows recompute the root and get ours.
 */
@Service
public class RegistryFreezeService {

    private final SchemeRepository schemes;
    private final EligibilityService eligibility;
    private final FrozenRegistryRepository registries;
    private final FrozenCandidateRepository candidates;
    private final AuditWriter audit;
    private final Clock clock;

    public RegistryFreezeService(
            SchemeRepository schemes,
            EligibilityService eligibility,
            FrozenRegistryRepository registries,
            FrozenCandidateRepository candidates,
            AuditWriter audit,
            Clock clock) {
        this.schemes = schemes;
        this.eligibility = eligibility;
        this.registries = registries;
        this.candidates = candidates;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public FreezeResponse freeze(String schemeCode, String frozenBy) {
        Scheme scheme = schemes.findByCode(schemeCode)
                .orElseThrow(() -> ApiException.notFound("scheme", schemeCode));

        EligibilityRules rules = eligibility.rulesFor(scheme);
        List<EligibilityService.Assessment> assessments = eligibility.assessAll(scheme);

        List<String> payloads = new ArrayList<>(assessments.size());
        for (EligibilityService.Assessment assessment : assessments) {
            payloads.add(candidatePayload(assessment));
        }

        MerkleTree tree = MerkleTree.of(payloads);
        Instant now = clock.instant();
        int eligibleCount = (int) assessments.stream()
                .filter(assessment -> assessment.decision().eligible())
                .count();

        FrozenRegistry registry = registries.saveAndFlush(FrozenRegistry.of(
                scheme.getId(), tree.root(), assessments.size(), eligibleCount,
                rules.hash(), rules.document(), now, frozenBy));

        for (int index = 0; index < assessments.size(); index++) {
            EligibilityService.Assessment assessment = assessments.get(index);
            EligibilityDecision decision = assessment.decision();
            candidates.save(FrozenCandidate.of(
                    registry.getId(), index,
                    assessment.application().getApplicationNo(),
                    tree.leafHash(index),
                    payloads.get(index),
                    decision.eligible(),
                    decision.effectiveCategory(),
                    decision.effectiveLocalResident(),
                    decision.effectiveDisability(),
                    decision.effectiveExServiceperson(),
                    assessment.application().getGender()));
        }

        audit.append(frozenBy, AuditAction.REGISTRY_FROZEN, "scheme", scheme.getCode(),
                freezePayload(registry));

        return new FreezeResponse(
                scheme.getCode(), registry.getId().toString(), registry.getRegistryRoot(),
                registry.getRulesHash(), registry.getCandidateCount(), registry.getEligibleCount(),
                registry.getFrozenAt(), registry.getFrozenBy());
    }

    /**
     * An applicant's proof that their row was among the inputs to a draw.
     *
     * <p>Returns the candidate's exact published bytes together with the sibling hashes needed to
     * fold them up to the root. Nothing here requires trusting this service: the applicant hashes
     * the bytes themselves and checks the result against the root printed in the newspaper.
     */
    @Transactional(readOnly = true)
    public InclusionProofResponse proofOf(String registryRoot, String applicationNo) {
        FrozenRegistry registry = registries.findFirstByRegistryRootOrderByFrozenAtAsc(registryRoot)
                .orElseThrow(() -> ApiException.notFound("frozen registry", registryRoot));

        FrozenCandidate candidate = candidates
                .findByRegistryIdAndApplicationNo(registry.getId(), applicationNo)
                .orElseThrow(() -> ApiException.notFound(
                        "candidate %s in registry".formatted(applicationNo), registryRoot));

        List<FrozenCandidate> all = candidates.findByRegistryIdOrderByLeafIndexAsc(registry.getId());
        MerkleTree tree = MerkleTree.of(all.stream().map(FrozenCandidate::getCanonicalJson).toList());

        List<MerkleTree.ProofStep> proof = tree.proofFor(candidate.getLeafIndex());
        return new InclusionProofResponse(
                applicationNo, registryRoot, candidate.getLeafIndex(), all.size(),
                candidate.getCanonicalJson(), candidate.getLeafHash(), proof,
                MerkleTree.verify(candidate.getCanonicalJson(), proof, registryRoot));
    }

    @Transactional(readOnly = true)
    public FrozenRegistry latestFor(String schemeCode) {
        Scheme scheme = schemes.findByCode(schemeCode)
                .orElseThrow(() -> ApiException.notFound("scheme", schemeCode));
        return registries.findFirstBySchemeIdOrderByFrozenAtDesc(scheme.getId())
                .orElseThrow(() -> ApiException.notFound("frozen registry for scheme", schemeCode));
    }

    @Transactional(readOnly = true)
    public List<FrozenCandidate> candidatesOf(String registryRoot) {
        FrozenRegistry registry = registries.findFirstByRegistryRootOrderByFrozenAtAsc(registryRoot)
                .orElseThrow(() -> ApiException.notFound("frozen registry", registryRoot));
        return candidates.findByRegistryIdOrderByLeafIndexAsc(registry.getId());
    }

    /**
     * The bytes committed to for one candidate.
     *
     * <p>Contains every input the allocator will consume and nothing else. Ineligible candidates
     * carry their reason codes, so the published register accounts for why somebody is not
     * competing rather than silently omitting them.
     */
    private String candidatePayload(EligibilityService.Assessment assessment) {
        EligibilityDecision decision = assessment.decision();

        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("applicationNo", assessment.application().getApplicationNo());
        node.put("category", decision.effectiveCategory().name());
        node.put("disability", decision.effectiveDisability());
        node.put("eligible", decision.eligible());
        node.put("exServiceperson", decision.effectiveExServiceperson());
        node.put("gender", assessment.application().getGender().name());
        node.put("localResident", decision.effectiveLocalResident());

        ArrayNode reasons = node.putArray("ineligibilityReasons");
        decision.disqualifyingFailures().stream()
                .map(EligibilityDecision.Check::code)
                .sorted()
                .forEach(reasons::add);

        return CanonicalJson.render(node);
    }

    private ObjectNode freezePayload(FrozenRegistry registry) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("candidateCount", registry.getCandidateCount());
        payload.put("eligibleCount", registry.getEligibleCount());
        payload.put("registryId", registry.getId().toString());
        payload.put("registryRoot", registry.getRegistryRoot());
        payload.put("rulesHash", registry.getRulesHash());
        return payload;
    }
}
