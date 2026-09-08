package com.zenalyst.housing.intake;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.normalisation.DateOfBirthParser;
import com.zenalyst.housing.normalisation.EmailNormaliser;
import com.zenalyst.housing.normalisation.FieldViolation;
import com.zenalyst.housing.normalisation.GovernmentId;
import com.zenalyst.housing.normalisation.InvalidFieldException;
import com.zenalyst.housing.normalisation.NameNormaliser;
import com.zenalyst.housing.normalisation.PhoneNormaliser;
import com.zenalyst.housing.platform.error.ValidationFailedException;
import com.zenalyst.housing.platform.hash.CanonicalJson;
import com.zenalyst.housing.platform.hash.IdentityHasher;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Turns a submitted form into a {@link NormalisedApplication}, collecting every problem it finds.
 *
 * <p>The raw payload is assembled here rather than captured from the HTTP body, for one reason:
 * the government identity number must not be written to disk. What is preserved is the
 * submission with that single field replaced by {@code "<redacted>"} — enough to show a court
 * exactly what the applicant wrote about themselves, without the system becoming a register of
 * national identity numbers. Whether the number itself matched is answerable from
 * {@code governmentIdToken}: hash the number the applicant produces and compare.
 */
@Component
public class ApplicationNormaliser {

    private static final String REDACTED = "<redacted>";
    private static final int MAX_ADDRESS_LENGTH = 500;

    private final IdentityHasher identityHasher;

    public ApplicationNormaliser(IdentityHasher identityHasher) {
        this.identityHasher = identityHasher;
    }

    /**
     * @param schemeCode  scopes the identity token
     * @param submittedAt the instant the application counts as having been made
     * @param today       reference date for rejecting future dates of birth
     */
    public NormalisedApplication normalise(
            String schemeCode,
            ApplicationChannel channel,
            SubmitApplicationRequest request,
            Instant submittedAt,
            LocalDate today,
            String paperReference,
            String enteredBy) {

        List<FieldViolation> violations = new ArrayList<>();

        String fullName = collect(violations, () -> NameNormaliser.displayForm("fullName", request.fullName()));
        // Derived from the display form rather than the raw input, so that a blank name reports
        // one violation rather than the same violation twice from two normalisers.
        String nameKey = fullName == null
                ? null
                : collect(violations, () -> NameNormaliser.key("fullName", fullName));
        LocalDate dateOfBirth = collect(violations,
                () -> DateOfBirthParser.parse("dateOfBirth", request.dateOfBirth(), today));
        String phone = collect(violations, () -> PhoneNormaliser.toE164("phone", request.phone()));
        String email = collect(violations, () -> EmailNormaliser.normalise("email", request.email()));
        String addressLine = collect(violations, () -> requiredText("addressLine", request.addressLine(), MAX_ADDRESS_LENGTH));
        String wardCode = collect(violations, () -> optionalUpper("wardCode", request.wardCode()));
        Category category = collect(violations, () -> enumValue("category", request.category(), Category.class));
        Gender gender = collect(violations, () -> enumValue("gender", request.gender(), Gender.class));
        Boolean localResident = collect(violations, () -> flag("localResident", request.localResident()));
        Boolean disability = collect(violations, () -> flag("disability", request.disability()));
        Boolean exServiceperson = collect(violations, () -> flag("exServiceperson", request.exServiceperson()));
        BigDecimal annualIncome = collect(violations, () -> money("annualIncome", request.annualIncome()));

        String governmentId = collect(violations, () -> GovernmentId.normalise("governmentId", request.governmentId()));

        if (!violations.isEmpty()) {
            throw new ValidationFailedException(violations);
        }

        return new NormalisedApplication(
                channel,
                rawPayload(request, channel, submittedAt, paperReference, enteredBy),
                fullName,
                nameKey,
                dateOfBirth,
                phone,
                email,
                EmailNormaliser.key(email),
                addressLine,
                wardCode,
                identityHasher.token(schemeCode, governmentId),
                GovernmentId.last4(governmentId),
                category,
                gender,
                Boolean.TRUE.equals(localResident),
                Boolean.TRUE.equals(disability),
                Boolean.TRUE.equals(exServiceperson),
                annualIncome,
                submittedAt,
                paperReference,
                enteredBy);
    }

    /** Runs one field's normalisation, recording rather than propagating its failure. */
    private <T> T collect(List<FieldViolation> violations, Supplier<T> normalisation) {
        try {
            return normalisation.get();
        } catch (InvalidFieldException e) {
            violations.add(e.violation());
            return null;
        }
    }

    private String rawPayload(
            SubmitApplicationRequest request, ApplicationChannel channel,
            Instant submittedAt, String paperReference, String enteredBy) {

        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("addressLine", request.addressLine());
        node.put("annualIncome", request.annualIncome());
        node.put("category", request.category());
        node.put("channel", channel.name());
        node.put("dateOfBirth", request.dateOfBirth());
        node.put("disability", request.disability());
        node.put("email", request.email());
        node.put("exServiceperson", request.exServiceperson());
        node.put("fullName", request.fullName());
        node.put("gender", request.gender());
        // The one field we accept and immediately forget.
        node.put("governmentId", REDACTED);
        node.put("localResident", request.localResident());
        node.put("phone", request.phone());
        node.put("submittedAt", submittedAt.toString());
        node.put("wardCode", request.wardCode());
        if (channel == ApplicationChannel.PAPER) {
            node.put("enteredBy", enteredBy);
            node.put("paperReference", paperReference);
        }
        return CanonicalJson.render(node);
    }

    private static String requiredText(String field, String raw, int maxLength) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidFieldException(field, "REQUIRED", "must be provided");
        }
        String trimmed = raw.trim().replaceAll("\\s+", " ");
        if (trimmed.length() > maxLength) {
            throw new InvalidFieldException(field, "TOO_LONG",
                    "must be at most %d characters".formatted(maxLength));
        }
        return trimmed;
    }

    private static String optionalUpper(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toUpperCase(Locale.ROOT);
        if (!trimmed.matches("^[A-Z0-9-]{1,32}$")) {
            throw new InvalidFieldException(field, "INVALID_CODE",
                    "must be up to 32 letters, digits or hyphens");
        }
        return trimmed;
    }

    private static <E extends Enum<E>> E enumValue(String field, String raw, Class<E> type) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidFieldException(field, "REQUIRED", "must be provided");
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidFieldException(field, "NOT_A_VALID_OPTION",
                    "must be one of %s".formatted(String.join(", ",
                            java.util.Arrays.stream(type.getEnumConstants()).map(Enum::name).toList())));
        }
    }

    private static Boolean flag(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return Boolean.FALSE;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "true", "yes", "y", "1" -> Boolean.TRUE;
            case "false", "no", "n", "0" -> Boolean.FALSE;
            default -> throw new InvalidFieldException(field, "NOT_A_BOOLEAN",
                    "must be true or false");
        };
    }

    private static BigDecimal money(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim().replace(",", ""));
            if (value.signum() < 0) {
                throw new InvalidFieldException(field, "NEGATIVE_AMOUNT", "must not be negative");
            }
            if (value.scale() > 2) {
                throw new InvalidFieldException(field, "TOO_PRECISE",
                        "must have at most two decimal places");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new InvalidFieldException(field, "NOT_A_NUMBER", "must be an amount such as 250000");
        }
    }

    /** Exposed so the paper importer can share the same "now" semantics. */
    public static LocalDate toLocalDate(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
