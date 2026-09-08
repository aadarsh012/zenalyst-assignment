package com.zenalyst.housing.scheme;

import java.time.Instant;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creates a scheme, for development and demonstration only.
 *
 * <p>Opening a housing scheme is a decision taken by a public authority with a published
 * notification behind it, not an API call. Modelling that properly — the notification, the
 * approvals, the dates — is a piece of work this system has not done, and inventing a thin endpoint
 * for it in the main API would suggest otherwise.
 *
 * <p>So it lives here instead, confined to the development profile, where its only job is to let
 * {@code make demo} run end to end. Under any other profile this bean does not exist.
 */
@RestController
@Profile("dev")
public class DevSchemeController {

    private final JdbcTemplate jdbc;

    public DevSchemeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping(path = "/api/v1/dev/scheme", produces = MediaType.APPLICATION_JSON_VALUE)
    public SchemeResponse create(
            @RequestParam String code,
            @RequestParam(defaultValue = "600") int flats,
            @RequestParam(defaultValue = "Demonstration scheme") String name) {

        jdbc.update("""
                INSERT INTO scheme (code, name, total_flats, applications_open_at, applications_close_at, status)
                VALUES (?, ?, ?, ?, ?, 'OPEN')
                ON CONFLICT (code) DO NOTHING
                """,
                code, name, flats,
                java.sql.Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
                java.sql.Timestamp.from(Instant.parse("2026-12-31T23:59:59Z")));

        return jdbc.queryForObject("""
                SELECT id, code, name, total_flats, applications_open_at, applications_close_at, status
                FROM scheme WHERE code = ?
                """,
                (rs, rowNum) -> new SchemeResponse(
                        rs.getObject("id", java.util.UUID.class), rs.getString("code"),
                        rs.getString("name"), rs.getInt("total_flats"),
                        rs.getTimestamp("applications_open_at").toInstant(),
                        rs.getTimestamp("applications_close_at").toInstant(),
                        rs.getString("status")),
                code);
    }
}
