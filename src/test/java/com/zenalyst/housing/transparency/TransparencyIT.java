package com.zenalyst.housing.transparency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenalyst.housing.draw.DrawExecutionService;
import com.zenalyst.housing.intake.IntakeFixtures;
import com.zenalyst.housing.intake.SubmitApplicationRequest;
import com.zenalyst.housing.normalisation.GovernmentId;
import com.zenalyst.housing.platform.AbstractIntegrationTest;
import com.zenalyst.housing.registry.MerkleTree;
import java.util.ArrayList;
import java.util.List;
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
 * The endpoints the brief is actually about: answering an applicant, a newspaper and a court.
 */
class TransparencyIT extends AbstractIntegrationTest {

    private static final int FLATS = 8;
    private static final int POPULATION = 40;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DrawExecutionService execution;

    private String scheme;
    private String drawId;

    @BeforeEach
    void createScheme(TestInfo testInfo) {
        scheme = "EXPLAIN-" + testInfo.getTestMethod().orElseThrow().getName();
        jdbc.update("""
                INSERT INTO scheme (code, name, total_flats, applications_open_at, applications_close_at, status)
                VALUES (?, ?, ?, TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2027-01-01 00:00:00+00', 'OPEN')
                ON CONFLICT (code) DO NOTHING
                """, scheme, "Explain " + scheme, FLATS);
    }

    // --- the applicant's answer --------------------------------------------

    @Test
    @DisplayName("a successful applicant is told which pool, which rank and on what basis")
    void explainsAnAllotment() {
        runDraw();
        String allotted = anAllottedApplication();

        JsonNode explain = explain(allotted);

        assertThat(explain.path("outcome").asText()).isEqualTo("ALLOTTED");
        assertThat(explain.path("allotment").path("pool").asText()).isNotEmpty();
        assertThat(explain.path("allotment").path("poolRank").asInt()).isPositive();
        assertThat(explain.path("summary").asText()).contains("allotted a flat");
    }

    @Test
    @DisplayName("an unsuccessful applicant is told where they came and where the cutoff fell")
    void explainsAMiss() {
        runDraw();
        String missed = anUnsuccessfulApplication();

        JsonNode explain = explain(missed);

        assertThat(explain.path("outcome").asText()).isIn("NOT_SELECTED", "WAITLISTED");

        // "You were not selected" is not an answer. This is.
        JsonNode pool = explain.path("pools").get(0);
        assertThat(pool.path("poolRank").asInt()).isPositive();
        assertThat(pool.path("cutoffPoolRank").asInt()).isPositive();
        assertThat(pool.path("poolRank").asInt()).isGreaterThan(pool.path("cutoffPoolRank").asInt());
        assertThat(explain.path("summary").asText()).containsAnyOf("cutoff", "waiting list");
    }

