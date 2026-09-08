package com.zenalyst.housing.draw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenalyst.housing.intake.IntakeFixtures;
import com.zenalyst.housing.intake.SubmitApplicationRequest;
import com.zenalyst.housing.normalisation.GovernmentId;
import com.zenalyst.housing.platform.AbstractIntegrationTest;
import com.zenalyst.housing.platform.hash.Hashing;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
 * The draw ceremony end to end.
 *
 * <p>Execution is driven through {@link DrawExecutionService} directly rather than by waiting for
 * JobRunr to poll. The enqueue path — that {@code execute} returns 202, moves the draw to
 * {@code RUNNING}, and lets exactly one of two simultaneous callers through — is covered on its
 * own; making every other test wait out a five-second poll interval would buy nothing and cost a
 * minute.
 */
class DrawCeremonyIT extends AbstractIntegrationTest {

    private static final int FLATS = 10;
    private static final int POPULATION = 40;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DrawExecutionService execution;

    private String scheme;

    @BeforeEach
    void createScheme(TestInfo testInfo) {
        scheme = "CEREMONY-" + testInfo.getTestMethod().orElseThrow().getName();
        jdbc.update("""
                INSERT INTO scheme (code, name, total_flats, applications_open_at, applications_close_at, status)
                VALUES (?, ?, ?, TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2027-01-01 00:00:00+00', 'OPEN')
                ON CONFLICT (code) DO NOTHING
                """, scheme, "Ceremony " + scheme, FLATS);
    }

    // --- the ceremony ------------------------------------------------------

    @Test
    @DisplayName("a committed draw does not disclose its seed")
    void commitWithholdsTheSeed() {
        prepare();
        JsonNode draw = commit();

        assertThat(draw.path("status").asText()).isEqualTo("COMMITTED");
        assertThat(draw.path("seedCommitment").asText()).matches("^[0-9a-f]{64}$");
        // Absent from the document entirely, not merely null. Publishing it would commit to nothing.
        assertThat(draw.has("seed")).isFalse();
        assertThat(draw.has("seedSalt")).isFalse();
        assertThat(draw.path("verification").asText()).contains("not public yet");
    }

    @Test
    @DisplayName("the revealed seed matches the commitment published before it")
    void revealMatchesTheCommitment() {
        prepare();
        JsonNode committed = commit();
        JsonNode revealed = reveal(drawId(committed));

        assertThat(revealed.path("status").asText()).isEqualTo("REVEALED");

        // The check an outsider performs, performed here with nothing but the published values.
        String recomputed = Hashing.sha256Hex(
                revealed.path("seed").asText() + ":" + revealed.path("seedSalt").asText());
        assertThat(recomputed).isEqualTo(committed.path("seedCommitment").asText());
    }

    @Test
    @DisplayName("the draw records the register and rules it will run against, at commit time")
    void commitFixesTheInputs() {
        prepare();
        JsonNode draw = commit();

        assertThat(draw.path("registryRoot").asText()).matches("^[0-9a-f]{64}$");
        assertThat(draw.path("rulesHash").asText()).matches("^[0-9a-f]{64}$");
        assertThat(draw.path("rulesVersion").asText()).isEqualTo("v1");
    }

