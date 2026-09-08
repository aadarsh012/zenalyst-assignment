package com.zenalyst.housing.eligibility;

import java.util.List;

/**
 * What a batch of verifications did.
 *
 * <p>Returned with {@code 200} even where rows failed. A file of several thousand certificate
 * decisions typed up from a counter register will contain bad rows; that is the normal case, and
 * the operator needs to know which ones rather than being told the whole batch was rejected.
 */
public record VerificationImportReport(
        String schemeCode,
        int totalRows,
        int recorded,
        int alreadyDecided,
        int rejected,
        List<RowOutcome> failures) {

    public VerificationImportReport {
        failures = List.copyOf(failures);
    }

    public record RowOutcome(int rowNumber, String applicationNo, String claim, String reason) {
    }
}
