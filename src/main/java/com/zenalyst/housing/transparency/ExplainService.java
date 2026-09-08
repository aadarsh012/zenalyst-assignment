package com.zenalyst.housing.transparency;

import com.zenalyst.housing.eligibility.EligibilityService;
import com.zenalyst.housing.identity.DuplicateLink;
import com.zenalyst.housing.identity.DuplicateLinkRepository;
import com.zenalyst.housing.intake.Application;
import com.zenalyst.housing.intake.ApplicationRepository;
import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.registry.InclusionProofResponse;
import com.zenalyst.housing.registry.RegistryFreezeService;
import com.zenalyst.housing.rules.RuleVersion;
import com.zenalyst.housing.rules.RuleVersionRepository;
import com.zenalyst.housing.scheme.Scheme;
import com.zenalyst.housing.scheme.SchemeRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the complete account of what happened to one application.
 *
 * <p>Every fact here is read from storage. Nothing is recomputed, inferred or approximated — the
 * earlier phases exist precisely so that this can be a lookup. An explanation that had to be
 * reconstructed would be an explanation that could differ between two people asking the same
 * question, which is the failure this endpoint exists to prevent.
 */
@Service
public class ExplainService {

    private final ApplicationRepository applications;
    private final SchemeRepository schemes;
    private final DuplicateLinkRepository duplicateLinks;
    private final EligibilityService eligibility;
    private final RegistryFreezeService registry;
    private final RuleVersionRepository ruleVersions;
    private final JdbcTemplate jdbc;