    @Test
    @DisplayName("execution produces the allotment, the rankings and the waitlists")
    void executionPersistsTheWholeResult() {
        prepare();
        String drawId = executeToCompletion();

        JsonNode draw = get(drawId);
        assertThat(draw.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(draw.path("seatsAwarded").asInt()).isEqualTo(FLATS);
        assertThat(draw.path("resultHash").asText()).matches("^[0-9a-f]{64}$");

        assertThat(count("allotment", drawId)).isEqualTo(FLATS);
        assertThat(count("draw_ranking", drawId)).isEqualTo(POPULATION);
        assertThat(count("draw_waitlist", drawId)).isEqualTo(POPULATION - FLATS);
        assertThat(count("draw_pool", drawId)).isEqualTo(5);
    }

    @Test
    @DisplayName("the executed draw is exactly what the allocator produces from the published inputs")
    void theResultIsReproducibleFromWhatWasPublished() {
        prepare();
        String drawId = executeToCompletion();
        String seed = get(drawId).path("seed").asText();

        // A rehearsal driven by the revealed seed must reach the same six hundred names. This is
        // the property the whole ceremony exists to make checkable.
        JsonNode rehearsal = post("/api/v1/schemes/" + scheme + "/draws:dry-run?seed=" + seed);
        List<String> rehearsed = new ArrayList<>();
        rehearsal.path("awards").forEach(a -> rehearsed.add(a.path("applicationNo").asText()));

        List<String> allotted = jdbc.queryForList(
                "SELECT application_no FROM allotment WHERE draw_id = ?::uuid ORDER BY pool, pool_rank",
                String.class, drawId);

        assertThat(rehearsed).containsExactlyInAnyOrderElementsOf(allotted);
    }

    @Test
    @DisplayName("publishing makes the result final, and the database enforces it")
    void publishedDrawsAreImmutable() {
        prepare();
        String drawId = executeToCompletion();

        assertThat(post("/api/v1/draws/" + drawId + "/publish?publishedBy=commissioner")
                .path("status").asText()).isEqualTo("PUBLISHED");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE draw SET seats_awarded = 999 WHERE id = ?::uuid", drawId))
                .hasMessageContaining("published and cannot be changed");

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM allotment WHERE draw_id = ?::uuid", drawId))
                .hasMessageContaining("written once");
    }

    // --- the orderings that must be refused --------------------------------

    @Test
    @DisplayName("a draw cannot execute before its seed is revealed")
    void cannotExecuteBeforeReveal() {
        prepare();
        String drawId = drawId(commit());

        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/draws/" + drawId + "/execute?executedBy=r1", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("must be REVEALED");
    }

    @Test
    @DisplayName("a seed cannot be revealed twice")
    void cannotRevealTwice() {
        prepare();
        String drawId = drawId(commit());
        reveal(drawId);

        ResponseEntity<String> second = rest.postForEntity(
                "/api/v1/draws/" + drawId + "/reveal?revealedBy=r1", null, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("a result cannot be published before it exists")
    void cannotPublishBeforeCompletion() {
        prepare();
        String drawId = drawId(commit());
        reveal(drawId);

        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/draws/" + drawId + "/publish?publishedBy=x", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("must be COMPLETED");
    }

    @Test
    @DisplayName("a scheme cannot have two draws in flight at once")
    void onlyOneDrawInFlight() {
        prepare();
        commit();

        ResponseEntity<String> second = rest.postForEntity(
                "/api/v1/schemes/" + scheme + "/draws?committedBy=r1", null, String.class);

        // Two draws running against one register has no defensible answer: whichever finished
        // second would silently become the result.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("still COMMITTED");
    }

    @Test
    @DisplayName("two simultaneous execute requests result in exactly one execution")
    void executeIsExactlyOnce() throws Exception {
        prepare();
        String drawId = drawId(commit());
        reveal(drawId);

        int attempts = 6;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<ResponseEntity<String>>> calls = java.util.Collections.nCopies(
                    attempts, () -> rest.postForEntity(
                            "/api/v1/draws/" + drawId + "/execute?executedBy=r1", null, String.class));
            List<Future<ResponseEntity<String>>> results = pool.invokeAll(calls);

            long accepted = results.stream().map(DrawCeremonyIT::get)
                    .filter(r -> r.getStatusCode() == HttpStatus.ACCEPTED).count();
            long refused = results.stream().map(DrawCeremonyIT::get)
                    .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();

            // The status transition happens under a row lock, so the losers read RUNNING and stop.
            assertThat(accepted).isEqualTo(1);
            assertThat(refused).isEqualTo(attempts - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a late job finds the work already done and does nothing")
    void executionIsIdempotent() {
        prepare();
        String drawId = executeToCompletion();
        long allotments = count("allotment", drawId);

        // JobRunr may deliver a job more than once. Re-running must not double the result, and
        // must not fail either — arriving late is allowed.
        execution.execute(java.util.UUID.fromString(drawId), "r1");

        assertThat(count("allotment", drawId)).isEqualTo(allotments);
        assertThat(get(drawId).path("status").asText()).isEqualTo("COMPLETED");
    }

    // --- the audit trail ---------------------------------------------------

    @Test
    @DisplayName("the seed reaches the audit chain only when it is revealed")
    void theSeedIsNotAuditedBeforeReveal() {
        prepare();
        String drawId = drawId(commit());

        String seedHeldBack = jdbc.queryForObject(
                "SELECT seed FROM draw WHERE id = ?::uuid", String.class, drawId);
        Integer leaked = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE payload::text LIKE ?",
                Integer.class, "%" + seedHeldBack + "%");
        assertThat(leaked).isZero();

        reveal(drawId);

        String action = jdbc.queryForObject(
                "SELECT action FROM audit_event WHERE payload::text LIKE ?",
                String.class, "%" + seedHeldBack + "%");
        assertThat(action).isEqualTo("DRAW_SEED_REVEALED");
    }

    @Test
    @DisplayName("the whole ceremony is in the chain, in order")
    void theCeremonyIsAudited() {
        prepare();
        String drawId = executeToCompletion();
        post("/api/v1/draws/" + drawId + "/publish?publishedBy=commissioner");

        List<String> actions = jdbc.queryForList("""
                SELECT action FROM audit_event WHERE subject_id = ? ORDER BY seq
                """, String.class, drawId);

        assertThat(actions).containsExactly(
                "DRAW_COMMITTED", "DRAW_SEED_REVEALED", "DRAW_EXECUTED", "DRAW_PUBLISHED");
    }

    // --- helpers -----------------------------------------------------------

    /** Applicants, a quota matrix, and a frozen register: everything a draw needs to exist. */
    private void prepare() {
        for (int i = 0; i < POPULATION; i++) {
            submit(i);
        }
        post("/api/v1/schemes/" + scheme + "/rules?createdBy=r1", """
                {"version":"v1","seats":{"OPEN":%d,"SC":0,"ST":0,"OBC":0,"EWS":0},
                 "horizontalReservations":[{"category":"WOMEN","share":0.30}]}
                """.formatted(FLATS));
        post("/api/v1/schemes/" + scheme + "/rules/v1:activate?activatedBy=r1");
        post("/api/v1/schemes/" + scheme + "/registry:freeze?frozenBy=r1");
    }

    private void submit(int index) {
        SubmitApplicationRequest request = new SubmitApplicationRequest(
                "Applicant %02d".formatted(index), "1990-01-01", identityNumber(index),
                null, null, "1 Road", "W-1", "GEN",
                index % 2 == 0 ? "FEMALE" : "MALE", "false", "false", "false", "250000");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/schemes/" + scheme + "/applications", HttpMethod.POST,
                json(IntakeFixtures.json(request)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private static String identityNumber(int index) {
        String base = "5%010d".formatted(index);
        for (int checkDigit = 0; checkDigit <= 9; checkDigit++) {
            String candidate = base + checkDigit;
            if (GovernmentId.hasValidCheckDigit(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("no valid check digit for " + base);
    }

    private JsonNode commit() {
        return post("/api/v1/schemes/" + scheme + "/draws?committedBy=registrar-1");
    }

    private JsonNode reveal(String drawId) {
        return post("/api/v1/draws/" + drawId + "/reveal?revealedBy=registrar-1");
    }

    /** Commits, reveals, and runs the allocation synchronously. Returns the draw id. */
    private String executeToCompletion() {
        String drawId = drawId(commit());
        reveal(drawId);

        ResponseEntity<String> accepted = rest.postForEntity(
                "/api/v1/draws/" + drawId + "/execute?executedBy=r1", null, String.class);
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(read(accepted.getBody()).path("status").asText()).isEqualTo("RUNNING");

        // Run the job body here rather than waiting on JobRunr's poll interval.
        execution.execute(java.util.UUID.fromString(drawId), "r1");
        return drawId;
    }

    private long count(String table, String drawId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE draw_id = ?::uuid", Long.class, drawId);
    }

    private String drawId(JsonNode draw) {
        return draw.path("drawId").asText();
    }

    private JsonNode get(String drawId) {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/draws/" + drawId, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
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

    private static ResponseEntity<String> get(Future<ResponseEntity<String>> future) {
        try {
            return future.get(Duration.ofSeconds(30).toSeconds(), java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
