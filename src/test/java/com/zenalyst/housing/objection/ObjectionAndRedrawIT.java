package com.zenalyst.housing.objection;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenalyst.housing.draw.DrawExecutionService;
import com.zenalyst.housing.intake.IntakeFixtures;
import com.zenalyst.housing.intake.SubmitApplicationRequest;
import com.zenalyst.housing.normalisation.GovernmentId;
import com.zenalyst.housing.platform.AbstractIntegrationTest;
import java.util.UUID;
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
 * Objections, and correcting a published result without editing it.
 *
 * <p>The scenario throughout: an applicant claims SC, nobody verifies the certificate before the
 * freeze, so they compete as GEN and miss out. They object. The objection is upheld, the
 * certificate is verified, the register re-frozen, and a second draw supersedes the first — which
 * remains published, intact and verifiable.
 */
class ObjectionAndRedrawIT extends AbstractIntegrationTest {

    private static final int FLATS = 10;
    private static final int POPULATION = 30;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DrawExecutionService execution;

    private String scheme;
    private String firstDrawId;
    private String objectorApplicationNo;

    @BeforeEach
    void createScheme(TestInfo testInfo) {
        scheme = "OBJ-" + testInfo.getTestMethod().orElseThrow().getName();
        jdbc.update("""
                INSERT INTO scheme (code, name, total_flats, applications_open_at, applications_close_at, status)
                VALUES (?, ?, ?, TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2027-01-01 00:00:00+00', 'OPEN')
                ON CONFLICT (code) DO NOTHING
                """, scheme, "Objections " + scheme, FLATS);
    }

    // --- filing and adjudicating -------------------------------------------

    @Test
    @DisplayName("anybody may object, and is told what happens next")
    void anObjectionCanBeFiled() {
        runFirstDraw();

        JsonNode objection = file(objectorApplicationNo, "CATEGORY_CLAIM_WRONGLY_REFUSED",
                "My scheduled caste certificate was submitted at the counter on 3 March and was "
                        + "never recorded as verified, so I competed in the general category.");

        assertThat(objection.path("status").asText()).isEqualTo("OPEN");
        assertThat(objection.path("drawId").asText()).isEqualTo(firstDrawId);
        assertThat(objection.path("whatHappensNext").asText()).contains("awaiting a decision");
    }

    @Test
    @DisplayName("an objection about the conduct of the draw needs no application at all")
    void aJournalistMayObject() {
        runFirstDraw();

        JsonNode objection = file(null, "DRAW_IMPROPERLY_CONDUCTED",
                "The published registry root does not match the candidate rows served by the API, "
                        + "according to my own reconstruction of the Merkle tree.");

        assertThat(objection.path("status").asText()).isEqualTo("OPEN");
        assertThat(objection.has("applicationNo")).isFalse();
    }

    @Test
    @DisplayName("an objection must say something; a blank one is refused")
    void anObjectionMustBeSubstantive() {
        runFirstDraw();

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/schemes/" + scheme + "/objections?filedBy=applicant",
                HttpMethod.POST,
                json("{\"ground\":\"OTHER\",\"statement\":\"unfair\"}"),
                String.class);

        // An objection nobody can adjudicate creates the appearance of a process without the
        // substance of one.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("upholding an objection requires a stated remedy")
    void upholdingRequiresARemedy() {
        runFirstDraw();
        String objectionId = file(objectorApplicationNo, "CATEGORY_CLAIM_WRONGLY_REFUSED",
                "My certificate was handed in and never recorded.").path("objectionId").asText();

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/objections/" + objectionId + "/decision?decidedBy=registrar",
                HttpMethod.POST,
                json("{\"outcome\":\"UPHELD\",\"reason\":\"The certificate was located in the counter file.\"}"),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("must state its remedy");
    }

    @Test
    @DisplayName("a rejection must give a reason, and the result stands")
    void rejectionIsReasoned() {
        runFirstDraw();
        String objectionId = file(objectorApplicationNo, "OTHER",
                "I believe the draw was unfair because I did not win a flat.").path("objectionId").asText();

        JsonNode decided = decide(objectionId, "REJECTED",
                "No certificate was submitted with the application and none was produced on request. "
                        + "The draw was conducted correctly.", null);

        assertThat(decided.path("status").asText()).isEqualTo("REJECTED");
        assertThat(decided.path("decisionReason").asText()).containsIgnoringCase("no certificate");
        assertThat(decided.path("whatHappensNext").asText()).contains("published result stands");
    }

