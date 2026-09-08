package com.zenalyst.housing.transparency;

import com.zenalyst.housing.allocation.AllocationCandidate;
import com.zenalyst.housing.allocation.AllocationResult;
import com.zenalyst.housing.allocation.AllocationRules;
import com.zenalyst.housing.allocation.Allocator;
import com.zenalyst.housing.allocation.SeatAward;
import com.zenalyst.housing.draw.AuthorityCommittedSeedSource;
import com.zenalyst.housing.draw.Draw;
import com.zenalyst.housing.draw.DrawRepository;
import com.zenalyst.housing.draw.SeedSourceType;
import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.registry.FrozenCandidate;
import com.zenalyst.housing.registry.FrozenCandidateRepository;
import com.zenalyst.housing.registry.MerkleTree;
import com.zenalyst.housing.rules.RuleService;
import com.zenalyst.housing.rules.RuleVersion;
import com.zenalyst.housing.rules.RuleVersionRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-derives a draw from its published inputs and reports whether the stored result matches.
 *
 * <p>This is the endpoint the whole system is built around. It performs, against our own database,
 * exactly the check a hostile third party would perform from outside: rebuild the register's Merkle
 * root, confirm the seed matches its commitment, re-run the allocator, and compare the six hundred
 * names against what was stored.
 *
 * <p>It is not a substitute for that outsider doing it themselves — a compromised server would
 * happily report success. Its value is that any divergence between the published inputs and the
 * stored result becomes visible immediately, to anyone, including to the authority itself. A silent
 * edit to an allotment row stops being something nobody notices until an applicant complains.
 */
@Service
public class DrawVerificationService {

    private final DrawRepository draws;
    private final FrozenCandidateRepository frozenCandidates;
    private final RuleVersionRepository ruleVersions;
    private final RuleService rules;
    private final JdbcTemplate jdbc;

