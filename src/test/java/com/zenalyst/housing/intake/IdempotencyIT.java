package com.zenalyst.housing.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenalyst.housing.platform.AbstractIntegrationTest;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The brief says a fair number of people applied twice because they were not sure the first one
 * went through. These tests cover the half of that problem which is ours to prevent: a retry
 * must not become a second application.
 *
 * <p>The other half — someone genuinely filling the form in twice on different days — is a real
 * duplicate and is deduplication's problem, not this one's.
 */
class IdempotencyIT extends AbstractIntegrationTest {

    private static final String SCHEME = "IDEM-IT";

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void createScheme() {
        IntakeFixtures.openScheme(jdbc, SCHEME);
    }

    private ResponseEntity<String> submit(String body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.exchange("/api/v1/schemes/" + SCHEME + "/applications",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private long applicationCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM application a JOIN scheme s ON s.id = a.scheme_id WHERE s.code = ?",
                Long.class, SCHEME);
    }

    @Test
    @DisplayName("the same key and the same body replays the original response, once")
    void replaysInsteadOfDuplicating() {
        String body = IntakeFixtures.json(
                IntakeFixtures.validRequest("Retry Person", IntakeFixtures.VALID_ID_1));
        long before = applicationCount();

        ResponseEntity<String> first = submit(body, "key-replay-1");
        ResponseEntity<String> second = submit(body, "key-replay-1");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("false");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");

        // Byte for byte, including the application number. A retrying client must not be told a
        // different application number from the one its first attempt created.
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(applicationCount()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("without a key, a resubmission does create a second application")
    void withoutAKeyDuplicatesAreCreated() {
        String body = IntakeFixtures.json(
                IntakeFixtures.validRequest("No Key Person", IntakeFixtures.VALID_ID_2));
        long before = applicationCount();

        submit(body, null);
        submit(body, null);

        // Not a defect: with nothing to correlate the two requests, the system cannot know this
        // was a retry rather than a genuine second application. Deduplication resolves it later,
        // and — crucially — resolves it visibly rather than by silently dropping one.
        assertThat(applicationCount()).isEqualTo(before + 2);
    }

    @Test
    @DisplayName("the same key with a different body is refused, not answered wrongly")
    void refusesKeyReuseWithDifferentBody() {
        submit(IntakeFixtures.json(
                IntakeFixtures.validRequest("First Body", IntakeFixtures.VALID_ID_3)), "key-conflict-1");

        ResponseEntity<String> second = submit(IntakeFixtures.json(
                IntakeFixtures.validRequest("Second Body", IntakeFixtures.VALID_ID_4)), "key-conflict-1");

        // Replaying the first response here would tell the caller their second, different
        // application succeeded, when it was never even attempted.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("already used for a different request body");
    }

    @Test
    @DisplayName("concurrent requests with one key produce exactly one application")
    void concurrentRequestsProduceOneApplication() throws Exception {
        String body = IntakeFixtures.json(
                IntakeFixtures.validRequest("Double Click", IntakeFixtures.VALID_ID_5));
        long before = applicationCount();

        int attempts = 6;
        // ExecutorService only became AutoCloseable in Java 19; this project targets 17.
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        try {
            List<Callable<ResponseEntity<String>>> calls = java.util.Collections.nCopies(
                    attempts, () -> submit(body, "key-concurrent-1"));
            List<Future<ResponseEntity<String>>> results = pool.invokeAll(calls);

            long created = results.stream()
                    .map(IdempotencyIT::get)
                    .filter(r -> r.getStatusCode() == HttpStatus.CREATED)
                    .count();
            long conflicted = results.stream()
                    .map(IdempotencyIT::get)
                    .filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
                    .count();

            // Losers of the race get a 409 telling them to retry; a retry then replays the
            // winner's response. What must never happen is two applications.
            assertThat(created + conflicted).isEqualTo(attempts);
        } finally {
            pool.shutdownNow();
        }

        assertThat(applicationCount()).isEqualTo(before + 1);
    }

    private static ResponseEntity<String> get(Future<ResponseEntity<String>> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
