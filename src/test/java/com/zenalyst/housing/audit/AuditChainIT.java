package com.zenalyst.housing.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenalyst.housing.intake.IntakeFixtures;
import com.zenalyst.housing.platform.AbstractIntegrationTest;
import com.zenalyst.housing.platform.hash.Hashing;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies that accepting applications actually builds an unbroken chain.
 *
 * <p>Phase 0 proved the audit table refuses mutation. What it could not prove is that anything
 * writes to it correctly, because nothing wrote to it at all. These tests close that gap: they
 * drive real intake through HTTP and then recompute the entire chain from the stored fields,
 * exactly as an outside verifier would.
 */
class AuditChainIT extends AbstractIntegrationTest {

    private static final String SCHEME = "AUDIT-IT";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void createScheme() {
        IntakeFixtures.openScheme(jdbc, SCHEME);
    }

    private void submit(String name, String governmentId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange("/api/v1/schemes/" + SCHEME + "/applications", HttpMethod.POST,
                new HttpEntity<>(IntakeFixtures.json(
                        IntakeFixtures.validRequest(name, governmentId)), headers), String.class);
    }

    @Test
    @DisplayName("accepting an application appends an event that names it")
    void acceptingAnApplicationIsRecorded() {
        submit("Audited Person", IntakeFixtures.VALID_ID_1);

        Map<String, Object> event = jdbc.queryForMap("""
                SELECT action, actor, subject_type, subject_id, payload::text AS payload
                FROM audit_event
                WHERE action = 'APPLICATION_RECEIVED' AND subject_id LIKE ?
                ORDER BY seq DESC LIMIT 1
                """, SCHEME + "-%");

        assertThat(event.get("action")).isEqualTo("APPLICATION_RECEIVED");
        assertThat(event.get("subject_type")).isEqualTo("application");
        assertThat(event.get("payload").toString())
                .contains("\"category\": \"OBC\"")
                .contains("\"channel\": \"ONLINE\"");
    }

    @Test
    @DisplayName("the audit payload carries decision-relevant attributes, not the applicant's identity")
    void payloadDoesNotDuplicateThePersonalRegister() {
        submit("Private Person", IntakeFixtures.VALID_ID_2);

        String payload = jdbc.queryForObject("""
                SELECT payload::text FROM audit_event
                WHERE action = 'APPLICATION_RECEIVED' AND subject_id LIKE ?
                ORDER BY seq DESC LIMIT 1
                """, String.class, SCHEME + "-%");

        // The chain is the most-exported, most-quoted part of this system. It must establish
        // what was decided, not become a second copy of everyone's contact details.
        assertThat(payload)
                .doesNotContain("Private Person")
                .doesNotContain("applicant@example.com")
                .doesNotContain("9876543210")
                .doesNotContain("Nehru Road");
    }

    @Test
    @DisplayName("the whole chain recomputes — every link, from genesis to head")
    void chainRecomputesFromStoredFields() {
        submit("Chain One", IntakeFixtures.VALID_ID_3);
        submit("Chain Two", IntakeFixtures.VALID_ID_4);
        submit("Chain Three", IntakeFixtures.VALID_ID_5);

        List<Map<String, Object>> events = jdbc.queryForList("""
                SELECT seq, event_id, occurred_at, actor, action, subject_type, subject_id,
                       payload::text AS payload, prev_hash, hash
                FROM audit_event ORDER BY seq
                """);

        assertThat(events).hasSizeGreaterThanOrEqualTo(3);

        String expectedPrev = Hashing.ZERO_HASH;
        for (Map<String, Object> event : events) {
            assertThat(event.get("prev_hash"))
                    .as("event %s links to its predecessor", event.get("seq"))
                    .isEqualTo(expectedPrev);

            String recomputed = recompute(event);
            assertThat(recomputed)
                    .as("event %s hashes to its stored value", event.get("seq"))
                    .isEqualTo(event.get("hash"));

            expectedPrev = (String) event.get("hash");
        }
    }

    /** Recomputes an event's hash from nothing but its stored columns, as a verifier would. */
    private String recompute(Map<String, Object> event) {
        try {
            return AuditHash.compute(
                    UUID.fromString(event.get("event_id").toString()),
                    ((java.sql.Timestamp) event.get("occurred_at")).toInstant(),
                    (String) event.get("actor"),
                    AuditAction.valueOf((String) event.get("action")),
                    (String) event.get("subject_type"),
                    (String) event.get("subject_id"),
                    objectMapper.readTree((String) event.get("payload")),
                    (String) event.get("prev_hash"));
        } catch (Exception e) {
            throw new IllegalStateException("could not recompute event " + event.get("seq"), e);
        }
    }

    @Test
    @DisplayName("a rejected submission leaves no trace in the chain")
    void rejectedSubmissionsAreNotRecorded() {
        Long before = jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        rest.exchange("/api/v1/schemes/" + SCHEME + "/applications", HttpMethod.POST,
                new HttpEntity<>("{\"fullName\":\"\",\"governmentId\":\"123\"}", headers), String.class);

        Long after = jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class);

        // A malformed request changed nobody's chances. Recording it would let anyone with curl
        // pad the chain until the events that matter were unreadable.
        assertThat(after).isEqualTo(before);
    }
}
