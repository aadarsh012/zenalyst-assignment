package com.zenalyst.housing.transparency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenalyst.housing.audit.AuditAction;
import com.zenalyst.housing.audit.AuditHash;
import com.zenalyst.housing.platform.hash.Hashing;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recomputes the audit chain from its stored columns.
 *
 * <p>Deliberately recomputes rather than trusting the stored hash. Comparing a row's
 * {@code prev_hash} to the previous row's {@code hash} would catch a deleted event but not an
 * edited one — a tamperer who changed a payload could recompute the hashes as they went. Rebuilding
 * each hash from the event's actual contents is what makes an edit detectable.
 *
 * <p>Streams the table rather than loading it. The chain grows without bound over a scheme's life,
 * and a verification that runs out of memory on a large chain is a verification nobody performs.
 */
@Service
public class AuditChainVerifier {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AuditChainVerifier(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AuditChainReport verify() {
        AtomicLong checked = new AtomicLong();
        AtomicReference<String> expectedPrev = new AtomicReference<>(Hashing.ZERO_HASH);
        AtomicReference<String> head = new AtomicReference<>(Hashing.ZERO_HASH);
        AtomicReference<AuditChainReport> failure = new AtomicReference<>();

        jdbc.query("""
                SELECT seq, event_id, occurred_at, actor, action, subject_type, subject_id,
                       payload::text AS payload, prev_hash, hash
                FROM audit_event
                ORDER BY seq
                """,
                (ResultSet rs) -> {
                    while (rs.next()) {
                        if (failure.get() != null) {
                            return null;   // already found the first break; nothing after it matters
                        }
                        AuditChainReport problem = check(rs, checked, expectedPrev, head);
                        if (problem != null) {
                            failure.set(problem);
                        }
                    }
                    return null;
                });

        return failure.get() != null
                ? failure.get()
                : AuditChainReport.intact(checked.get(), head.get());
    }

    private AuditChainReport check(
            ResultSet rs, AtomicLong checked,
            AtomicReference<String> expectedPrev, AtomicReference<String> head) throws SQLException {

        long seq = rs.getLong("seq");
        String storedPrev = rs.getString("prev_hash");
        String storedHash = rs.getString("hash");
        checked.incrementAndGet();

        if (!storedPrev.equals(expectedPrev.get())) {
            return AuditChainReport.broken(checked.get(), seq, "BROKEN_LINK",
                    ("Event %d claims to follow %s, but the preceding event's hash is %s. "
                            + "An event has been removed, reordered or inserted.")
                            .formatted(seq, abbreviate(storedPrev), abbreviate(expectedPrev.get())));
        }

        String recomputed;
        try {
            recomputed = AuditHash.compute(
                    UUID.fromString(rs.getString("event_id")),
                    rs.getTimestamp("occurred_at").toInstant(),
                    rs.getString("actor"),
                    AuditAction.valueOf(rs.getString("action")),
                    rs.getString("subject_type"),
                    rs.getString("subject_id"),
                    objectMapper.readTree(rs.getString("payload")),
                    storedPrev);
        } catch (Exception e) {
            return AuditChainReport.broken(checked.get(), seq, "UNREADABLE_EVENT",
                    "Event %d could not be rehashed: %s".formatted(seq, e.getMessage()));
        }

        if (!recomputed.equals(storedHash)) {
            return AuditChainReport.broken(checked.get(), seq, "CONTENT_ALTERED",
                    ("Event %d stores hash %s but its contents hash to %s. "
                            + "The event has been edited since it was written.")
                            .formatted(seq, abbreviate(storedHash), abbreviate(recomputed)));
        }

        expectedPrev.set(storedHash);
        head.set(storedHash);
        return null;
    }

    private static String abbreviate(String hash) {
        return hash.length() <= 16 ? hash : hash.substring(0, 16) + "…";
    }
}