    public ExplainService(
            ApplicationRepository applications,
            SchemeRepository schemes,
            DuplicateLinkRepository duplicateLinks,
            EligibilityService eligibility,
            RegistryFreezeService registry,
            RuleVersionRepository ruleVersions,
            JdbcTemplate jdbc) {
        this.applications = applications;
        this.schemes = schemes;
        this.duplicateLinks = duplicateLinks;
        this.eligibility = eligibility;
        this.registry = registry;
        this.ruleVersions = ruleVersions;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public ExplainResponse explain(String applicationNo) {
        Application application = applications.findByApplicationNo(applicationNo)
                .orElseThrow(() -> ApiException.notFound("application", applicationNo));
        Scheme scheme = schemes.findById(application.getSchemeId())
                .orElseThrow(() -> ApiException.notFound("scheme", application.getSchemeId().toString()));

        ExplainResponse.Identity identity = identityOf(application);
        ExplainResponse.Eligibility eligibilityView = eligibilityOf(applicationNo);

        Optional<DrawRow> drawRow = latestDrawFor(scheme.getId());
        if (drawRow.isEmpty()) {
            return notDrawnYet(applicationNo, identity, eligibilityView);
        }

        DrawRow draw = drawRow.get();
        ExplainResponse.RegistryEntry registryEntry = registryEntryOf(draw, applicationNo);
        Optional<RankRow> rank = rankOf(draw.id(), applicationNo);

        if (rank.isEmpty()) {
            return notInDraw(applicationNo, identity, eligibilityView, registryEntry, draw);
        }

        List<ExplainResponse.PoolStanding> pools = poolStandingsOf(draw.id(), applicationNo);
        Optional<ExplainResponse.Allotment> allotment = allotmentOf(draw.id(), applicationNo);
        Optional<ExplainResponse.Waitlist> waitlist = waitlistOf(draw.id(), applicationNo);

        ExplainResponse.Outcome outcome = allotment.isPresent()
                ? ExplainResponse.Outcome.ALLOTTED
                : waitlist.isPresent()
                        ? ExplainResponse.Outcome.WAITLISTED
                        : ExplainResponse.Outcome.NOT_SELECTED;

        return new ExplainResponse(
                applicationNo, outcome,
                summaryFor(outcome, applicationNo, allotment, waitlist, pools),
                identity, eligibilityView, registryEntry, drawSummaryOf(draw),
                new ExplainResponse.Lottery(rank.get().ticket(),
                        "HMAC-SHA256(key = seed, message = applicationNo), sorted ascending as hexadecimal",
                        rank.get().overallRank(), candidatesInDraw(draw.id())),
                pools, allotment.orElse(null), waitlist.orElse(null),
                verificationSteps(draw, applicationNo, rank.get().ticket()));
    }

    // --- the pieces --------------------------------------------------------

    private ExplainResponse.Identity identityOf(Application application) {
        Optional<DuplicateLink> supersededBy =
                duplicateLinks.findByDuplicateApplicationId(application.getId());

        if (supersededBy.isPresent()) {
            DuplicateLink link = supersededBy.get();
            return new ExplainResponse.Identity(false,
                    applicationNoOf(link.getCanonicalApplicationId()),
                    link.getTier().name(), 0);
        }
        int others = duplicateLinks.findByCanonicalApplicationId(application.getId()).size();
        return new ExplainResponse.Identity(true, null, null, others);
    }

    private ExplainResponse.Eligibility eligibilityOf(String applicationNo) {
        EligibilityService.Assessment assessment = eligibility.assess(applicationNo);
        return new ExplainResponse.Eligibility(
                assessment.decision().eligible(),
                assessment.application().getCategory().name(),
                assessment.decision().effectiveCategory().name(),
                assessment.decision().effectiveLocalResident(),
                assessment.decision().effectiveDisability(),
                assessment.decision().effectiveExServiceperson(),
                assessment.decision().checks());
    }

    private ExplainResponse.RegistryEntry registryEntryOf(DrawRow draw, String applicationNo) {
        try {
            InclusionProofResponse proof = registry.proofOf(draw.registryRoot(), applicationNo);
            return new ExplainResponse.RegistryEntry(
                    draw.registryRoot(), proof.leafIndex(), proof.candidateCount(),
                    proof.canonicalJson(), proof.leafHash(), proof.proof());
        } catch (ApiException e) {
            // Not in the frozen register at all — an application made after the freeze.
            return new ExplainResponse.RegistryEntry(draw.registryRoot(), -1, 0, null, null, List.of());
        }
    }

    /**
     * Every pool this applicant competed in, with where they came and where the cutoff fell.
     *
     * <p>An applicant competes in the open pool always, and in their category's pool if they have
     * one. Reporting both is the difference between "you were not selected" and "you were 412th of
     * 800 for the open seats, where the cutoff was 300, and 96th for OBC, where it was 90".
     */
    private List<ExplainResponse.PoolStanding> poolStandingsOf(UUID drawId, String applicationNo) {
        List<ExplainResponse.PoolStanding> standings = new ArrayList<>();

        jdbc.query("""
                SELECT p.pool, p.seats, p.awarded, p.competitors, p.cutoff_pool_rank,
                       a.pool_rank AS allotted_rank,
                       w.pool_rank AS waiting_rank
                FROM draw_pool p
                LEFT JOIN allotment a
                       ON a.draw_id = p.draw_id AND a.pool = p.pool AND a.application_no = ?
                LEFT JOIN draw_waitlist w
                       ON w.draw_id = p.draw_id AND w.pool = p.pool AND w.application_no = ?
                WHERE p.draw_id = ?
                ORDER BY p.pool
                """,
                rs -> {
                    Integer allottedRank = (Integer) rs.getObject("allotted_rank");
                    Integer waitingRank = (Integer) rs.getObject("waiting_rank");
                    if (allottedRank == null && waitingRank == null) {
                        return;   // did not compete in this pool
                    }
                    boolean selected = allottedRank != null;
                    int poolRank = selected ? allottedRank : waitingRank;
                    int cutoff = rs.getInt("cutoff_pool_rank");

                    standings.add(new ExplainResponse.PoolStanding(
                            rs.getString("pool"), poolRank, rs.getInt("seats"), rs.getInt("awarded"),
                            rs.getInt("competitors"), cutoff, selected,
                            selected
                                    ? "Selected in this pool."
                                    : "Ranked %d; the last seat awarded on merit went to rank %d."
                                            .formatted(poolRank, cutoff)));
                },
                applicationNo, applicationNo, drawId);

        return standings;
    }

    private Optional<ExplainResponse.Allotment> allotmentOf(UUID drawId, String applicationNo) {
        List<ExplainResponse.Allotment> found = jdbc.query("""
                SELECT pool, basis, pool_rank, horizontal_category
                FROM allotment WHERE draw_id = ? AND application_no = ?
                """,
                (rs, rowNum) -> new ExplainResponse.Allotment(
                        rs.getString("pool"), rs.getString("basis"),
                        rs.getInt("pool_rank"), rs.getString("horizontal_category")),
                drawId, applicationNo);
        return found.stream().findFirst();
    }

    private Optional<ExplainResponse.Waitlist> waitlistOf(UUID drawId, String applicationNo) {
        List<ExplainResponse.Waitlist> found = jdbc.query("""
                SELECT pool, position, pool_rank FROM draw_waitlist
                WHERE draw_id = ? AND application_no = ?
                ORDER BY position LIMIT 1
                """,
                (rs, rowNum) -> new ExplainResponse.Waitlist(
                        rs.getString("pool"), rs.getInt("position"),
                        rs.getInt("pool_rank"), rs.getInt("position") - 1),
                drawId, applicationNo);
        return found.stream().findFirst();
    }

    private Optional<RankRow> rankOf(UUID drawId, String applicationNo) {
        return jdbc.query("""
                SELECT overall_rank, ticket FROM draw_ranking
                WHERE draw_id = ? AND application_no = ?
                """,
                (rs, rowNum) -> new RankRow(rs.getInt("overall_rank"), rs.getString("ticket")),
                drawId, applicationNo).stream().findFirst();
    }

    private int candidatesInDraw(UUID drawId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM draw_ranking WHERE draw_id = ?", Integer.class, drawId);
        return count == null ? 0 : count;
    }

    /** The draw an applicant is asking about: the published one, or the latest completed one. */
    private Optional<DrawRow> latestDrawFor(UUID schemeId) {
        return jdbc.query("""
                SELECT id, status, registry_root, rules_hash, rule_version_id, seed_source,
                       seed_commitment, beacon_round, seed, result_hash
                FROM draw
                WHERE scheme_id = ? AND status IN ('PUBLISHED', 'COMPLETED')
                ORDER BY CASE status WHEN 'PUBLISHED' THEN 0 ELSE 1 END, committed_at DESC
                LIMIT 1
                """,
                (rs, rowNum) -> new DrawRow(
                        rs.getObject("id", UUID.class), rs.getString("status"),
                        rs.getString("registry_root"), rs.getString("rules_hash"),
                        rs.getObject("rule_version_id", UUID.class), rs.getString("seed_source"),
                        rs.getString("seed_commitment"), (Long) rs.getObject("beacon_round"),
                        rs.getString("seed"), rs.getString("result_hash")),
                schemeId).stream().findFirst();
    }

    private ExplainResponse.DrawSummary drawSummaryOf(DrawRow draw) {
        String version = ruleVersions.findById(draw.ruleVersionId())
                .map(RuleVersion::getVersion).orElse("unknown");
        return new ExplainResponse.DrawSummary(
                draw.id().toString(), draw.status(), version, draw.rulesHash(),
                draw.seedSource(), draw.seedCommitment(), draw.beaconRound(),
                draw.seed(), draw.resultHash());
    }

    // --- wording -----------------------------------------------------------

    private String summaryFor(
            ExplainResponse.Outcome outcome, String applicationNo,
            Optional<ExplainResponse.Allotment> allotment,
            Optional<ExplainResponse.Waitlist> waitlist,
            List<ExplainResponse.PoolStanding> pools) {

        return switch (outcome) {
            case ALLOTTED -> {
                ExplainResponse.Allotment award = allotment.orElseThrow();
                yield "MERIT".equals(award.basis())
                        ? "You have been allotted a flat. You were ranked %d in the %s pool, which is within its seats."
                                .formatted(award.poolRank(), award.pool())
                        : ("You have been allotted a flat in the %s pool, under the %s reservation. You were ranked "
                                + "%d, below the pool's merit cutoff, and were selected because the reservation "
                                + "had not been filled on merit alone.")
                                .formatted(award.pool(), award.horizontalCategory(), award.poolRank());
            }
            case WAITLISTED -> {
                ExplainResponse.Waitlist waiting = waitlist.orElseThrow();
                yield ("You have not been allotted a flat. You are number %d on the waiting list for the %s pool, "
                        + "where you were ranked %d. If %s ahead of you give up a flat, yours is the next offer.")
                        .formatted(waiting.position(), waiting.pool(), waiting.poolRank(),
                                waiting.aheadOfYou() == 0 ? "nobody" : "the %d".formatted(waiting.aheadOfYou()));
            }
            case NOT_SELECTED -> {
                String detail = pools.stream()
                        .map(pool -> "%s (you: %d, cutoff: %d)".formatted(pool.pool(), pool.poolRank(), pool.cutoffPoolRank()))
                        .reduce((a, b) -> a + "; " + b).orElse("no pool");
                yield "You have not been allotted a flat. You competed in: " + detail + ".";
            }
            default -> "This application did not take part in the draw.";
        };
    }

    private ExplainResponse notDrawnYet(
            String applicationNo, ExplainResponse.Identity identity, ExplainResponse.Eligibility eligibility) {
        return new ExplainResponse(applicationNo, ExplainResponse.Outcome.NO_DRAW_YET,
                "This scheme has not held its draw yet. Everything below is your standing as it will "
                        + "enter the draw when it is held.",
                identity, eligibility, null, null, null, List.of(), null, null,
                List.of("Come back once the draw has been held."));
    }

    private ExplainResponse notInDraw(
            String applicationNo, ExplainResponse.Identity identity,
            ExplainResponse.Eligibility eligibility, ExplainResponse.RegistryEntry registryEntry,
            DrawRow draw) {

        String reason = !identity.isCanonical()
                ? "Application %s was found to be another submission by you, and it is the one that "
                        .formatted(applicationNo)
                        + "stands in the draw. Application %s competed on your behalf."
                                .formatted(identity.canonicalApplicationNo())
                : eligibility.eligible()
                        ? "This application was not among the candidates frozen for this draw."
                        : "This application was found ineligible, so it did not compete. The checks "
                                + "below say which rule was not met.";

        return new ExplainResponse(applicationNo, ExplainResponse.Outcome.NOT_IN_DRAW, reason,
                identity, eligibility, registryEntry, drawSummaryOf(draw), null,
                List.of(), null, null,
                List.of("Your row's presence or absence in the frozen register is provable from the "
                        + "published registry root: " + draw.registryRoot()));
    }

    /** What the applicant can check without taking our word for any of it. */
    private List<String> verificationSteps(DrawRow draw, String applicationNo, String ticket) {
        List<String> steps = new ArrayList<>();
        steps.add("Your place in the draw is HMAC-SHA256(key = the published seed, message = \"%s\") = %s. "
                .formatted(applicationNo, ticket)
                + "Compute it yourself; it depends on nothing but those two values.");
        steps.add("Your row was among the inputs: hash the canonicalJson above and fold it up through "
                + "the inclusionProof to reach the registry root " + draw.registryRoot() + ".");
        if ("DRAND_BEACON".equals(draw.seedSource())) {
            steps.add("The seed is drand round %d. Fetch https://api.drand.sh/public/%d — its randomness "
                    .formatted(draw.beaconRound(), draw.beaconRound())
                    + "is the seed, and the round number was published before the round existed.");
        } else {
            steps.add("The seed was committed to before it was known: SHA-256(seed + \":\" + salt) equals "
                    + "the commitment " + draw.seedCommitment() + ", published when the draw was created.");
        }
        steps.add("The whole draw can be re-derived: POST /api/v1/draws/%s/verify".formatted(draw.id()));
        return steps;
    }

    private String applicationNoOf(UUID applicationId) {
        return applications.findById(applicationId)
                .map(Application::getApplicationNo)
                .orElse("another application");
    }

    private record DrawRow(
            UUID id, String status, String registryRoot, String rulesHash, UUID ruleVersionId,
            String seedSource, String seedCommitment, Long beaconRound, String seed, String resultHash) {
    }

    private record RankRow(int overallRank, String ticket) {
    }
}
