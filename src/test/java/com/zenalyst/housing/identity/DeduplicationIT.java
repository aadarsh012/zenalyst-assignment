package com.zenalyst.housing.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenalyst.housing.intake.IntakeFixtures;
import com.zenalyst.housing.intake.SubmitApplicationRequest;
import com.zenalyst.housing.platform.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The scenario the brief describes: "a fair number applied twice because they were not sure the
 * first one went through."
 *
 * <p>One person, four applications, arriving by three different routes to being recognised as the
 * same person — and a fifth applicant who merely shares a birthday and must not be swept up with
 * them.
 */
class DeduplicationIT extends AbstractIntegrationTest {

    private static final String DOB = "1990-02-01";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * A scheme per test method.
     *
     * <p>Integration tests share one database, and deduplication reasons over an entire scheme.
     * Methods sharing a scheme would see each other's applications — and because these fixtures
     * deliberately reuse identity numbers, every test after the first would find the previous
     * test's applications and merge them. Isolating by scheme keeps each test's register to
     * exactly the applications it submitted.
     */
    private String scheme;

    @BeforeEach
    void createScheme(TestInfo testInfo) {
        scheme = "DEDUP-" + testInfo.getTestMethod().orElseThrow().getName();
        IntakeFixtures.openScheme(jdbc, scheme);
    }

    // --- the fixture -------------------------------------------------------

    /** First application. Earliest submission, so this is the one that should survive. */
    private String submitFirstApplication() {
        return submit("Ramesh Chandra Kumar", DOB, IntakeFixtures.VALID_ID_1,
                "9876543210", "ramesh@example.com");
    }

    /** Same identity number, different contact details. Matches at the strongest tier. */
    private String submitSameIdentityNumber() {
        return submit("Ramesh Chandra Kumar", DOB, IntakeFixtures.VALID_ID_1,
                "9000000001", "ramesh.other@example.com");
    }

    /** Different identity number, same name, birthday and phone. Matches at the second tier. */
    private String submitSameNameDobPhone() {
        return submit("Ramesh Chandra Kumar", DOB, IntakeFixtures.VALID_ID_2,
                "9876543210", "ramesh.third@example.com");
    }

    /** One letter out, nothing else in common. Similarity 0.87 — a question, not an answer. */
    private String submitMisspelled() {
        return submit("Ramesh Chandra Kumarr", DOB, IntakeFixtures.VALID_ID_3,
                "9000000004", "ramesh.fourth@example.com");
    }

    /** Same birthday, genuinely different person. Similarity 0.6 — below the threshold. */
    private String submitDifferentPerson() {
        return submit("Rajesh Kumar", DOB, IntakeFixtures.VALID_ID_4,
                "9000000005", "rajesh@example.com");
    }

    // --- tests -------------------------------------------------------------

    @Test
    @DisplayName("an exact identity-number match is merged without asking anyone")
    void exactIdentityMatchMergesAutomatically() {
        String first = submitFirstApplication();
        String second = submitSameIdentityNumber();

        DeduplicationReportView report = run();

        assertThat(report.applicationsLinked()).isEqualTo(1);
        assertThat(report.duplicateGroups()).isEqualTo(1);
        assertThat(identity(first).role()).isEqualTo("CANONICAL");
        assertThat(identity(second).role()).isEqualTo("DUPLICATE");
        assertThat(identity(second).canonicalApplicationNo()).isEqualTo(first);
        assertThat(identity(second).matchedAtTier()).isEqualTo("GOVERNMENT_ID");
    }

    @Test
    @DisplayName("identity is transitive across different tiers")
    void transitiveAcrossTiers() {
        String first = submitFirstApplication();
        String byIdentityNumber = submitSameIdentityNumber();
        String byPhone = submitSameNameDobPhone();

        DeduplicationReportView report = run();

        // The second and third applications share nothing with each other: different identity
        // numbers, different phone numbers, different emails. Each matches only the first. If
        // pairs were merged independently this would be two applicants, and one person would
        // hold two entries in the draw.
        assertThat(report.duplicateGroups()).isEqualTo(1);
        assertThat(report.applicationsLinked()).isEqualTo(2);
        assertThat(report.distinctApplicants()).isEqualTo(1);

        assertThat(identity(first).role()).isEqualTo("CANONICAL");
        assertThat(identity(byIdentityNumber).canonicalApplicationNo()).isEqualTo(first);
        assertThat(identity(byPhone).canonicalApplicationNo()).isEqualTo(first);
    }

