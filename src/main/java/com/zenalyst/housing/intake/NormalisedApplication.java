package com.zenalyst.housing.intake;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * An application after normalisation and before persistence: every field parsed, validated and
 * reduced to its canonical form.
 *
 * <p>This is the boundary between "what someone typed" and "what the system believes". Nothing
 * downstream ever sees the raw strings again except through {@code rawPayload}, which is carried
 * along unchanged so the original submission remains available as evidence.
 */
public record NormalisedApplication(
        ApplicationChannel channel,
        String rawPayload,

        String fullName,
        String nameKey,
        LocalDate dateOfBirth,
        String phoneE164,
        String email,
        String emailKey,
        String addressLine,
        String wardCode,

        String governmentIdToken,
        String governmentIdLast4,

        Category category,
        Gender gender,
        boolean localResident,
        boolean disability,
        boolean exServiceperson,
        BigDecimal annualIncome,

        Instant submittedAt,
        String paperReference,
        String enteredBy) {
}
