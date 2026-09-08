package com.zenalyst.housing.identity;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Writes fingerprints and finds the applications that share them.
 *
 * <p>Plain JDBC rather than JPA. This is set-based work — hash four thousand rows, insert them in
 * one batch, then self-join a table to itself — and an ORM contributes nothing to it except a
 * layer between the reader and the query that decides who counts as the same person. That query
 * should be legible.
 */
@Component
public class FingerprintStore {

    private final JdbcTemplate jdbc;

    public FingerprintStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Computes and stores fingerprints for every application in the scheme.
     *
     * <p>Idempotent: applications are immutable, so an application's fingerprints never change and
     * re-running simply skips what is already there.
     *
     * @return how many fingerprint rows were newly written
     */
    public int refresh(UUID schemeId) {
        List<Object[]> rows = new ArrayList<>();

        jdbc.query("""
                SELECT id, government_id_hash, name_key, date_of_birth, phone_e164, email_key
                FROM application
                WHERE scheme_id = ?
                """,
                rs -> {
                    UUID id = rs.getObject("id", UUID.class);
                    String nameKey = rs.getString("name_key");
                    LocalDate dateOfBirth = rs.getObject("date_of_birth", LocalDate.class);

                    add(rows, id, MatchTier.GOVERNMENT_ID,
                            Fingerprints.governmentId(rs.getString("government_id_hash")));
                    add(rows, id, MatchTier.NAME_DOB_PHONE,
                            Fingerprints.nameDobPhone(nameKey, dateOfBirth, rs.getString("phone_e164")));
                    add(rows, id, MatchTier.NAME_DOB_EMAIL,
                            Fingerprints.nameDobEmail(nameKey, dateOfBirth, rs.getString("email_key")));
                },
                schemeId);

        if (rows.isEmpty()) {
            return 0;
        }

        int[] written = jdbc.batchUpdate("""
                INSERT INTO application_fingerprint (application_id, tier, fingerprint)
                VALUES (?, ?, ?)
                ON CONFLICT (application_id, tier) DO NOTHING
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        Object[] row = rows.get(index);
                        ps.setObject(1, row[0]);
                        ps.setString(2, (String) row[1]);
                        ps.setString(3, (String) row[2]);
                    }

                    @Override
                    public int getBatchSize() {
                        return rows.size();
                    }
                });

        return java.util.Arrays.stream(written).sum();
    }

    /**
     * Every pair of applications in the scheme that share a fingerprint.
     *
     * <p>{@code a.id < b.id} yields each pair once rather than twice, and gives the result a
     * stable order regardless of how PostgreSQL chooses to execute the join.
     */
    public List<ExactMatch> exactMatches(UUID schemeId) {
        return jdbc.query("""
                SELECT fa.application_id AS a_id,
                       fb.application_id AS b_id,
                       fa.tier            AS tier,
                       fa.fingerprint     AS fingerprint
                FROM application_fingerprint fa
                JOIN application_fingerprint fb
                       ON fb.tier = fa.tier
                      AND fb.fingerprint = fa.fingerprint
                      AND fb.application_id > fa.application_id
                JOIN application aa ON aa.id = fa.application_id AND aa.scheme_id = ?
                JOIN application ab ON ab.id = fb.application_id AND ab.scheme_id = ?
                ORDER BY fa.tier, fa.fingerprint, fa.application_id, fb.application_id
                """,
                (rs, rowNum) -> new ExactMatch(
                        rs.getObject("a_id", UUID.class),
                        rs.getObject("b_id", UUID.class),
                        MatchTier.valueOf(rs.getString("tier")),
                        rs.getString("fingerprint")),
                schemeId, schemeId);
    }

    private static void add(List<Object[]> rows, UUID id, MatchTier tier, Optional<String> fingerprint) {
        fingerprint.ifPresent(value -> rows.add(new Object[] {id, tier.name(), value}));
    }
}
