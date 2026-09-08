package com.zenalyst.housing.draw;

import com.zenalyst.housing.allocation.AllocationResult;
import com.zenalyst.housing.allocation.PoolOutcome;
import com.zenalyst.housing.allocation.RankedCandidate;
import com.zenalyst.housing.allocation.SeatAward;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.platform.hash.CanonicalJson;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes a draw's result.
 *
 * <p>Batched JDBC rather than JPA. A draw produces four thousand rankings, six hundred allotments
 * and several thousand waitlist entries in one go; persisting that through an entity manager means
 * eight thousand managed objects and a flush that has to be tuned into behaving. The rows are
 * written once and never updated, so there is nothing for an ORM to do here.
 */
@Repository
public class DrawResultRepository {

    private final JdbcTemplate jdbc;

    public DrawResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void write(UUID drawId, AllocationResult result) {
        writeAllotments(drawId, result.awards());
        writeRankings(drawId, result.meritOrder());
        writeWaitlists(drawId, result.pools());
        writePools(drawId, result.pools());
    }

    private void writeAllotments(UUID drawId, List<SeatAward> awards) {
        jdbc.batchUpdate("""
                INSERT INTO allotment
                    (draw_id, application_no, pool, basis, horizontal_category, pool_rank, overall_rank, ticket)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        SeatAward award = awards.get(index);
                        ps.setObject(1, drawId);
                        ps.setString(2, award.applicationNo());
                        ps.setString(3, award.pool().name());
                        ps.setString(4, award.basis().name());
                        ps.setString(5, award.horizontalCategory() == null
                                ? null : award.horizontalCategory().name());
                        ps.setInt(6, award.poolRank());
                        ps.setInt(7, award.overallRank());
                        ps.setString(8, award.ticket());
                    }

                    @Override
                    public int getBatchSize() {
                        return awards.size();
                    }
                });
    }

    private void writeRankings(UUID drawId, List<RankedCandidate> meritOrder) {
        jdbc.batchUpdate("""
                INSERT INTO draw_ranking (draw_id, application_no, overall_rank, ticket)
                VALUES (?, ?, ?, ?)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        RankedCandidate ranked = meritOrder.get(index);
                        ps.setObject(1, drawId);
                        ps.setString(2, ranked.applicationNo());
                        ps.setInt(3, ranked.overallRank());
                        ps.setString(4, ranked.ticket());
                    }

                    @Override
                    public int getBatchSize() {
                        return meritOrder.size();
                    }
                });
    }

    private void writeWaitlists(UUID drawId, List<PoolOutcome> pools) {
        record Entry(String pool, int position, String applicationNo) {
        }
        List<Entry> entries = new java.util.ArrayList<>();
        for (PoolOutcome pool : pools) {
            List<String> waitlist = pool.waitlist();
            for (int i = 0; i < waitlist.size(); i++) {
                entries.add(new Entry(pool.pool().name(), i + 1, waitlist.get(i)));
            }
        }

        jdbc.batchUpdate("""
                INSERT INTO draw_waitlist (draw_id, pool, position, application_no)
                VALUES (?, ?, ?, ?)
                """,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int index) throws SQLException {
                        Entry entry = entries.get(index);
                        ps.setObject(1, drawId);
                        ps.setString(2, entry.pool());
                        ps.setInt(3, entry.position());
                        ps.setString(4, entry.applicationNo());
                    }

                    @Override
                    public int getBatchSize() {
                        return entries.size();
                    }
                });
    }

    private void writePools(UUID drawId, List<PoolOutcome> pools) {
        for (PoolOutcome pool : pools) {
            ArrayNode horizontal = JsonNodeFactory.instance.arrayNode();
            pool.horizontal().forEach(outcome -> {
                ObjectNode node = horizontal.addObject();
                node.put("awarded", outcome.awarded());
                node.put("category", outcome.category().name());
                node.put("onMerit", outcome.onMerit());
                node.put("required", outcome.required());
                node.put("toppedUp", outcome.toppedUp());
            });

            ArrayNode displaced = JsonNodeFactory.instance.arrayNode();
            pool.displaced().forEach(displaced::add);

            jdbc.update("""
                    INSERT INTO draw_pool
                        (draw_id, pool, seats, awarded, competitors, cutoff_pool_rank, horizontal, displaced)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                    """,
                    drawId, pool.pool().name(), pool.seats(), pool.awarded(), pool.competitors(),
                    pool.cutoffPoolRank(), CanonicalJson.render(horizontal), CanonicalJson.render(displaced));
        }
    }
}