    @Test
    @DisplayName("the explanation carries the whole chain of reasoning, not just the outcome")
    void explanationIsComplete() {
        runDraw();
        JsonNode explain = explain(anAllottedApplication());

        assertThat(explain.path("identity").path("isCanonical").asBoolean()).isTrue();
        assertThat(explain.path("eligibility").path("checks").size()).isGreaterThan(0);
        assertThat(explain.path("registry").path("registryRoot").asText()).matches("^[0-9a-f]{64}$");
        assertThat(explain.path("registry").path("inclusionProof").size()).isPositive();
        assertThat(explain.path("draw").path("seed").asText()).isNotEmpty();
        assertThat(explain.path("lottery").path("ticket").asText()).matches("^[0-9a-f]{64}$");
        assertThat(explain.path("lottery").path("formula").asText()).contains("HMAC-SHA256");
        assertThat(explain.path("verifyYourself").size()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("an applicant can verify their own inclusion proof without this service")
    void inclusionProofInTheExplanationIsCheckable() {
        runDraw();
        JsonNode registry = explain(anAllottedApplication()).path("registry");

        List<MerkleTree.ProofStep> proof = new ArrayList<>();
        for (JsonNode step : registry.path("inclusionProof")) {
            proof.add(new MerkleTree.ProofStep(
                    MerkleTree.Side.valueOf(step.path("side").asText()), step.path("hash").asText()));
        }

        // Recomputed here from the published bytes alone — no repository, no service.
        assertThat(MerkleTree.verify(
                registry.path("canonicalJson").asText(), proof, registry.path("registryRoot").asText()))
                .isTrue();
    }

    @Test
    @DisplayName("a duplicate application is told which of its owner's applications competed instead")
    void explainsADuplicate() {
        // Two applications, same identity number: the second is superseded by the first.
        String first = submit(0, "GEN");
        String second = submitWithIdentityOf(0, 1);
        post("/api/v1/schemes/" + scheme + "/deduplication:run?runBy=operator-1");

        JsonNode explain = explain(second);

        assertThat(explain.path("outcome").asText()).isEqualTo("NO_DRAW_YET");
        assertThat(explain.path("identity").path("isCanonical").asBoolean()).isFalse();
        assertThat(explain.path("identity").path("canonicalApplicationNo").asText()).isEqualTo(first);
    }

    // --- the court's answer ------------------------------------------------

    @Test
    @DisplayName("a draw re-derives from its published inputs")
    void verificationPassesForAnUntamperedDraw() {
        runDraw();
        JsonNode report = post("/api/v1/draws/" + drawId + "/verify");

        assertThat(report.path("verified").asBoolean()).isTrue();
        assertThat(report.path("checks")).hasSize(5);
        report.path("checks").forEach(check ->
                assertThat(check.path("passed").asBoolean())
                        .as("check %s", check.path("name").asText()).isTrue());
    }

    @Test
    @DisplayName("editing one allotment row is caught, and the applicant is named")
    void verificationCatchesATamperedAllotment() {
        runDraw();
        String allotted = anAllottedApplication();

        // The allotment table refuses UPDATE, so a tamperer would have to drop the trigger first.
        // Doing exactly that is the point of this test: even with the database defence removed,
        // the arithmetic still catches it.
        jdbc.execute("ALTER TABLE allotment DISABLE TRIGGER allotment_no_mutation");
        try {
            jdbc.update("UPDATE allotment SET pool_rank = pool_rank + 500 "
                    + "WHERE draw_id = ?::uuid AND application_no = ?", drawId, allotted);
        } finally {
            jdbc.execute("ALTER TABLE allotment ENABLE TRIGGER allotment_no_mutation");
        }

        JsonNode report = post("/api/v1/draws/" + drawId + "/verify");

        assertThat(report.path("verified").asBoolean()).isFalse();
        assertThat(checkNamed(report, "ALLOTMENT").path("passed").asBoolean()).isFalse();
        assertThat(checkNamed(report, "ALLOTMENT").path("detail").asText()).contains(allotted);
        assertThat(report.path("summary").asText()).contains("does NOT match");
    }

    @Test
    @DisplayName("adding a winner who was never a candidate is caught")
    void verificationCatchesAnInventedWinner() {
        runDraw();
        jdbc.update("""
                INSERT INTO allotment (draw_id, application_no, pool, basis, pool_rank, overall_rank, ticket)
                VALUES (?::uuid, 'GHOST-000001', 'OPEN', 'MERIT', 999, 999, ?)
                """, drawId, "f".repeat(64));

        JsonNode report = post("/api/v1/draws/" + drawId + "/verify");

        assertThat(report.path("verified").asBoolean()).isFalse();
        assertThat(checkNamed(report, "ALLOTMENT").path("detail").asText())
                .contains("GHOST-000001")
                .contains("holds a flat that the published inputs do not award");
    }

    @Test
    @DisplayName("altering a frozen candidate breaks the registry root")
    void verificationCatchesATamperedRegister() {
        runDraw();

        // The frozen register refuses edits, so a tamperer would have to drop the trigger first.
        // Doing exactly that is the point: even with the database defence removed, the arithmetic
        // still catches it.
        jdbc.execute("ALTER TABLE frozen_candidate DISABLE TRIGGER frozen_candidate_no_mutation");
        try {
            jdbc.update("""
                    UPDATE frozen_candidate SET canonical_json = replace(canonical_json, '"eligible":true', '"eligible":false')
                    WHERE registry_id = (SELECT registry_id FROM draw WHERE id = ?::uuid)
                      AND leaf_index = 0
                    """, drawId);
        } finally {
            jdbc.execute("ALTER TABLE frozen_candidate ENABLE TRIGGER frozen_candidate_no_mutation");
        }

        JsonNode report = post("/api/v1/draws/" + drawId + "/verify");

        assertThat(report.path("verified").asBoolean()).isFalse();
        assertThat(checkNamed(report, "REGISTRY_ROOT").path("passed").asBoolean()).isFalse();
        assertThat(checkNamed(report, "REGISTRY_ROOT").path("detail").asText())
                .contains("no longer produce the published root");
    }

    @Test
    @DisplayName("the frozen register refuses to be edited at all")
    void frozenRegisterIsImmutable() {
        runDraw();

        // Every other published artefact refuses UPDATE and DELETE. This one did not until V9,
        // which was an oversight rather than a decision: a tampered row was caught by /verify, but
        // a plain UPDATE was enough to make it.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE frozen_candidate SET application_no = 'X' WHERE leaf_index = 0"))
                .hasMessageContaining("frozen register is a snapshot");

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM frozen_registry WHERE registry_root = "
                        + "(SELECT registry_root FROM draw WHERE id = ?::uuid)", drawId))
                .hasMessageContaining("frozen register is a snapshot");
    }

