package com.zenalyst.housing.draw;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenalyst.housing.intake.IntakeFixtures;
import com.zenalyst.housing.normalisation.GovernmentId;
import com.zenalyst.housing.intake.SubmitApplicationRequest;
import com.zenalyst.housing.platform.AbstractIntegrationTest;
import java.util.ArrayList;
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
 * The rules-and-rehearsal path, end to end: publish a quota matrix, freeze a register, run the
 * allocator against both, and keep none of it.
 */
class DryRunIT extends AbstractIntegrationTest {

    /** Ten flats, so a whole draw fits in a test that a person can check by hand. */
    private static final int FLATS = 10;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private String scheme;

    @BeforeEach
    void createScheme(TestInfo testInfo) {
        scheme = "DRAW-" + testInfo.getTestMethod().orElseThrow().getName();
        jdbc.update("""
                INSERT INTO scheme (code, name, total_flats, applications_open_at, applications_close_at, status)
                VALUES (?, ?, ?, TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2027-01-01 00:00:00+00', 'OPEN')
                ON CONFLICT (code) DO NOTHING
                """, scheme, "Draw test " + scheme, FLATS);
    }

    private static final String MATRIX = """
            {
              "version": "v1",
              "seats": {"OPEN": 5, "SC": 2, "ST": 1, "OBC": 2, "EWS": 0},
              "horizontalReservations": [
                {"category": "WOMEN", "share": 0.30},
                {"category": "PWD", "share": 0.05}
              ]
            }
            """;

    // --- rule versions -----------------------------------------------------

