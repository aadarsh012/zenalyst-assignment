package com.zenalyst.housing.identity;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.audit.AuditAction;
import com.zenalyst.housing.audit.AuditWriter;
import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.platform.hash.CanonicalJson;
import com.zenalyst.housing.scheme.Scheme;
import com.zenalyst.housing.scheme.SchemeRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Works out which applications belong to the same person.
 *
 * <h2>A pass, not a hook</h2>
 *
 * <p>Deduplication runs over the whole register rather than checking each application as it
 * arrives, for two reasons. Duplicates arrive out of order — application 4,000 may be a duplicate
 * of application 12, and an arrival-time check only ever sees the past. And a pass can be re-run:
 * the same register produces the same conclusions every time, which is what makes the result
 * something to be audited rather than an accident of the order the post arrived in.
 *
 * <h2>Links are rebuilt, decisions are kept</h2>
 *
 * <p>Each run deletes the scheme's links and recomputes them from the fingerprint matches plus the
 * fuzzy matches a human has confirmed. That sounds destructive and is not: the links are a
 * <em>projection</em>. What a person decided lives in {@link DuplicateReview}, what the system did
 * lives in the audit chain, and both survive.
 *
 * <p>Rebuilding is also what handles the case that would otherwise corrupt the register. A paper
 * form handed in on 3 March but typed up in April predates the online application currently
 * standing for that person; once it exists, the canonical application must change. Rebuilding
 * makes that automatic. Incremental linking would have left the register asserting that the later
 * application was the original.
 */
@Service
public class DeduplicationService {

