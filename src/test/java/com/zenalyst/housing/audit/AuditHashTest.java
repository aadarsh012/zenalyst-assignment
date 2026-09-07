package com.zenalyst.housing.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.platform.hash.Hashing;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuditHashTest {

    private static final UUID EVENT_ID = UUID.fromString("0f2b6a3e-1c4d-4a2b-9e3f-5a6b7c8d9e0f");
    private static final Instant AT = Instant.parse("2026-03-14T10:15:30.123456Z");

    private String hash(String subjectId, ObjectNode payload, String prevHash) {
        return AuditHash.compute(EVENT_ID, AT, "operator-7", AuditAction.APPLICATION_RECEIVED,
                "application", subjectId, payload, prevHash);
    }

    private ObjectNode payload(String key, String value) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put(key, value);
        return node;
    }

    @Test
    @DisplayName("the same event always hashes to the same value")
    void isDeterministic() {
        assertThat(hash("MHS-2026-000001", payload("a", "1"), Hashing.ZERO_HASH))
                .isEqualTo(hash("MHS-2026-000001", payload("a", "1"), Hashing.ZERO_HASH))
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("changing the subject changes the hash")
    void dependsOnSubject() {
        assertThat(hash("MHS-2026-000001", payload("a", "1"), Hashing.ZERO_HASH))
                .isNotEqualTo(hash("MHS-2026-000002", payload("a", "1"), Hashing.ZERO_HASH));
    }

    @Test
    @DisplayName("changing the payload changes the hash")
    void dependsOnPayload() {
        assertThat(hash("MHS-2026-000001", payload("a", "1"), Hashing.ZERO_HASH))
                .isNotEqualTo(hash("MHS-2026-000001", payload("a", "2"), Hashing.ZERO_HASH));
    }

    @Test
    @DisplayName("changing the predecessor changes the hash — this is what chains the log")
    void dependsOnPredecessor() {
        assertThat(hash("MHS-2026-000001", payload("a", "1"), Hashing.ZERO_HASH))
                .isNotEqualTo(hash("MHS-2026-000001", payload("a", "1"), "f".repeat(64)));
    }

    @Test
    @DisplayName("payload key order does not affect the hash")
    void ignoresPayloadKeyOrder() {
        ObjectNode ab = JsonNodeFactory.instance.objectNode();
        ab.put("a", "1");
        ab.put("b", "2");
        ObjectNode ba = JsonNodeFactory.instance.objectNode();
        ba.put("b", "2");
        ba.put("a", "1");

        assertThat(hash("x", ab, Hashing.ZERO_HASH)).isEqualTo(hash("x", ba, Hashing.ZERO_HASH));
    }

    @Test
    @DisplayName("timestamps always render with six fractional digits")
    void formatsTimestampsAtMicrosecondPrecision() {
        // Java's default instant formatting emits 0, 3, 6 or 9 digits depending on the value.
        // A whole-second timestamp hashed as "...:30Z" on write and "...:30.000000Z" on read
        // would fail its own verification for no reason but formatting.
        assertThat(AuditHash.TIMESTAMP.format(Instant.parse("2026-03-14T10:15:30Z")))
                .isEqualTo("2026-03-14T10:15:30.000000Z");
        assertThat(AuditHash.TIMESTAMP.format(AT)).isEqualTo("2026-03-14T10:15:30.123456Z");
    }
}
