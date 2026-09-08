package com.zenalyst.housing.intake;

import com.zenalyst.housing.normalisation.FieldViolation;
import java.util.List;

/**
 * What happened to one row of an imported file.
 *
 * <p>{@code rowNumber} is the line in the uploaded file as a spreadsheet would number it, header
 * included, because the person fixing the errors is looking at that spreadsheet.
 */
public record ImportRowOutcome(
        int rowNumber,
        Status status,
        String paperReference,
        String applicationNo,
        List<FieldViolation> violations) {

    public enum Status {
        /** Accepted and now competing for a flat. */
        ACCEPTED,
        /** Unusable as submitted; {@code violations} says why. Nothing was written. */
        REJECTED,
        /** This paper reference was imported previously. Skipped, not duplicated. */
        ALREADY_IMPORTED
    }

    public static ImportRowOutcome accepted(int rowNumber, String paperReference, String applicationNo) {
        return new ImportRowOutcome(rowNumber, Status.ACCEPTED, paperReference, applicationNo, List.of());
    }

    public static ImportRowOutcome rejected(int rowNumber, String paperReference, List<FieldViolation> violations) {
        return new ImportRowOutcome(rowNumber, Status.REJECTED, paperReference, null, violations);
    }

    public static ImportRowOutcome alreadyImported(int rowNumber, String paperReference, String applicationNo) {
        return new ImportRowOutcome(rowNumber, Status.ALREADY_IMPORTED, paperReference, applicationNo, List.of());
    }
}