    private final SchemeRepository schemes;
    private final FingerprintStore fingerprints;
    private final FuzzyMatchFinder fuzzyMatches;
    private final DuplicateLinkRepository links;
    private final DuplicateReviewRepository reviews;
    private final AuditWriter audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public DeduplicationService(
            SchemeRepository schemes,
            FingerprintStore fingerprints,
            FuzzyMatchFinder fuzzyMatches,
            DuplicateLinkRepository links,
            DuplicateReviewRepository reviews,
            AuditWriter audit,
            JdbcTemplate jdbc,
            Clock clock) {
        this.schemes = schemes;
        this.fingerprints = fingerprints;
        this.fuzzyMatches = fuzzyMatches;
        this.links = links;
        this.reviews = reviews;
        this.audit = audit;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional
    public DeduplicationReport run(String schemeCode, String actor) {
        Scheme scheme = schemes.findByCode(schemeCode)
                .orElseThrow(() -> ApiException.notFound("scheme", schemeCode));
        Instant now = clock.instant();

        fingerprints.refresh(scheme.getId());

        Map<UUID, ApplicationRef> register = loadRegister(scheme.getId());
        MergeGraph graph = new MergeGraph();
        register.values().forEach(graph::add);

        // Strongest tier seen for each application, so a link can say what actually matched
        // rather than naming whichever tier happened to be processed last.
        Map<UUID, MatchTier> strongestTier = new HashMap<>();
        Map<UUID, Set<UUID>> matchedWith = new HashMap<>();

        List<ExactMatch> exact = fingerprints.exactMatches(scheme.getId());
        for (ExactMatch match : exact) {
            graph.link(match.applicationAId(), match.applicationBId());
            recordEvidence(strongestTier, matchedWith, match.applicationAId(), match.applicationBId(), match.tier());
            recordEvidence(strongestTier, matchedWith, match.applicationBId(), match.applicationAId(), match.tier());
        }

        // Human-confirmed fuzzy matches join the graph on equal footing with the exact tiers.
        // They are not stored as links directly, so that one rebuild reconciles both sources.
        for (DuplicateReview confirmed
                : reviews.findBySchemeIdAndStatus(scheme.getId(), ReviewStatus.CONFIRMED_DUPLICATE)) {
            graph.link(confirmed.getApplicationAId(), confirmed.getApplicationBId());
            recordEvidence(strongestTier, matchedWith,
                    confirmed.getApplicationAId(), confirmed.getApplicationBId(), MatchTier.PROBABLE);
            recordEvidence(strongestTier, matchedWith,
                    confirmed.getApplicationBId(), confirmed.getApplicationAId(), MatchTier.PROBABLE);
        }

        List<MergeGroup> groups = graph.groups();
        links.deleteBySchemeId(scheme.getId());
        links.flush();

        List<DuplicateGroupSummary> summaries = new ArrayList<>();
        int linked = 0;
        for (MergeGroup group : groups) {
            List<DuplicateGroupSummary.LinkedApplication> members = new ArrayList<>();
            for (ApplicationRef duplicate : group.duplicates()) {
                MatchTier tier = strongestTier.getOrDefault(duplicate.id(), MatchTier.PROBABLE);
                links.save(DuplicateLink.of(
                        scheme.getId(), group.canonical().id(), duplicate.id(), tier,
                        evidence(group.canonical(), duplicate, tier, matchedWith, register),
                        actor, now));
                members.add(new DuplicateGroupSummary.LinkedApplication(
                        duplicate.applicationNo(), tier, tier.description()));
                linked++;
            }
            summaries.add(new DuplicateGroupSummary(group.canonical().applicationNo(), members));
        }

        int raised = raiseFuzzyReviews(scheme, graph, register, now);

        DeduplicationReport report = new DeduplicationReport(
                scheme.getCode(), now,
                register.size(),
                register.size() - linked,
                exact.size(),
                groups.size(),
                linked,
                raised,
                (int) reviews.countBySchemeIdAndStatus(scheme.getId(), ReviewStatus.PENDING),
                summaries);

        audit.append(actor, AuditAction.DEDUPLICATION_COMPLETED, "scheme", scheme.getCode(),
                auditPayload(report));
        return report;
    }

    /**
     * Queues fuzzy pairs for a human, at most one per pair of people.
     *
     * <p>Three kinds of candidate are skipped, and the third is the one that makes this usable.
     *
     * <p>Pairs already known to be the same person by a stronger tier are skipped — there is
     * nothing left to decide.
     *
     * <p>Pairs a human has already ruled on are skipped, whichever way they ruled. A rejection is
     * a decision, and re-asking it every run until someone gives in and confirms is not a review
     * process.
     *
     * <p>And <strong>candidates are collapsed to one review per pair of people, not per pair of
     * applications.</strong> Someone who mistypes their name while applying for the fourth time
     * resembles all three of their earlier applications equally. Queued naively that is three
     * rows asking one question, and the operator answers it three times — or, worse, answers it
     * differently on the third. Collapsing to the underlying people asks once. The pair kept is
     * the highest-scoring one, since the candidate list arrives ordered by score.
     */
    private int raiseFuzzyReviews(
            Scheme scheme, MergeGraph graph, Map<UUID, ApplicationRef> register, Instant now) {

        Map<UUID, UUID> canonicalOf = new HashMap<>();
        for (MergeGroup group : graph.groups()) {
            canonicalOf.put(group.canonical().id(), group.canonical().id());
            group.duplicates().forEach(duplicate -> canonicalOf.put(duplicate.id(), group.canonical().id()));
        }

        // Existing reviews are mapped through today's groupings, so a pair rejected before the
        // two applications joined larger groups still counts as decided for those groups.
        Set<String> settledPeoplePairs = new HashSet<>();
        for (DuplicateReview existing : reviews.findBySchemeIdOrderByRaisedAtAsc(scheme.getId())) {
            settledPeoplePairs.add(peopleKey(canonicalOf, existing.getApplicationAId(), existing.getApplicationBId()));
        }

        int raised = 0;
        for (FuzzyCandidate candidate : fuzzyMatches.candidates(scheme.getId())) {
            UUID a = candidate.applicationAId();
            UUID b = candidate.applicationBId();

            String peopleKey = peopleKey(canonicalOf, a, b);
            if (peopleKey == null) {
                continue;   // already one person by a stronger tier
            }
            if (!settledPeoplePairs.add(peopleKey)) {
                continue;   // this question has already been asked, or already answered
            }

            FuzzyCandidate stored = candidate.inIdOrder();
            reviews.save(DuplicateReview.raise(
                    scheme.getId(), stored.applicationAId(), stored.applicationBId(),
                    stored.similarity(), fuzzyEvidence(stored), now));
            raised++;
        }
        return raised;
    }

    /**
     * @return a stable key for the two <em>people</em> the applications belong to, or {@code null}
     *         if both belong to the same person and there is therefore nothing to review
     */
    private static String peopleKey(Map<UUID, UUID> canonicalOf, UUID a, UUID b) {
        UUID personA = canonicalOf.getOrDefault(a, a);
        UUID personB = canonicalOf.getOrDefault(b, b);
        return personA.equals(personB) ? null : ApplicationIds.pairKey(personA, personB);
    }

    private Map<UUID, ApplicationRef> loadRegister(UUID schemeId) {
        Map<UUID, ApplicationRef> register = new java.util.LinkedHashMap<>();
        jdbc.query("""
                SELECT id, application_no, submitted_at
                FROM application
                WHERE scheme_id = ?
                ORDER BY submitted_at, application_no
                """,
                rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    register.put(id, new ApplicationRef(
                            id,
                            rs.getString("application_no"),
                            rs.getTimestamp("submitted_at").toInstant()));
                },
                schemeId);
        return register;
    }

