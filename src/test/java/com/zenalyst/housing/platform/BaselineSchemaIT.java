package com.zenalyst.housing.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the two claims the baseline migration makes: that Flyway owns the schema, and
 * that the audit log genuinely cannot be edited.
 *
 * <p>The append-only test matters more than it looks. "Append-only" is the sort of property
 * that is asserted in a README, believed by everyone, and quietly untrue — because the
 * trigger was dropped in a later migration, or was never applied to the environment that
 * counts. Asserting it against a real PostgreSQL keeps the claim honest for the life of the
 * project.
 */
class BaselineSchemaIT extends AbstractIntegrationTest {

    private static final String GENESIS = "0".repeat(64);
    private static final String SOME_HASH = "a".repeat(64);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("Flyway applied the baseline migration successfully")
    void flywayApplied() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);

        String description = jdbc.queryForObject(
                "SELECT description FROM flyway_schema_history WHERE version = '1'", String.class);
        assertThat(description).isEqualTo("baseline");
    }

    @Test
    @DisplayName("Hibernate validated its mappings against the migrated schema")
    void schemaMatchesEntities() {
        // ddl-auto=validate means the context would have failed to start on a mismatch;
        // reaching this line at all is the assertion. The count confirms the table is real.
        Integer schemes = jdbc.queryForObject("SELECT count(*) FROM scheme", Integer.class);
        assertThat(schemes).isNotNull();
    }

    @Test
    @DisplayName("audit_event accepts inserts")
    void auditAcceptsInserts() {
        insertAuditEvent(GENESIS, SOME_HASH);

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE hash = ?", Integer.class, SOME_HASH);
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("audit_event refuses UPDATE")
    void auditRefusesUpdate() {
        String hash = "b".repeat(64);
        insertAuditEvent(GENESIS, hash);

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_event SET actor = 'tampered' WHERE hash = ?", hash))
                .hasMessageContaining("append-only");

        String actor = jdbc.queryForObject(
                "SELECT actor FROM audit_event WHERE hash = ?", String.class, hash);
        assertThat(actor).isNotEqualTo("tampered");
    }

    @Test
    @DisplayName("audit_event refuses DELETE")
    void auditRefusesDelete() {
        String hash = "c".repeat(64);
        insertAuditEvent(GENESIS, hash);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_event WHERE hash = ?", hash))
                .hasMessageContaining("append-only");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE hash = ?", Integer.class, hash))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("audit_event refuses TRUNCATE")
    void auditRefusesTruncate() throws SQLException {
        insertAuditEvent(GENESIS, "d".repeat(64));

        // TRUNCATE is DDL and is not routed through JdbcTemplate.update's DML path, so it is
        // issued directly — a statement-level trigger is the only thing that stops it.
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            assertThatThrownBy(() -> statement.execute("TRUNCATE TABLE audit_event"))
                    .hasMessageContaining("append-only");
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class))
                .isGreaterThan(0);
    }

    private void insertAuditEvent(String prevHash, String hash) {
        jdbc.update("""
                INSERT INTO audit_event (occurred_at, actor, action, subject_type, subject_id, payload, prev_hash, hash)
                VALUES (now(), 'test', 'TEST_EVENT', 'test', 'test-1', '{}'::jsonb, ?, ?)
                """, prevHash, hash);
    }
}
