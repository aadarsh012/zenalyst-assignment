package com.zenalyst.housing.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.platform.hash.CanonicalJson;
import com.zenalyst.housing.platform.hash.Hashing;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Computes an audit event's hash.
 *
 * <p>The definition is deliberately small enough to restate in a sentence, because a third party
 * has to be able to reimplement it: <em>build a JSON object of the event's fields, canonicalise
 * it, and take its SHA-256.</em> Everything difficult is delegated to
 * {@link CanonicalJson}, so there is no separator convention to get wrong and no way for a value
 * containing a delimiter to forge a different event's hash.
 *
 * <p>{@code seq} is deliberately not part of the hash. Ordering is already established by
 * {@code prevHash}, which is strictly stronger — renumbering rows cannot break a chain that does
 * not depend on the numbers.
 *
 * <p>Timestamps are formatted with exactly six fractional digits, matching PostgreSQL's
 * microsecond resolution. Java's default instant formatting emits three, six or nine digits
 * depending on the value, so a timestamp that hashed one way on write would hash another way on
 * read-back — a verification failure caused entirely by formatting.
 */
public final class AuditHash {

    /** Timestamps are always rendered with exactly six fractional digits, in UTC. */
    public static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private AuditHash() {
    }

    public static String compute(
            UUID eventId,
            Instant occurredAt,
            String actor,
            AuditAction action,
            String subjectType,
            String subjectId,
            JsonNode payload,
            String prevHash) {

        ObjectNode event = JsonNodeFactory.instance.objectNode();
        event.put("action", action.name());
        event.put("actor", actor);
        event.put("eventId", eventId.toString());
        event.put("occurredAt", TIMESTAMP.format(occurredAt));
        event.set("payload", payload == null ? JsonNodeFactory.instance.objectNode() : payload);
        event.put("prevHash", prevHash);
        event.put("subjectId", subjectId);
        event.put("subjectType", subjectType);

        return Hashing.sha256Hex(CanonicalJson.render(event));
    }
}