    private static void recordEvidence(
            Map<UUID, MatchTier> strongestTier, Map<UUID, Set<UUID>> matchedWith,
            UUID application, UUID other, MatchTier tier) {

        // Enum order is strongest first, so a lower ordinal wins.
        strongestTier.merge(application, tier,
                (existing, candidate) -> existing.ordinal() <= candidate.ordinal() ? existing : candidate);
        matchedWith.computeIfAbsent(application, key -> new java.util.LinkedHashSet<>()).add(other);
    }

    private String evidence(
            ApplicationRef canonical, ApplicationRef duplicate, MatchTier tier,
            Map<UUID, Set<UUID>> matchedWith, Map<UUID, ApplicationRef> register) {

        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("canonicalApplicationNo", canonical.applicationNo());
        node.put("duplicateApplicationNo", duplicate.applicationNo());
        node.put("reason", tier.description());
        node.put("tier", tier.name());

        ArrayNode directMatches = node.putArray("matchedDirectlyWith");
        matchedWith.getOrDefault(duplicate.id(), Set.of()).stream()
                .map(register::get)
                .filter(java.util.Objects::nonNull)
                .map(ApplicationRef::applicationNo)
                .sorted()
                .forEach(directMatches::add);

        return CanonicalJson.render(node);
    }

    private String fuzzyEvidence(FuzzyCandidate candidate) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("applicationNoA", candidate.applicationNoA());
        node.put("applicationNoB", candidate.applicationNoB());
        node.put("dateOfBirth", candidate.dateOfBirth().toString());
        node.put("nameKeyA", candidate.nameKeyA());
        node.put("nameKeyB", candidate.nameKeyB());
        node.put("similarity", candidate.similarity());
        node.put("threshold", fuzzyMatches.threshold());
        return CanonicalJson.render(node);
    }

    private ObjectNode auditPayload(DeduplicationReport report) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("applicationsExamined", report.applicationsExamined());
        payload.put("applicationsLinked", report.applicationsLinked());
        payload.put("distinctApplicants", report.distinctApplicants());
        payload.put("duplicateGroups", report.duplicateGroups());
        payload.put("exactMatchPairs", report.exactMatchPairs());
        payload.put("reviewsPending", report.reviewsPending());
        payload.put("reviewsRaised", report.reviewsRaised());
        payload.put("schemeCode", report.schemeCode());
        return payload;
    }

}
