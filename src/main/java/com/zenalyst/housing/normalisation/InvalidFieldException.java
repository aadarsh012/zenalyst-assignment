package com.zenalyst.housing.normalisation;

/** Thrown by a normaliser when a value cannot be interpreted at all. */
public class InvalidFieldException extends RuntimeException {

    private final transient FieldViolation violation;

    public InvalidFieldException(FieldViolation violation) {
        super("%s: %s".formatted(violation.field(), violation.message()));
        this.violation = violation;
    }

    public InvalidFieldException(String field, String code, String message) {
        this(FieldViolation.of(field, code, message));
    }

    public FieldViolation violation() {
        return violation;
    }
}
