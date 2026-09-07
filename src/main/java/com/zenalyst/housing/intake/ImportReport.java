package com.zenalyst.housing.intake;

import java.util.List;

/**
 * The result of importing a file of paper applications.
 *
 * <p>Returned with {@code 200 OK} even when rows failed, which deserves an explanation. A batch
 * of four thousand hand-written forms typed up by several people will contain bad rows; that is
 * the normal case, not an error. Answering {@code 400} would imply the request was wrong and
 * nothing happened, when in fact most of it succeeded and the operator now needs to know exactly
 * which rows to fix. The report is the response.
 */
public record ImportReport(
        String schemeCode,
        int totalRows,
        int accepted,
        int rejected,
        int alreadyImported,
        List<ImportRowOutcome> outcomes) {

    public static ImportReport of(String schemeCode, List<ImportRowOutcome> outcomes) {
        return new ImportReport(
                schemeCode,
                outcomes.size(),
                (int) outcomes.stream().filter(o -> o.status() == ImportRowOutcome.Status.ACCEPTED).count(),
                (int) outcomes.stream().filter(o -> o.status() == ImportRowOutcome.Status.REJECTED).count(),
                (int) outcomes.stream().filter(o -> o.status() == ImportRowOutcome.Status.ALREADY_IMPORTED).count(),
                outcomes);
    }
}