    @Test
    @DisplayName("a near-miss name is queued for a human and never merged on its own")
    void fuzzyMatchIsQueuedNotMerged() {
        String first = submitFirstApplication();
        String misspelled = submitMisspelled();

        DeduplicationReportView report = run();

        // Nothing merged. Silently discarding a citizen's application because two strings looked
        // alike is exactly what loses in court.
        assertThat(report.applicationsLinked()).isZero();
        assertThat(report.reviewsRaised()).isEqualTo(1);

        assertThat(identity(misspelled).role()).isEqualTo("CANONICAL");
        assertThat(identity(first).role()).isEqualTo("CANONICAL");

        List<ReviewSummary> queue = reviewQueue("PENDING");
        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).similarity()).isEqualTo("0.870");
        assertThat(List.of(queue.get(0).applicationNoA(), queue.get(0).applicationNoB()))
                .containsExactlyInAnyOrder(first, misspelled);
    }

    @Test
    @DisplayName("a different person who shares a birthday is left alone entirely")
    void differentPersonIsNotFlagged() {
        submitFirstApplication();
        submitDifferentPerson();

        DeduplicationReportView report = run();

        assertThat(report.applicationsLinked()).isZero();
        assertThat(report.reviewsRaised()).isZero();
        assertThat(report.distinctApplicants()).isEqualTo(2);
    }

    @Test
    @DisplayName("one review is raised per pair of people, not per pair of applications")
    void oneReviewPerPairOfPeople() {
        submitFirstApplication();
        submitSameIdentityNumber();
        submitSameNameDobPhone();
        submitMisspelled();

        DeduplicationReportView report = run();

        // The misspelled application resembles all three earlier ones equally. Queued naively
        // that is three rows asking the operator one question.
        assertThat(report.reviewsRaised()).isEqualTo(1);
        assertThat(reviewQueue("PENDING")).hasSize(1);
    }

    @Test
    @DisplayName("running the pass again changes nothing")
    void reRunningIsIdempotent() {
        submitFirstApplication();
        submitSameIdentityNumber();
        submitSameNameDobPhone();
        submitMisspelled();

        DeduplicationReportView first = run();
        DeduplicationReportView second = run();

        assertThat(second.applicationsLinked()).isEqualTo(first.applicationsLinked());
        assertThat(second.duplicateGroups()).isEqualTo(first.duplicateGroups());
        assertThat(second.distinctApplicants()).isEqualTo(first.distinctApplicants());
        // The pending review is not raised a second time.
        assertThat(second.reviewsRaised()).isZero();
        assertThat(second.reviewsPending()).isEqualTo(first.reviewsPending());
    }

    @Test
    @DisplayName("confirming a review links the applications immediately")
    void confirmingAReviewLinksImmediately() {
        String first = submitFirstApplication();
        String misspelled = submitMisspelled();
        run();

        String reviewId = reviewQueue("PENDING").get(0).reviewId();
        DeduplicationReportView afterDecision = decide(reviewId, "CONFIRMED_DUPLICATE",
                "Same handwriting on both counter forms; confirmed with applicant by phone.");

        // The report comes back from the decision itself, so the operator sees the effect rather
        // than having to go and look for it.
        assertThat(afterDecision.applicationsLinked()).isEqualTo(1);
        assertThat(identity(misspelled).role()).isEqualTo("DUPLICATE");
        assertThat(identity(misspelled).canonicalApplicationNo()).isEqualTo(first);
        assertThat(identity(misspelled).matchedAtTier()).isEqualTo("PROBABLE");
    }

    @Test
    @DisplayName("a rejected review is never raised again")
    void rejectedReviewsStayRejected() {
        submitFirstApplication();
        submitMisspelled();
        run();

        String reviewId = reviewQueue("PENDING").get(0).reviewId();
        decide(reviewId, "NOT_DUPLICATE", "Twin brothers, different identity numbers, verified.");

        DeduplicationReportView afterRerun = run();

        // Without a memory of the decision, this pair would come back every run until an
        // operator eventually gave in and confirmed it.
        assertThat(afterRerun.reviewsRaised()).isZero();
        assertThat(afterRerun.reviewsPending()).isZero();
        assertThat(afterRerun.applicationsLinked()).isZero();
        assertThat(reviewQueue("NOT_DUPLICATE")).hasSize(1);
    }

    @Test
    @DisplayName("a review cannot be decided twice")
    void reviewsAreDecidedOnce() {
        submitFirstApplication();
        submitMisspelled();
        run();

        String reviewId = reviewQueue("PENDING").get(0).reviewId();
        decide(reviewId, "NOT_DUPLICATE", "First decision.");

        ResponseEntity<String> second = rest.exchange(
                "/api/v1/duplicate-reviews/" + reviewId + "/decision?decidedBy=operator-2",
                HttpMethod.POST, jsonEntity("{\"outcome\":\"CONFIRMED_DUPLICATE\"}"), String.class);

        // Overwriting would erase that two operators disagreed, which is exactly the thing an
        // applicant contesting the outcome would want to know.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("already decided");
    }

    @Test
    @DisplayName("a duplicate application explains itself to its applicant")
    void duplicateApplicationExplainsItself() {
        String first = submitFirstApplication();
        String second = submitSameIdentityNumber();
        run();

        IdentityView duplicate = identity(second);
        assertThat(duplicate.reason())
                .contains("same identity number")
                .contains(first);

        IdentityView canonical = identity(first);
        assertThat(canonical.reason()).contains("competes in the draw");
        assertThat(canonical.duplicates()).hasSize(1);
    }

    @Test
    @DisplayName("the duplicate application still exists and is still readable")
    void duplicatesAreNeverDeleted() {
        submitFirstApplication();
        String second = submitSameIdentityNumber();
        run();

        // Four thousand people applied; the published result must account for all four thousand.
        ResponseEntity<String> stillThere =
                rest.getForEntity("/api/v1/applications/" + second, String.class);
        assertThat(stillThere.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stillThere.getBody()).contains(second);
    }

    @Test
    @DisplayName("the pass and every human decision are recorded in the audit chain")
    void everythingIsAudited() {
        submitFirstApplication();
        submitMisspelled();
        run();

        String reviewId = reviewQueue("PENDING").get(0).reviewId();
        decide(reviewId, "CONFIRMED_DUPLICATE", "Confirmed at the counter.");

        Integer passes = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action = 'DEDUPLICATION_COMPLETED' AND subject_id = ?",
                Integer.class, scheme);
        assertThat(passes).isGreaterThanOrEqualTo(1);

        String decision = jdbc.queryForObject("""
                SELECT payload::text FROM audit_event
                WHERE action = 'DUPLICATE_REVIEW_DECIDED' AND subject_id = ?
                """, String.class, reviewId);
        assertThat(decision)
                .contains("CONFIRMED_DUPLICATE")
                .contains("Confirmed at the counter");

        String actor = jdbc.queryForObject("""
                SELECT actor FROM audit_event
                WHERE action = 'DUPLICATE_REVIEW_DECIDED' AND subject_id = ?
                """, String.class, reviewId);
        assertThat(actor).isEqualTo("operator-1");
    }

    // --- helpers -----------------------------------------------------------

    private String submit(String name, String dob, String governmentId, String phone, String email) {
        SubmitApplicationRequest request = new SubmitApplicationRequest(
                name, dob, governmentId, phone, email, "12 Nehru Road", "W-07",
                "OBC", "MALE", "true", "false", "false", "250000");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/schemes/" + scheme + "/applications", HttpMethod.POST,
                jsonEntity(IntakeFixtures.json(request)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return read(response.getBody()).get("applicationNo").asText();
    }

    private DeduplicationReportView run() {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/schemes/" + scheme + "/deduplication:run?runBy=operator-1", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return asReport(response.getBody());
    }

    private DeduplicationReportView decide(String reviewId, String outcome, String note) {
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/duplicate-reviews/" + reviewId + "/decision?decidedBy=operator-1",
                HttpMethod.POST,
                jsonEntity("{\"outcome\":\"%s\",\"note\":\"%s\"}".formatted(outcome, note)),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return asReport(response.getBody());
    }

    private List<ReviewSummary> reviewQueue(String status) {
        ResponseEntity<ReviewSummary[]> response = rest.getForEntity(
                "/api/v1/schemes/" + scheme + "/duplicate-reviews?status=" + status, ReviewSummary[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return List.of(response.getBody());
    }

    private IdentityView identity(String applicationNo) {
        ResponseEntity<String> response = rest.getForEntity(
                "/api/v1/applications/" + applicationNo + "/identity", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode node = read(response.getBody());
        return new IdentityView(
                node.path("role").asText(),
                node.path("reason").asText(),
                node.path("canonicalApplicationNo").asText(null),
                node.path("matchedAtTier").asText(null),
                node.path("duplicates"));
    }

    private DeduplicationReportView asReport(String body) {
        JsonNode node = read(body);
        return new DeduplicationReportView(
                node.path("applicationsExamined").asInt(),
                node.path("distinctApplicants").asInt(),
                node.path("duplicateGroups").asInt(),
                node.path("applicationsLinked").asInt(),
                node.path("reviewsRaised").asInt(),
                node.path("reviewsPending").asInt());
    }

    private HttpEntity<String> jsonEntity(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private JsonNode read(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable response: " + body, e);
        }
    }

    private record DeduplicationReportView(
            int applicationsExamined, int distinctApplicants, int duplicateGroups,
            int applicationsLinked, int reviewsRaised, int reviewsPending) {
    }

    private record IdentityView(
            String role, String reason, String canonicalApplicationNo,
            String matchedAtTier, JsonNode duplicates) {
    }
}
