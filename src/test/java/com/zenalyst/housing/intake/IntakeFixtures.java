package com.zenalyst.housing.intake;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Test data helpers.
 *
 * <p>Each test class creates its own scheme with a unique code. Integration tests share one
 * database — the audit log is append-only and therefore cannot be truncated between tests, so
 * isolation comes from tests not colliding rather than from cleaning up after each other.
 */
public final class IntakeFixtures {

    /** Twelve digits with a correct Verhoeff check digit. */
    public static final String VALID_ID_1 = "234567890124";
    public static final String VALID_ID_2 = "345678901238";
    public static final String VALID_ID_3 = "456789012341";
    public static final String VALID_ID_4 = "567890123458";
    public static final String VALID_ID_5 = "678901234560";

    private IntakeFixtures() {
    }

    public static UUID openScheme(JdbcTemplate jdbc, String code) {
        return scheme(jdbc, code, "OPEN",
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"));
    }

    public static UUID scheme(JdbcTemplate jdbc, String code, String status, Instant opensAt, Instant closesAt) {
        return jdbc.queryForObject("""
                INSERT INTO scheme (code, name, total_flats, applications_open_at, applications_close_at, status)
                VALUES (?, ?, 600, ?, ?, ?)
                ON CONFLICT (code) DO UPDATE SET status = EXCLUDED.status
                RETURNING id
                """,
                UUID.class,
                code, "Test scheme " + code,
                java.sql.Timestamp.from(opensAt), java.sql.Timestamp.from(closesAt), status);
    }

    /** A well-formed online application. Vary fields per test with the {@code with*} helpers. */
    public static SubmitApplicationRequest validRequest(String name, String governmentId) {
        return new SubmitApplicationRequest(
                name,
                "1990-02-01",
                governmentId,
                "9876543210",
                "applicant@example.com",
                "12 Nehru Road, Ward 7",
                "W-07",
                "OBC",
                "FEMALE",
                "true",
                "false",
                "false",
                "250000");
    }

    public static String json(SubmitApplicationRequest r) {
        return """
                {
                  "fullName": %s,
                  "dateOfBirth": %s,
                  "governmentId": %s,
                  "phone": %s,
                  "email": %s,
                  "addressLine": %s,
                  "wardCode": %s,
                  "category": %s,
                  "gender": %s,
                  "localResident": %s,
                  "disability": %s,
                  "exServiceperson": %s,
                  "annualIncome": %s
                }
                """.formatted(
                q(r.fullName()), q(r.dateOfBirth()), q(r.governmentId()), q(r.phone()), q(r.email()),
                q(r.addressLine()), q(r.wardCode()), q(r.category()), q(r.gender()),
                q(r.localResident()), q(r.disability()), q(r.exServiceperson()), q(r.annualIncome()));
    }

    private static String q(String value) {
        return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
