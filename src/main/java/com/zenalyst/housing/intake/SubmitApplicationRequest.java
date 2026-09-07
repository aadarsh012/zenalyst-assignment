package com.zenalyst.housing.intake;

/**
 * An online application, exactly as posted.
 *
 * <p>Every field is a {@link String}, including the dates, numbers and booleans. This is
 * deliberate. If Jackson rejected a malformed date before our code ran, the applicant would get
 * a generic parse failure naming a Java type, and the other four things wrong with their form
 * would go unmentioned. Taking strings lets one component validate everything and report all of
 * it at once, and gives the online and paper paths identical error semantics.
 */
public record SubmitApplicationRequest(
        String fullName,
        String dateOfBirth,
        String governmentId,
        String phone,
        String email,
        String addressLine,
        String wardCode,
        String category,
        String gender,
        String localResident,
        String disability,
        String exServiceperson,
        String annualIncome) {
}
