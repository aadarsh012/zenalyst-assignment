package com.zenalyst.housing.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.zenalyst.housing.platform.hash.CanonicalJson;
import com.zenalyst.housing.platform.hash.Hashing;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Appends to the audit chain.
 *
 * <p>Two design choices are worth stating.
 *
 * <p><strong>Appends must join an existing transaction</strong>
 * ({@link Propagation#MANDATORY}). An audit event is not a log line written near a change; it is
 * part of the change. Committing the application row and the record of having accepted it
 * separately admits exactly the state nobody can defend afterwards — a flat allotted with no
 * record of why, or a record of an act that never happened. Either they both commit or neither
 * does, and calling this outside a transaction is a programming error that fails loudly.
 *
 * <p><strong>Appends are serialised by a PostgreSQL advisory lock.</strong> Each event's hash
 * covers its predecessor's, so two concurrent writers reading the same {@code prevHash} would
 * fork the chain. The lock is transaction-scoped and released on commit or rollback. This makes
 * the audit log a single ordered sequence and therefore a bottleneck on write throughput; at
 * four thousand applications that costs nothing, and the ordering is the entire point.
 */
@Component
public class AuditWriter {

    /** Arbitrary constant identifying the chain lock. Any fixed value works; this one spells "AUDIT". */
    private static final long CHAIN_LOCK_KEY = 0x4155444954L;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AuditWriter(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public AuditEvent append(
            String actor,
            AuditAction action,
            String subjectType,
            String subjectId,
            JsonNode payload) {

        // pg_advisory_xact_lock returns SQL void, which arrives as a PGobject and maps to no
        // Java type; the call is made for its effect, so the result set is simply discarded.
        jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, CHAIN_LOCK_KEY);

        String prevHash = jdbc.query(
                "SELECT hash FROM audit_event ORDER BY seq DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : Hashing.ZERO_HASH);

        UUID eventId = UUID.randomUUID();
        // Truncated to the resolution PostgreSQL will actually store. Hashing a nanosecond-precision
        // instant and then storing a microsecond-precision one would make every event fail its own
        // verification.
        Instant occurredAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String canonicalPayload = CanonicalJson.render(payload);
        String hash = AuditHash.compute(
                eventId, occurredAt, actor, action, subjectType, subjectId, payload, prevHash);

        Long seq = jdbc.queryForObject("""
                INSERT INTO audit_event
                    (event_id, occurred_at, actor, action, subject_type, subject_id, payload, prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
                RETURNING seq
                """,
                Long.class,
                eventId, java.sql.Timestamp.from(occurredAt), actor, action.name(),
                subjectType, subjectId, canonicalPayload, prevHash, hash);

        return new AuditEvent(
                seq == null ? 0L : seq, eventId, occurredAt, actor, action,
                subjectType, subjectId, canonicalPayload, prevHash, hash);
    }
}
