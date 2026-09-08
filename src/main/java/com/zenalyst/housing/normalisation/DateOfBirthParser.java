package com.zenalyst.housing.normalisation;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;

/**
 * Parses dates of birth as they actually arrive from an online form and from a clerk typing in
 * a handwritten one.
 *
 * <p><strong>Ambiguous numeric dates are read day-first.</strong> {@code 01/02/1990} is the
 * first of February, not the second of January. This is the Indian convention and the
 * convention of the forms this system ingests, but it is a genuine ambiguity that no amount of
 * code can resolve: a date entered by someone thinking in month-first will be silently
 * misread. The mitigation is not cleverness here, it is that {@code raw_payload} preserves what
 * was actually typed, so a disputed date of birth can be re-examined rather than argued about.
 *
 * <p>Resolution is strict: {@code 31/02/1990} is rejected rather than quietly shifted to the
 * second of March, which is what lenient parsing would do.
 */
public final class DateOfBirthParser {

    private static final List<DateTimeFormatter> FORMATS = List.of(
            strict("uuuu-MM-dd"),   // ISO — what the online form posts
            strict("dd/MM/uuuu"),
            strict("dd-MM-uuuu"),
            strict("dd.MM.uuuu"),
            strict("d/M/uuuu"),
            strict("d-M-uuuu"),
            strict("dd MMM uuuu"),  // "05 Jan 1990" — common on typed-up paper forms
            strict("dd MMMM uuuu"));

    /** No plausible applicant was born before this. Guards against a mistyped century. */
    private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);

    private DateOfBirthParser() {
    }

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.STRICT);
    }

    /**
     * @param today the current date, passed in rather than read, so that parsing is a pure
     *              function and its tests do not change meaning as the calendar advances
     */
    public static LocalDate parse(String field, String raw, LocalDate today) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidFieldException(field, "REQUIRED", "must be provided");
        }
        String trimmed = raw.trim();

        LocalDate parsed = null;
        for (DateTimeFormatter format : FORMATS) {
            try {
                parsed = LocalDate.parse(trimmed, format);
                break;
            } catch (DateTimeParseException ignored) {
                // try the next accepted shape
            }
        }

        if (parsed == null) {
            throw new InvalidFieldException(field, "UNPARSEABLE_DATE",
                    "must be a real date such as 1990-02-01 or 01/02/1990 (day first)");
        }
        if (parsed.isAfter(today)) {
            throw new InvalidFieldException(field, "DATE_IN_FUTURE",
                    "must not be in the future");
        }
        if (parsed.isBefore(EARLIEST)) {
            throw new InvalidFieldException(field, "DATE_IMPLAUSIBLE",
                    "must not be before %s".formatted(EARLIEST));
        }
        return parsed;
    }
}
