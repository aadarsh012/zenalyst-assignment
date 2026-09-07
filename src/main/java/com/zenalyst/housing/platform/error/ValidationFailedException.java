package com.zenalyst.housing.platform.error;

import com.zenalyst.housing.normalisation.FieldViolation;
import java.util.List;

/**
 * Raised when a submission has one or more unusable fields.
 *
 * <p>Carries every violation, not the first one found. An applicant who is told about one
 * problem, fixes it, and is then told about another is an applicant who may well give up — and
 * a scheme that loses applicants to its own error reporting has not run a fair draw.
 */
public class ValidationFailedException extends RuntimeException {

    private final transient List<FieldViolation> violations;

    public ValidationFailedException(List<FieldViolation> violations) {
        super("%d field(s) failed validation".formatted(violations.size()));
        this.violations = List.copyOf(violations);
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
