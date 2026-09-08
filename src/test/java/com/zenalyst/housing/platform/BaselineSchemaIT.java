package com.zenalyst.housing.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.zenalyst.housing.audit.AuditAction;
import com.zenalyst.housing.audit.AuditEvent;
import com.zenalyst.housing.audit.AuditWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private AuditWriter auditWriter;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Appends through the real {@link AuditWriter} rather than by raw INSERT.
     *
     * <p>Hand-inserted rows with invented hashes would break the chain for every test that runs
     * afterwards against this shared database — and would break it in exactly the way a genuine
     * tamper does, which is a confusing thing to leave lying around in a test suite whose job is
     * to detect tampering.
     */
    private AuditEvent append(String actor) {
        return new TransactionTemplate(transactionManager).execute(status -> auditWriter.append(
                actor, AuditAction.APPLICATION_RECEIVED, "test-subject", "BASELINE-IT",
                JsonNodeFactory.instance.objectNode().put("origin", "BaselineSchemaIT")));
    }

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
        AuditEvent event = append("baseline-insert");

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE hash = ?", Integer.class, event.hash());
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("audit_event refuses UPDATE")
    void auditRefusesUpdate() {
        AuditEvent event = append("baseline-update");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_event SET actor = 'tampered' WHERE hash = ?", event.hash()))
                .hasMessageContaining("append-only");

        String actor = jdbc.queryForObject(
                "SELECT actor FROM audit_event WHERE hash = ?", String.class, event.hash());
        assertThat(actor).isEqualTo("baseline-update");
    }

    @Test
    @DisplayName("audit_event refuses DELETE")
    void auditRefusesDelete() {
        AuditEvent event = append("baseline-delete");

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM audit_event WHERE hash = ?", event.hash()))
                .hasMessageContaining("append-only");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_event WHERE hash = ?", Integer.class, event.hash()))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("audit_event refuses TRUNCATE")
    void auditRefusesTruncate() throws SQLException {
        append("baseline-truncate");

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
}