    @Test
    @DisplayName("an objection cannot be decided twice")
    void objectionsAreDecidedOnce() {
        runFirstDraw();
        String objectionId = file(objectorApplicationNo, "OTHER",
                "A statement long enough to be adjudicated properly.").path("objectionId").asText();
        decide(objectionId, "REJECTED", "Considered and refused for stated reasons.", null);

        ResponseEntity<String> second = rest.exchange(
                "/api/v1/objections/" + objectionId + "/decision?decidedBy=someone-else",
                HttpMethod.POST,
                json("{\"outcome\":\"UPHELD\",\"reason\":\"Changed our minds.\",\"remedy\":\"Re-draw.\"}"),
                String.class);

        // A disagreement between adjudicators is exactly what a contesting applicant would want to
        // see, so the first decision is not overwritten.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // --- superseding --------------------------------------------------------

    @Test
    @DisplayName("a published draw cannot be superseded unless an objection was upheld")
    void supersessionRequiresAnUpheldObjection() {
        runFirstDraw();

        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/schemes/" + scheme + "/draws?committedBy=r1&supersedes=" + firstDrawId,
                null, String.class);

        // An authority able to re-draw at will can draw until it likes the answer, and every
        // individual draw would still verify perfectly.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("no objection against it has been upheld");
    }

