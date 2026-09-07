package com.zenalyst.housing.normalisation;

/**
 * A single thing wrong with a single field.
 *
 * <p>Violations are values, not exceptions-in-flight, because intake needs to collect all of
 * them and report them together. Telling an applicant their phone number is malformed, waiting
 * for them to fix it, and only then telling them their date of birth is also malformed is a way
 * of losing applicants who had every right to apply.
 *
 * @param field the field as the submitter named it
 * @param code  a stable machine-readable code; safe to branch on
 * @param message human-readable explanation
 */
public record FieldViolation(String field, String code, String message) {

    public static FieldViolation of(String field, String code, String message) {
        return new FieldViolation(field, code, message);
    }
}