    // --- the auditor's answer ----------------------------------------------

    @Test
    @DisplayName("the audit chain verifies")
    void auditChainIsIntact() {
        runDraw();
        JsonNode report = get("/api/v1/audit/verify");

        assertThat(report.path("verified").asBoolean()).isTrue();
        assertThat(report.path("eventsChecked").asLong()).isPositive();
        // Absent rather than null: the API omits null fields, so an intact chain simply has no
        // break to report. (Jackson's MissingNode.isNull() is false — it is missing, not null.)
        assertThat(report.has("firstBreakAtSeq")).isFalse();
        assertThat(report.path("headHash").asText()).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("editing one audit event is caught, and the event is named")
    void auditChainCatchesAnEditedEvent() {
        runDraw();
        Long seq = jdbc.queryForObject(
                "SELECT min(seq) FROM audit_event WHERE action = 'APPLICATION_RECEIVED'", Long.class);

        String originalActor = jdbc.queryForObject(
                "SELECT actor FROM audit_event WHERE seq = ?", String.class, seq);

        // The chain is append-only and global: every other test in this suite verifies the same
        // one. So the tamper is undone afterwards, restoring the exact original bytes — which is
        // itself a demonstration that the hash depends on contents and nothing else.
        jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER audit_event_no_mutation");
        try {
            jdbc.update("UPDATE audit_event SET actor = 'tampered' WHERE seq = ?", seq);

            JsonNode report = get("/api/v1/audit/verify");

            // Recomputing each hash from its contents is what catches this. Comparing stored
            // hashes to each other would not: the chain still links, because only the contents
            // changed.
            assertThat(report.path("verified").asBoolean()).isFalse();
            assertThat(report.path("firstBreakAtSeq").asLong()).isEqualTo(seq);
            assertThat(report.path("breakKind").asText()).isEqualTo("CONTENT_ALTERED");
            assertThat(report.path("detail").asText()).contains("edited since it was written");

        } finally {
            jdbc.update("UPDATE audit_event SET actor = ? WHERE seq = ?", originalActor, seq);
            jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER audit_event_no_mutation");
        }

        // Restoring the original contents restores the chain: nothing about it is stateful beyond
        // the bytes themselves.
        assertThat(get("/api/v1/audit/verify").path("verified").asBoolean()).isTrue();
    }

    // --- the newspaper's answer --------------------------------------------

    @Test
    @DisplayName("the published file carries every hash needed to check it, and no personal data")
    void resultsCsvIsSelfContainedAndImpersonal() {
        runDraw();
        ResponseEntity<String> response = rest.getForEntity(
                "/api/v1/draws/" + drawId + "/results.csv", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String csv = response.getBody();

        assertThat(csv)
                .contains("# registry_root")
                .contains("# rules_hash")
                .contains("# seed ")
                .contains("# result_hash")
                .contains("HMAC-SHA256")
                .contains("application_no,pool,basis");

        // Nothing in here identifies a person beyond their application number.
        assertThat(csv)
                .doesNotContain("Applicant")
                .doesNotContain("Nehru Road")
                .doesNotContain("@example.com");

        long rows = csv.lines().filter(line -> line.startsWith(scheme + "-")).count();
        assertThat(rows).isEqualTo(FLATS);
    }

    // --- helpers -----------------------------------------------------------

    private void runDraw() {
        for (int i = 0; i < POPULATION; i++) {
            submit(i, "GEN");
        }
        post("/api/v1/schemes/" + scheme + "/rules?createdBy=r1", """
                {"version":"v1","seats":{"OPEN":%d,"SC":0,"ST":0,"OBC":0,"EWS":0},
                 "horizontalReservations":[{"category":"WOMEN","share":0.30}]}
                """.formatted(FLATS));
        post("/api/v1/schemes/" + scheme + "/rules/v1:activate?activatedBy=r1");
        post("/api/v1/schemes/" + scheme + "/registry:freeze?frozenBy=r1");

        drawId = post("/api/v1/schemes/" + scheme + "/draws?committedBy=r1").path("drawId").asText();
        post("/api/v1/draws/" + drawId + "/reveal?revealedBy=r1");
        post("/api/v1/draws/" + drawId + "/execute?executedBy=r1");
        execution.execute(UUID.fromString(drawId), "r1");
        post("/api/v1/draws/" + drawId + "/publish?publishedBy=commissioner");
    }

    private String anAllottedApplication() {
        return jdbc.queryForObject(
                "SELECT application_no FROM allotment WHERE draw_id = ?::uuid ORDER BY pool_rank LIMIT 1",
                String.class, drawId);
    }

    private String anUnsuccessfulApplication() {
        return jdbc.queryForObject("""
                SELECT application_no FROM draw_waitlist
                WHERE draw_id = ?::uuid ORDER BY position DESC LIMIT 1
                """, String.class, drawId);
    }

    private String submit(int index, String category) {
        return submitWith(index, identityNumber(index), category);
    }

    /** A second application carrying an earlier applicant's identity number. */
    private String submitWithIdentityOf(int identityIndex, int index) {
        return submitWith(index, identityNumber(identityIndex), "GEN");
    }

    private String submitWith(int index, String identityNumber, String category) {
        SubmitApplicationRequest request = new SubmitApplicationRequest(
                "Applicant %02d".formatted(index), "1990-01-01", identityNumber,
                null, null, "12 Nehru Road", "W-1", category,
                index % 2 == 0 ? "FEMALE" : "MALE", "false", "false", "false", "250000");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/schemes/" + scheme + "/applications", HttpMethod.POST,
                json(IntakeFixtures.json(request)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return read(response.getBody()).path("applicationNo").asText();
    }

    private static String identityNumber(int index) {
        String base = "6%010d".formatted(index);
        for (int checkDigit = 0; checkDigit <= 9; checkDigit++) {
            String candidate = base + checkDigit;
            if (GovernmentId.hasValidCheckDigit(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no valid check digit for " + base);
    }

    private JsonNode checkNamed(JsonNode report, String name) {
        for (JsonNode check : report.path("checks")) {
            if (check.path("name").asText().equals(name)) {
                return check;
            }
        }
        throw new IllegalStateException("no check named " + name);
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