    public DrawVerificationService(
            DrawRepository draws,
            FrozenCandidateRepository frozenCandidates,
            RuleVersionRepository ruleVersions,
            RuleService rules,
            JdbcTemplate jdbc) {
        this.draws = draws;
        this.frozenCandidates = frozenCandidates;
        this.ruleVersions = ruleVersions;
        this.rules = rules;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public DrawVerificationReport verify(UUID drawId) {
        Draw draw = draws.findById(drawId)
                .orElseThrow(() -> ApiException.notFound("draw", drawId.toString()));

        if (draw.getSeed() == null) {
            throw ApiException.conflict(
                    "Draw %s has not revealed its seed, so there is nothing to verify yet."
                            .formatted(drawId),
                    Map.of("drawId", drawId.toString(), "status", draw.getStatus().name()));
        }

        List<DrawVerificationReport.Check> checks = new ArrayList<>();
        List<FrozenCandidate> candidates =
                frozenCandidates.findByRegistryIdOrderByLeafIndexAsc(draw.getRegistryId());

        checks.add(checkRegistryRoot(draw, candidates));
        checks.add(checkSeedCommitment(draw));

        RuleVersion ruleVersion = ruleVersions.findById(draw.getRuleVersionId())
                .orElseThrow(() -> ApiException.notFound("rule version", draw.getRuleVersionId().toString()));
        checks.add(checkRulesHash(draw, ruleVersion));

        AllocationResult recomputed = Allocator.allocate(
                candidates.stream().map(DrawVerificationService::toCandidate).toList(),
                rules.toAllocationRules(ruleVersion),
                draw.getSeed());

        checks.add(checkAllotments(draw, recomputed));
        checks.add(checkResultHash(draw, recomputed));

        boolean verified = checks.stream().allMatch(DrawVerificationReport.Check::passed);
        return new DrawVerificationReport(drawId.toString(), verified, summary(verified, checks), checks);
    }

    /** Rebuilds the Merkle tree from the stored candidate rows and compares it with the published root. */
    private DrawVerificationReport.Check checkRegistryRoot(Draw draw, List<FrozenCandidate> candidates) {
        String recomputed = MerkleTree.of(
                candidates.stream().map(FrozenCandidate::getCanonicalJson).toList()).root();

        return recomputed.equals(draw.getRegistryRoot())
                ? DrawVerificationReport.Check.passed("REGISTRY_ROOT", recomputed,
                        "The %d frozen candidate rows rebuild the root this draw committed to."
                                .formatted(candidates.size()))
                : DrawVerificationReport.Check.failed("REGISTRY_ROOT", draw.getRegistryRoot(), recomputed,
                        "The stored candidate rows no longer produce the published root. A candidate "
                                + "has been added, removed or altered since the register was frozen.");
    }

    private DrawVerificationReport.Check checkSeedCommitment(Draw draw) {
        if (draw.getSeedSource() == SeedSourceType.DRAND_BEACON) {
            return DrawVerificationReport.Check.passed("SEED_COMMITMENT",
                    String.valueOf(draw.getBeaconRound()),
                    ("The seed is drand round %d, whose number was published before the round existed. "
                            + "Fetch https://api.drand.sh/public/%d to confirm the value independently — "
                            + "this service is not needed for that, and should not be trusted for it.")
                            .formatted(draw.getBeaconRound(), draw.getBeaconRound()));
        }

        String recomputed = AuthorityCommittedSeedSource.commitmentOf(draw.getSeed(), draw.getSeedSalt());
        return recomputed.equals(draw.getSeedCommitment())
                ? DrawVerificationReport.Check.passed("SEED_COMMITMENT", recomputed,
                        "SHA-256(seed + \":\" + salt) matches the commitment published before the "
                                + "seed was known.")
                : DrawVerificationReport.Check.failed("SEED_COMMITMENT",
                        draw.getSeedCommitment(), recomputed,
                        "The revealed seed does not hash to the published commitment. The seed was "
                                + "changed after it was committed to.");
    }

    private DrawVerificationReport.Check checkRulesHash(Draw draw, RuleVersion ruleVersion) {
        AllocationRules allocationRules = rules.toAllocationRules(ruleVersion);
        String recomputed = allocationRules.hash();

        return recomputed.equals(draw.getRulesHash())
                ? DrawVerificationReport.Check.passed("RULES_HASH", recomputed,
                        "The quota matrix stored as version '%s' is the one this draw was run under."
                                .formatted(ruleVersion.getVersion()))
                : DrawVerificationReport.Check.failed("RULES_HASH", draw.getRulesHash(), recomputed,
                        "The rule version this draw referenced no longer hashes to the value recorded "
                                + "at commit time. The quota matrix has been altered.");
    }

    /**
     * Compares the recomputed allotment with the stored one, name by name.
     *
     * <p>Reports the first few differences rather than a count. "Three rows differ" tells an
     * investigator nothing; naming the applicants tells them where to look.
     */
    private DrawVerificationReport.Check checkAllotments(Draw draw, AllocationResult recomputed) {
        Map<String, String> stored = new LinkedHashMap<>();
        jdbc.query("""
                SELECT application_no, pool, basis, pool_rank
                FROM allotment WHERE draw_id = ?
                ORDER BY pool, pool_rank
                """,
                rs -> {
                    stored.put(rs.getString("application_no"), "%s/%s/%d".formatted(
                            rs.getString("pool"), rs.getString("basis"), rs.getInt("pool_rank")));
                },
                draw.getId());

        Map<String, String> expected = new LinkedHashMap<>();
        for (SeatAward award : recomputed.awards()) {
            expected.put(award.applicationNo(), "%s/%s/%d".formatted(
                    award.pool().name(), award.basis().name(), award.poolRank()));
        }

        List<String> differences = new ArrayList<>();
        expected.forEach((applicationNo, description) -> {
            String storedDescription = stored.get(applicationNo);
            if (storedDescription == null) {
                differences.add("%s should hold a flat (%s) but has no allotment"
                        .formatted(applicationNo, description));
            } else if (!storedDescription.equals(description)) {
                differences.add("%s is stored as %s but recomputes to %s"
                        .formatted(applicationNo, storedDescription, description));
            }
        });
        stored.keySet().stream()
                .filter(applicationNo -> !expected.containsKey(applicationNo))
                .forEach(applicationNo -> differences.add(
                        "%s holds a flat that the published inputs do not award".formatted(applicationNo)));

        if (differences.isEmpty()) {
            return DrawVerificationReport.Check.passed("ALLOTMENT",
                    "%d allotments".formatted(expected.size()),
                    "Re-running the allocator on the frozen register, the published rules and the "
                            + "revealed seed produces exactly the stored allotment.");
        }
        return DrawVerificationReport.Check.failed("ALLOTMENT",
                "%d allotments".formatted(expected.size()),
                "%d differences".formatted(differences.size()),
                "The stored allotment is not what the published inputs produce. "
                        + String.join("; ", differences.stream().limit(5).toList())
                        + (differences.size() > 5 ? "; and %d more".formatted(differences.size() - 5) : ""));
    }

    private DrawVerificationReport.Check checkResultHash(Draw draw, AllocationResult recomputed) {
        String hash = recomputed.resultHash();
        return hash.equals(draw.getResultHash())
                ? DrawVerificationReport.Check.passed("RESULT_HASH", hash,
                        "The recomputed allotment hashes to the value published with this draw.")
                : DrawVerificationReport.Check.failed("RESULT_HASH", draw.getResultHash(), hash,
                        "The recomputed allotment does not hash to the published result hash.");
    }

    private static String summary(boolean verified, List<DrawVerificationReport.Check> checks) {
        if (verified) {
            return "This draw is exactly what its published inputs produce. The register, the rules, "
                    + "the seed and the allotment all agree.";
        }
        String failed = checks.stream()
                .filter(check -> !check.passed())
                .map(DrawVerificationReport.Check::name)
                .reduce((a, b) -> a + ", " + b).orElse("");
        return "This draw does NOT match its published inputs. Failed checks: " + failed + ".";
    }

    private static AllocationCandidate toCandidate(FrozenCandidate frozen) {
        return new AllocationCandidate(
                frozen.getApplicationNo(), frozen.getEffectiveCategory(), frozen.getGender(),
                frozen.isEffectiveDisability(), frozen.isEffectiveExServiceperson(),
                frozen.isEffectiveLocalResident(), frozen.isEligible());
    }
}
