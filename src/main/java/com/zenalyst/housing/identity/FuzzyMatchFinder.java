package com.zenalyst.housing.identity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Finds applications whose names resemble each other and whose dates of birth agree.
 *
 * <p>Trigram similarity, via PostgreSQL's {@code pg_trgm}. Requiring an exact date of birth as
 * well as a similar name keeps this from being the O(n²) disaster it sounds like: the date
 * equality is evaluated first and cuts four thousand applications down to a handful of small
 * cohorts before any string comparison happens.
 *
 * <p>Pairs are ordered by score and then by application number rather than by id. Ids are
 * random, so ordering on them would make the choice of which pair gets queued differ between two
 * runs over identical data — and this system's defence is that it does the same thing every time.
 *
 * <p>Two names that are <em>identical</em> are included, not excluded. Two people with the same
 * name and the same birthday and no other point of contact are the strongest thing this tier can
 * find, and precisely the case a human should look at.
 */
@Component
public class FuzzyMatchFinder {

    private final JdbcTemplate jdbc;
    private final double threshold;

    public FuzzyMatchFinder(
            JdbcTemplate jdbc,
            @Value("${housing.deduplication.name-similarity-threshold}") double threshold) {
        this.jdbc = jdbc;
        this.threshold = threshold;
    }

    public double threshold() {
        return threshold;
    }

    public List<FuzzyCandidate> candidates(UUID schemeId) {
        return jdbc.query("""
                SELECT a.id             AS a_id,
                       a.application_no AS a_no,
                       a.name_key       AS a_name,
                       b.id             AS b_id,
                       b.application_no AS b_no,
                       b.name_key       AS b_name,
                       a.date_of_birth  AS dob,
                       similarity(a.name_key, b.name_key) AS score
                FROM application a
                JOIN application b
                       ON b.scheme_id = a.scheme_id
                      AND b.date_of_birth = a.date_of_birth
                      AND b.application_no > a.application_no
                WHERE a.scheme_id = ?
                  AND similarity(a.name_key, b.name_key) >= ?
                ORDER BY score DESC, a.application_no, b.application_no
                """,
                (rs, rowNum) -> new FuzzyCandidate(
                        rs.getObject("a_id", UUID.class),
                        rs.getString("a_no"),
                        rs.getString("a_name"),
                        rs.getObject("b_id", UUID.class),
                        rs.getString("b_no"),
                        rs.getString("b_name"),
                        rs.getObject("dob", LocalDate.class),
                        rs.getBigDecimal("score").setScale(3, java.math.RoundingMode.HALF_UP)),
                schemeId, threshold);
    }
}
