package com.zenalyst.housing.platform.idempotency;

import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.platform.hash.Hashing;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes repeated POSTs safe to send.
 *
 * <p>The brief says a fair number of people applied twice because they were not sure the first
 * one went through. Some of those are genuinely two applications and belong to deduplication.
 * But a retried request after a timeout, a double-clicked button, or a mobile connection that
 * dropped after the server had already committed are not second applications, and turning them
 * into second applications would be this system creating the very duplicates it exists to
 * resolve.
 *
 * <h2>How exactly-once is achieved here, and what it costs</h2>
 *
 * <p>The idempotency record is written <em>inside the same transaction as the work it describes</em>,
 * and the key is the primary key. So the application row and the proof that this key was used
 * commit together or not at all — there is no window in which the work is durable but the
 * record of it is not, and no orphaned "in progress" marker to reap if the process dies.
 *
 * <p>The cost is that two genuinely concurrent requests carrying the same key both do the work,
 * and the loser's transaction rolls back on the primary key collision before committing
 * anything. Effort is duplicated; effects are not. The alternative — reserving the key in a
 * separate committed transaction first — avoids the wasted work but introduces a crashed-process
 * state that blocks the key until something cleans it up. For an intake endpoint at this volume,
 * wasted effort is much the cheaper failure.
 */
@Component
public class IdempotencyService {

    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public static String hashRequest(String canonicalBody) {
        return Hashing.sha256Hex(canonicalBody);
    }

    /**
     * Looks for a completed response under this key.
     *
     * @throws ApiException if the key was used before with a different request body — that is a
     *                      client defect, and answering it with the earlier response would tell
     *                      the caller their second, different request succeeded when it never ran
     */
    @Transactional(readOnly = true)
    public Optional<StoredResponse> replay(String key, String endpoint, String requestHash) {
        return jdbc.query("""
                SELECT request_hash, response_status, response_body
                FROM idempotency_record
                WHERE idempotency_key = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    String storedHash = rs.getString("request_hash");
                    if (!storedHash.equals(requestHash)) {
                        throw idempotencyKeyReused(key, endpoint);
                    }
                    return Optional.of(new StoredResponse(
                            rs.getInt("response_status"), rs.getString("response_body")));
                },
                key);
    }

    /**
     * Records the response under this key. Must run inside the transaction that performed the
     * work, so that the two commit together.
     *
     * @throws ApiException if another transaction claimed the key first
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String key, String endpoint, String requestHash, int status, String body) {
        try {
            jdbc.update("""
                    INSERT INTO idempotency_record
                        (idempotency_key, endpoint, request_hash, response_status, response_body)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    key, endpoint, requestHash, status, body);
        } catch (DuplicateKeyException e) {
            throw new ApiException(
                    com.zenalyst.housing.platform.error.ProblemType.CONFLICT,
                    "A request with this Idempotency-Key is already being processed. Retry shortly.",
                    Map.of("idempotencyKey", key, "endpoint", endpoint));
        }
    }

    private static ApiException idempotencyKeyReused(String key, String endpoint) {
        return ApiException.conflict(
                "This Idempotency-Key was already used for a different request body. "
                        + "Use a new key for a new request.",
                Map.of("idempotencyKey", key, "endpoint", endpoint));
    }
}
