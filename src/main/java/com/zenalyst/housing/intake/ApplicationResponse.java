package com.zenalyst.housing.intake;

import java.time.Instant;
import java.time.LocalDate;

/**
 * What the applicant gets back, and what they can quote later.
 *
 * <p>{@code applicationNo} is the handle for everything that follows — status, the explanation of
 * why they did or did not get a flat, and any objection they raise. It is returned prominently
 * because an applicant who loses it has to be found by name and date of birth, which is exactly
 * the ambiguous lookup this system is trying to avoid.
 *
 * <p>Both timestamps are shown. For a paper application these differ, and an applicant is
 * entitled to see that the system knows they applied on the 3rd even though it was typed in on
 * the 19th.
 */
public record ApplicationResponse(
        String applicationNo,
        String schemeCode,
        ApplicationChannel channel,
        String fullName,
        LocalDate dateOfBirth,
        String governmentIdLast4,
        Category category,
        Instant submittedAt,
        Instant recordedAt) {

    public static ApplicationResponse from(Application application, String schemeCode) {
        return new ApplicationResponse(
                application.getApplicationNo(),
                schemeCode,
                application.getChannel(),
                application.getFullName(),
                application.getDateOfBirth(),
                application.getGovernmentIdLast4(),
                application.getCategory(),
                application.getSubmittedAt(),
                application.getRecordedAt());
    }
}
