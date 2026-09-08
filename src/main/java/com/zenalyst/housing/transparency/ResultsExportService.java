package com.zenalyst.housing.transparency;

import com.zenalyst.housing.draw.Draw;
import com.zenalyst.housing.draw.DrawRepository;
import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.rules.RuleVersion;
import com.zenalyst.housing.rules.RuleVersionRepository;
import java.io.StringWriter;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The published allotment, as a file a newspaper can download.
 *
 * <p>Two decisions shape it.
 *
 * <p><strong>The header carries the inputs.</strong> Every hash needed to check the file is at the
 * top of the file: the registry root, the rules hash, the seed and its commitment, the result hash.
 * A list of six hundred names with no provenance is a list somebody has to take on trust; the same
 * list with its inputs is one they can re-derive.
 *
 * <p><strong>The rows carry no personal data.</strong> Application number, pool, basis, rank. Not
 * names, not addresses, not categories claimed. Publishing a national newspaper's worth of
 * "Ramesh Kumar, SC, 12 Nehru Road" would be a disclosure the draw does not require, and the
 * application number is enough for any applicant to find themselves.
 */
@Service
public class ResultsExportService {

    private final DrawRepository draws;
    private final RuleVersionRepository ruleVersions;
    private final JdbcTemplate jdbc;

    public ResultsExportService(
            DrawRepository draws, RuleVersionRepository ruleVersions, JdbcTemplate jdbc) {
        this.draws = draws;
        this.ruleVersions = ruleVersions;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public String exportCsv(UUID drawId) {
        Draw draw = draws.findById(drawId)
                .orElseThrow(() -> ApiException.notFound("draw", drawId.toString()));

        if (draw.getResultHash() == null) {
            throw ApiException.conflict(
                    "Draw %s has not produced a result yet.".formatted(drawId),
                    java.util.Map.of("drawId", drawId.toString(), "status", draw.getStatus().name()));
        }

        String rulesVersion = ruleVersions.findById(draw.getRuleVersionId())
                .map(RuleVersion::getVersion).orElse("unknown");

        StringWriter out = new StringWriter();
        writeHeader(out, draw, rulesVersion);

        try (CSVPrinter csv = new CSVPrinter(out, CSVFormat.DEFAULT.builder()
                .setHeader("application_no", "pool", "basis", "horizontal_category",
                        "pool_rank", "overall_rank", "ticket")
                .get())) {

            jdbc.query("""
                    SELECT application_no, pool, basis, horizontal_category, pool_rank, overall_rank, ticket
                    FROM allotment WHERE draw_id = ?
                    ORDER BY pool, pool_rank
                    """,
                    rs -> {
                        try {
                            csv.printRecord(
                                    rs.getString("application_no"), rs.getString("pool"),
                                    rs.getString("basis"),
                                    rs.getString("horizontal_category") == null
                                            ? "" : rs.getString("horizontal_category"),
                                    rs.getInt("pool_rank"), rs.getInt("overall_rank"),
                                    rs.getString("ticket"));
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException("could not write CSV row", e);
                        }
                    },
                    drawId);

        } catch (java.io.IOException e) {
            throw new IllegalStateException("could not write the results file", e);
        }
        return out.toString();
    }

    /**
     * Everything needed to check the file, as CSV comments above the header row.
     *
     * <p>Spreadsheets show these as leading rows rather than hiding them, which is the intention:
     * somebody opening this file should see what it can be checked against before they see a single
     * name.
     */
    private void writeHeader(StringWriter out, Draw draw, String rulesVersion) {
        out.write("# Housing scheme allotment — published result\n");
        out.write("#\n");
        out.write("# draw_id         : " + draw.getId() + "\n");
        out.write("# status          : " + draw.getStatus() + "\n");
        out.write("# registry_root   : " + draw.getRegistryRoot() + "\n");
        out.write("# rules_version   : " + rulesVersion + "\n");
        out.write("# rules_hash      : " + draw.getRulesHash() + "\n");
        out.write("# seed_source     : " + draw.getSeedSource() + "\n");
        if (draw.getSeedCommitment() != null) {
            out.write("# seed_commitment : " + draw.getSeedCommitment() + "\n");
            out.write("# seed_salt       : " + draw.getSeedSalt() + "\n");
        }
        if (draw.getBeaconRound() != null) {
            out.write("# beacon_round    : " + draw.getBeaconRound() + "\n");
        }
        out.write("# seed            : " + draw.getSeed() + "\n");
        out.write("# result_hash     : " + draw.getResultHash() + "\n");
        out.write("# seats_awarded   : " + draw.getSeatsAwarded() + "\n");
        out.write("#\n");
        out.write("# Each applicant's place in the draw is HMAC-SHA256(key = seed, message = application_no),\n");
        out.write("# sorted ascending as hexadecimal. Every ticket below can be recomputed from the seed\n");
        out.write("# above and nothing else. The full candidate register is published separately at\n");
        out.write("# /api/v1/registry/" + draw.getRegistryRoot() + "/candidates\n");
        out.write("#\n");
    }
}