    @Test
    @DisplayName("a quota matrix is published with a hash of itself")
    void rulesArePublishedWithAHash() {
        JsonNode created = createRules(MATRIX);

        assertThat(created.path("version").asText()).isEqualTo("v1");
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("rulesHash").asText()).matches("^[0-9a-f]{64}$");
        assertThat(created.path("rulesDocument").asText()).contains("\"OPEN\":5");
    }

    @Test
    @DisplayName("a matrix that does not total the scheme's flats is refused when it is written")
    void matrixMustTotalTheSchemeFlats() {
        ResponseEntity<String> response = postRules("""
                {"version":"bad","seats":{"OPEN":5,"SC":2,"ST":1,"OBC":1,"EWS":0},
                 "horizontalReservations":[]}
                """);

        // Nine seats for ten flats. Caught now, not mid-draw with the seed already public.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("totals 9 seats").contains("10 flats");
    }

    @Test
    @DisplayName("horizontal reservations that cannot be satisfied are refused")
    void impossibleReservationsAreRefused() {
        ResponseEntity<String> response = postRules("""
                {"version":"bad","seats":{"OPEN":5,"SC":2,"ST":1,"OBC":2,"EWS":0},
                 "horizontalReservations":[{"category":"WOMEN","share":0.70},
                                           {"category":"PWD","share":0.50}]}
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("cannot be satisfied");
    }

    @Test
    @DisplayName("activating a version supersedes its predecessor, atomically")
    void activationSupersedesThePrevious() {
        createRules(MATRIX);
        activate("v1");
        createRules(MATRIX.replace("\"v1\"", "\"v2\""));
        activate("v2");

        List<JsonNode> versions = list();
        assertThat(versions).hasSize(2);
        assertThat(statusOf(versions, "v1")).isEqualTo("SUPERSEDED");
        assertThat(statusOf(versions, "v2")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a superseded version is kept, so past draws stay explicable")
    void supersededVersionsAreKept() {
        createRules(MATRIX);
        activate("v1");
        createRules(MATRIX.replace("\"v1\"", "\"v2\""));
        activate("v2");

        assertThat(list()).extracting(node -> node.path("version").asText())
                .containsExactlyInAnyOrder("v1", "v2");
    }

    @Test
    @DisplayName("a version cannot be activated twice")
    void activationIsOnce() {
        createRules(MATRIX);
        activate("v1");

        ResponseEntity<String> second = rest.postForEntity(
                "/api/v1/schemes/" + scheme + "/rules/v1:activate?activatedBy=registrar-1",
                null, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("the same version name cannot be published twice")
    void versionNamesAreUnique() {
        createRules(MATRIX);
        assertThat(postRules(MATRIX).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    // --- the rehearsal -----------------------------------------------------

    @Test
    @DisplayName("a rehearsal allots every seat and keeps none of it")
    void dryRunAllocatesAndPersistsNothing() {
        submitPopulation();
        createRules(MATRIX);
        activate("v1");
        freeze();

        // Counted as a delta, not an absolute. Integration tests share one database and other
        // tests legitimately create draws; an absolute count would make this assertion depend
        // on which tests happened to run first.
        Integer drawEventsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action LIKE 'DRAW%'", Integer.class);

        JsonNode result = dryRun("rehearsal-seed");

        assertThat(result.path("persisted").asBoolean()).isFalse();
        assertThat(result.path("totalSeats").asInt()).isEqualTo(FLATS);
        assertThat(result.path("seatsAwarded").asInt()).isEqualTo(FLATS);
        assertThat(result.path("rulesVersion").asText()).isEqualTo("v1");
        assertThat(result.path("registryRoot").asText()).matches("^[0-9a-f]{64}$");

        // Nothing was written: not one audit event added, and no draw row for this scheme.
        Integer drawEventsAfter = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE action LIKE 'DRAW%'", Integer.class);
        assertThat(drawEventsAfter).isEqualTo(drawEventsBefore);

        Integer draws = jdbc.queryForObject(
                "SELECT count(*) FROM draw d JOIN scheme s ON s.id = d.scheme_id WHERE s.code = ?",
                Integer.class, scheme);
        assertThat(draws).isZero();
    }

    @Test
    @DisplayName("the same seed rehearses the same draw; a different seed does not")
    void theSeedDecidesTheOutcome() {
        submitPopulation();
        createRules(MATRIX);
        activate("v1");
        freeze();

        assertThat(awardedApplications(dryRun("seed-a")))
                .isEqualTo(awardedApplications(dryRun("seed-a")))
                .isNotEqualTo(awardedApplications(dryRun("seed-b")));
    }

    @Test
    @DisplayName("each pool reports its cutoff, its reservations and who was displaced")
    void poolsExplainThemselves() {
        submitPopulation();
        createRules(MATRIX);
        activate("v1");
        freeze();

        JsonNode open = poolOf(dryRun("rehearsal-seed"), "OPEN");

        assertThat(open.path("seats").asInt()).isEqualTo(5);
        assertThat(open.path("awarded").asInt()).isEqualTo(5);
        assertThat(open.path("competitors").asInt()).isGreaterThan(5);
        assertThat(open.path("cutoffPoolRank").asInt()).isPositive();
        assertThat(open.path("horizontal")).hasSize(2);
        assertThat(open.has("displaced")).isTrue();
        assertThat(open.path("waitlistLength").asInt()).isPositive();
    }

    @Test
    @DisplayName("the draw runs on the frozen rows, not on whatever the register has become since")
    void theDrawRunsOnTheFrozenSnapshot() {
        submitPopulation();
        createRules(MATRIX);
        activate("v1");
        int frozenCount = freeze().path("candidateCount").asInt();

        // Somebody applies after the freeze. They cannot be in a draw whose inputs were already
        // committed to — that is the entire purpose of having frozen them.
        submit("Latecomer", "1990-02-01", identityNumber(POPULATION + 1), "GEN", "FEMALE");

        JsonNode result = dryRun("rehearsal-seed");
        assertThat(result.path("candidatesInRegistry").asInt()).isEqualTo(frozenCount);
    }

    @Test
    @DisplayName("unverified category claims leave the reserved pools with nobody in them")
    void unverifiedCategoriesLeaveReservedPoolsEmpty() {
        submitPopulationUnverified();
        createRules(MATRIX);
        activate("v1");
        freeze();

        JsonNode result = dryRun("rehearsal-seed");

        // Every applicant falls back to GEN, so only the five open seats can be filled and the
        // five reserved ones go unallotted. Correct under ADR-0008, and exactly why the freeze
        // response reports how many claims are still outstanding: an authority that froze in this
        // state would allot half its flats.
        assertThat(result.path("seatsAwarded").asInt()).isEqualTo(5);
        assertThat(poolOf(result, "SC").path("competitors").asInt()).isZero();
        assertThat(poolOf(result, "OBC").path("awarded").asInt()).isZero();
    }

    @Test
    @DisplayName("a scheme with no active rule version cannot be drawn")
    void noActiveRulesMeansNoDraw() {
        submitPopulation();
        freeze();

        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/schemes/" + scheme + "/draws:dry-run?seed=x", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("no active rule version");
    }

    @Test
    @DisplayName("a scheme with no frozen register cannot be drawn")
    void noFrozenRegisterMeansNoDraw() {
        submitPopulation();
        createRules(MATRIX);
        activate("v1");

        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/schemes/" + scheme + "/draws:dry-run?seed=x", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("rule creation and activation are recorded in the audit chain")
    void ruleChangesAreAudited() {
        createRules(MATRIX);
        activate("v1");

        Integer events = jdbc.queryForObject("""
                SELECT count(*) FROM audit_event
                WHERE action IN ('RULE_VERSION_CREATED', 'RULE_VERSION_ACTIVATED') AND subject_id = ?
                """, Integer.class, scheme);
        assertThat(events).isEqualTo(2);
    }

    // --- helpers -----------------------------------------------------------

    /**
     * Enough applicants that the open pool cannot exhaust any single category.
     *
     * <p>Forty across five categories is eight each. The open pool takes five, so even in the worst
     * case — all five from one category — three remain, which covers the largest reserved pool.
     * A smaller population makes the test's outcome depend on how the lottery happened to fall.
     */
    private static final int POPULATION = 40;

    private static final String[] CATEGORIES = {"GEN", "SC", "ST", "OBC", "EWS"};

    /**
     * A distinct, checksum-valid identity number for each applicant.
     *
     * <p>Built with the production check-digit routine rather than a hardcoded list, so the fixture
     * scales and cannot drift out of agreement with the validation it has to satisfy.
     */
    private static String identityNumber(int index) {
        String base = "2%010d".formatted(index);
        for (int checkDigit = 0; checkDigit <= 9; checkDigit++) {
            String candidate = base + checkDigit;
            if (GovernmentId.hasValidCheckDigit(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no valid check digit for " + base);
    }

    /**
     * Twenty applicants spread across the categories, with their certificates verified.
     *
     * <p>Verification is not incidental. An unverified category claim means competing as GEN
     * (ADR-0008), so a population whose certificates nobody has examined leaves every reserved pool
     * with no candidates at all — see {@link #unverifiedCategoriesLeaveReservedPoolsEmpty()}.
     */
    private void submitPopulation() {
        for (int i = 0; i < POPULATION; i++) {
            String category = CATEGORIES[i % CATEGORIES.length];
            String applicationNo = submit(
                    "Applicant %02d".formatted(i),
                    "199%d-0%d-01".formatted(i % 10, (i % 9) + 1),
                    identityNumber(i), category,
                    i % 3 == 0 ? "FEMALE" : "MALE");

            if (!"GEN".equals(category)) {
                verify(applicationNo, "CATEGORY");
            }
            if ("EWS".equals(category)) {
                verify(applicationNo, "INCOME");
            }
        }
    }

    /** Submits without verifying anything, so every claim stays outstanding. */
    private void submitPopulationUnverified() {
        for (int i = 0; i < POPULATION; i++) {
            submit("Applicant %02d".formatted(i),
                    "199%d-0%d-01".formatted(i % 10, (i % 9) + 1),
                    identityNumber(i), CATEGORIES[i % CATEGORIES.length],
                    i % 3 == 0 ? "FEMALE" : "MALE");
        }
    }

    private String submit(String name, String dob, String governmentId, String category, String gender) {
        SubmitApplicationRequest request = new SubmitApplicationRequest(
                name, dob, governmentId, null, null, "12 Nehru Road", "W-07",
                category, gender, "true", "false", "false", "250000");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/schemes/" + scheme + "/applications", HttpMethod.POST,
                json(IntakeFixtures.json(request)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return read(response.getBody()).path("applicationNo").asText();
    }

    private void verify(String applicationNo, String claim) {
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/applications/" + applicationNo + "/verifications?verifiedBy=clerk-anita",
                HttpMethod.POST,
                json("{\"claim\":\"%s\",\"outcome\":\"VERIFIED\",\"evidenceReference\":\"CERT-%s\"}"
                        .formatted(claim, applicationNo)),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private JsonNode createRules(String body) {
        ResponseEntity<String> response = postRules(body);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return read(response.getBody());
    }

    private ResponseEntity<String> postRules(String body) {
        return rest.exchange("/api/v1/schemes/" + scheme + "/rules?createdBy=registrar-1",
                HttpMethod.POST, json(body), String.class);
    }

    private void activate(String version) {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/schemes/" + scheme + "/rules/" + version + ":activate?activatedBy=registrar-1",
                null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private List<JsonNode> list() {
        JsonNode array = read(rest.getForEntity(
                "/api/v1/schemes/" + scheme + "/rules", String.class).getBody());
        List<JsonNode> versions = new ArrayList<>();
        array.forEach(versions::add);
        return versions;
    }

    private String statusOf(List<JsonNode> versions, String version) {
        return versions.stream()
                .filter(node -> node.path("version").asText().equals(version))
                .findFirst().orElseThrow().path("status").asText();
    }

    private JsonNode freeze() {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/schemes/" + scheme + "/registry:freeze?frozenBy=registrar-1", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return read(response.getBody());
    }

    private JsonNode dryRun(String seed) {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/schemes/" + scheme + "/draws:dry-run?seed=" + seed, null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return read(response.getBody());
    }

    private List<String> awardedApplications(JsonNode result) {
        List<String> awarded = new ArrayList<>();
        result.path("awards").forEach(award -> awarded.add(award.path("applicationNo").asText()));
        return awarded;
    }

    private JsonNode poolOf(JsonNode result, String pool) {
        for (JsonNode summary : result.path("pools")) {
            if (summary.path("pool").asText().equals(pool)) {
                return summary;
            }
        }
        throw new IllegalStateException("no pool " + pool);
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