    @Test
    @DisplayName("correcting the record and re-drawing leaves the original untouched")
    void theOriginalDrawSurvivesItsReplacement() {
        runFirstDraw();
        String firstResultHash = drawOf(firstDrawId).path("resultHash").asText();
        long firstAllotments = allotmentCount(firstDrawId);

        String secondDrawId = upholdAndRedraw();

        // The original is not modified in any respect — it cannot be; the database refuses.
        JsonNode original = drawOf(firstDrawId);
        assertThat(original.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(original.path("resultHash").asText()).isEqualTo(firstResultHash);
        assertThat(allotmentCount(firstDrawId)).isEqualTo(firstAllotments);
        assertThat(original.path("supersededByDrawId").asText()).isEqualTo(secondDrawId);

        JsonNode replacement = drawOf(secondDrawId);
        assertThat(replacement.path("supersedesDrawId").asText()).isEqualTo(firstDrawId);
    }

    @Test
    @DisplayName("both draws remain independently verifiable")
    void bothDrawsVerify() {
        runFirstDraw();
        String secondDrawId = upholdAndRedraw();

        assertThat(post("/api/v1/draws/" + firstDrawId + "/verify").path("verified").asBoolean())
                .as("the superseded draw still re-derives from its own published inputs").isTrue();
        assertThat(post("/api/v1/draws/" + secondDrawId + "/verify").path("verified").asBoolean())
                .as("so does its replacement").isTrue();
    }

    @Test
    @DisplayName("the correction actually changes the applicant's standing")
    void theCorrectionTakesEffect() {
        runFirstDraw();

        // Before: the certificate was never verified, so they competed as GEN.
        assertThat(explain(objectorApplicationNo).path("eligibility").path("effectiveCategory").asText())
                .isEqualTo("GEN");

        upholdAndRedraw();

        assertThat(explain(objectorApplicationNo).path("eligibility").path("effectiveCategory").asText())
                .isEqualTo("SC");
    }

    @Test
    @DisplayName("the two draws have different registry roots, because the register changed")
    void thereplacementRunsOnACorrectedRegister() {
        runFirstDraw();
        String secondDrawId = upholdAndRedraw();

        assertThat(drawOf(secondDrawId).path("registryRoot").asText())
                .isNotEqualTo(drawOf(firstDrawId).path("registryRoot").asText());
    }

    @Test
    @DisplayName("a draw can be superseded only once")
    void onlyOneSuccessor() {
        runFirstDraw();
        upholdAndRedraw();

        // A second replacement would leave nobody able to say which allotment stands.
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/schemes/" + scheme + "/draws?committedBy=r1&supersedes=" + firstDrawId,
                null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("already been superseded");
    }

    @Test
    @DisplayName("the whole cycle is in the audit chain, and the chain still verifies")
    void theCycleIsAudited() {
        runFirstDraw();
        upholdAndRedraw();

        Integer objectionEvents = jdbc.queryForObject("""
                SELECT count(*) FROM audit_event WHERE action IN ('OBJECTION_FILED', 'OBJECTION_DECIDED')
                """, Integer.class);
        assertThat(objectionEvents).isGreaterThanOrEqualTo(2);

        assertThat(get("/api/v1/audit/verify").path("verified").asBoolean()).isTrue();
    }

    // --- helpers -----------------------------------------------------------

    /** A draw in which the objector's SC certificate was never verified, so they competed as GEN. */
    private void runFirstDraw() {
        for (int i = 0; i < POPULATION; i++) {
            String applicationNo = submit(i, i == 0 ? "SC" : "GEN");
            if (i == 0) {
                objectorApplicationNo = applicationNo;
            }
        }
        post("/api/v1/schemes/" + scheme + "/rules?createdBy=r1", """
                {"version":"v1","seats":{"OPEN":%d,"SC":0,"ST":0,"OBC":0,"EWS":0},
                 "horizontalReservations":[]}
                """.formatted(FLATS));
        post("/api/v1/schemes/" + scheme + "/rules/v1:activate?activatedBy=r1");
        post("/api/v1/schemes/" + scheme + "/registry:freeze?frozenBy=r1");
        firstDrawId = holdDraw(null);
    }

    /**
     * The full remedy: uphold, correct the record through the ordinary endpoint, re-freeze, re-draw.
     *
     * <p>Four separate acts on purpose. Nothing here is a single call that rewrites a result.
     */
    private String upholdAndRedraw() {
        String objectionId = file(objectorApplicationNo, "CATEGORY_CLAIM_WRONGLY_REFUSED",
                "My scheduled caste certificate was handed in at the counter and never recorded.")
                .path("objectionId").asText();

        decide(objectionId, "UPHELD",
                "The certificate was located in the counter file for 3 March and is genuine.",
                "Verify the category claim and hold a superseding draw.");

        // The correction goes through the ordinary verification endpoint, leaving its own audit
        // event, exactly as it would have done had it happened before the first draw.
        rest.exchange("/api/v1/applications/" + objectorApplicationNo
                        + "/verifications?verifiedBy=clerk-anita",
                HttpMethod.POST,
                json("{\"claim\":\"CATEGORY\",\"outcome\":\"VERIFIED\",\"evidenceReference\":\"CERT-SC-0003\"}"),
                String.class);

        post("/api/v1/schemes/" + scheme + "/registry:freeze?frozenBy=r1");
        return holdDraw(firstDrawId);
    }

    private String holdDraw(String supersedes) {
        String path = "/api/v1/schemes/" + scheme + "/draws?committedBy=r1"
                + (supersedes == null ? "" : "&supersedes=" + supersedes);
        String drawId = post(path).path("drawId").asText();
        post("/api/v1/draws/" + drawId + "/reveal?revealedBy=r1");
        post("/api/v1/draws/" + drawId + "/execute?executedBy=r1");
        execution.execute(UUID.fromString(drawId), "r1");
        post("/api/v1/draws/" + drawId + "/publish?publishedBy=commissioner");
        return drawId;
    }

    private JsonNode file(String applicationNo, String ground, String statement) {
        String body = applicationNo == null
                ? "{\"ground\":\"%s\",\"statement\":\"%s\"}".formatted(ground, statement)
                : "{\"applicationNo\":\"%s\",\"ground\":\"%s\",\"statement\":\"%s\"}"
                        .formatted(applicationNo, ground, statement);
        return post("/api/v1/schemes/" + scheme + "/objections?filedBy=applicant", body);
    }

    private JsonNode decide(String objectionId, String outcome, String reason, String remedy) {
        String body = remedy == null
                ? "{\"outcome\":\"%s\",\"reason\":\"%s\"}".formatted(outcome, reason)
                : "{\"outcome\":\"%s\",\"reason\":\"%s\",\"remedy\":\"%s\"}"
                        .formatted(outcome, reason, remedy);
        return post("/api/v1/objections/" + objectionId + "/decision?decidedBy=registrar", body);
    }

    private String submit(int index, String category) {
        SubmitApplicationRequest request = new SubmitApplicationRequest(
                "Applicant %02d".formatted(index), "1990-01-01", identityNumber(index),
                null, null, "1 Road", "W-1", category, "MALE", "false", "false", "false", "250000");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/schemes/" + scheme + "/applications", HttpMethod.POST,
                json(IntakeFixtures.json(request)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return read(response.getBody()).path("applicationNo").asText();
    }

    private static String identityNumber(int index) {
        String base = "8%010d".formatted(index);
        for (int checkDigit = 0; checkDigit <= 9; checkDigit++) {
            String candidate = base + checkDigit;
            if (GovernmentId.hasValidCheckDigit(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no valid check digit for " + base);
    }

    private long allotmentCount(String drawId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM allotment WHERE draw_id = ?::uuid", Long.class, drawId);
    }

    private JsonNode drawOf(String drawId) {
        return get("/api/v1/draws/" + drawId);
    }

    private JsonNode explain(String applicationNo) {
        return get("/api/v1/applications/" + applicationNo + "/explain");
    }

    private JsonNode get(String path) {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);
        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return read(response.getBody());
    }

    private JsonNode post(String path) {
        return post(path, null);
    }

    private JsonNode post(String path, String body) {
        ResponseEntity<String> response = body == null
                ? rest.postForEntity(path, null, String.class)
                : rest.exchange(path, HttpMethod.POST, json(body), String.class);
        assertThat(response.getStatusCode()).as(path).isIn(HttpStatus.OK, HttpStatus.ACCEPTED);
        return read(response.getBody());
    }

    private HttpEntity<String> json(String body) {
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
}
