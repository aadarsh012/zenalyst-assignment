package com.zenalyst.housing.normalisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The normalisers decide whether two records are the same person, so their edge cases are the
 * edge cases of the whole deduplication story.
 */
class NormalisationTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 7);

    @Nested
    @DisplayName("names")
    class Names {

        @ParameterizedTest
        @CsvSource({
                "'Ramesh Kumar',        RAMESH KUMAR",
                "'  ramesh   kumar  ',  RAMESH KUMAR",
                "'Ramesh Kumār',        RAMESH KUMAR",
                "'RAMESH  KUMAR',       RAMESH KUMAR",
                "'Ramesh-Kumar',        RAMESH KUMAR",
                "'D''Souza, Maria',     D SOUZA MARIA",
                "'Ramesh   S/o Kumar',  RAMESH S O KUMAR"
        })
        @DisplayName("fold case, accents, punctuation and whitespace into one key")
        void foldsToKey(String input, String expected) {
            assertThat(NameNormaliser.key("fullName", input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("preserve the name as written for display")
        void keepsDisplayForm() {
            assertThat(NameNormaliser.displayForm("fullName", "  Ramesh   Kumār "))
                    .isEqualTo("Ramesh Kumār");
        }

        @Test
        @DisplayName("token order is significant: reversed names are NOT the same key")
        void reversedNamesDiffer() {
            // Deliberate. Collapsing order here would silently merge two different people;
            // catching a clerk's swapped given and family name is fuzzy matching's job, where a
            // human confirms it.
            assertThat(NameNormaliser.key("fullName", "Ramesh Kumar"))
                    .isNotEqualTo(NameNormaliser.key("fullName", "Kumar Ramesh"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "!!!", "..."})
        @DisplayName("reject names with nothing to match on")
        void rejectsEmptyNames(String input) {
            assertThatThrownBy(() -> NameNormaliser.key("fullName", input))
                    .isInstanceOf(InvalidFieldException.class);
        }
    }

    @Nested
    @DisplayName("phone numbers")
    class Phones {

        @ParameterizedTest
        @ValueSource(strings = {
                "9876543210", "09876543210", "+91 98765 43210", "+919876543210",
                "91-9876-543210", "(+91) 9876543210", "0091 9876543210", "  9876543210  "})
        @DisplayName("all the ways one number gets written reduce to one E.164 value")
        void normalisesToE164(String input) {
            assertThat(PhoneNormaliser.toE164("phone", input)).isEqualTo("+919876543210");
        }

        @Test
        @DisplayName("absent is allowed — phone is optional")
        void absentIsNull() {
            assertThat(PhoneNormaliser.toE164("phone", null)).isNull();
            assertThat(PhoneNormaliser.toE164("phone", "  ")).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"1234567890", "5876543210", "987654321", "98765432101234", "abcdefghij"})
        @DisplayName("reject what is not a dialable Indian mobile number")
        void rejectsInvalid(String input) {
            assertThatThrownBy(() -> PhoneNormaliser.toE164("phone", input))
                    .isInstanceOf(InvalidFieldException.class);
        }
    }

    @Nested
    @DisplayName("emails")
    class Emails {

        @Test
        @DisplayName("Google aliasing folds into one key")
        void foldsGoogleAliases() {
            String a = EmailNormaliser.key(EmailNormaliser.normalise("email", "R.Kumar+flat@Gmail.com"));
            String b = EmailNormaliser.key(EmailNormaliser.normalise("email", "rkumar@googlemail.com"));
            assertThat(a).isEqualTo(b).isEqualTo("rkumar@gmail.com");
        }

        @Test
        @DisplayName("dots are significant outside Google, where they address different people")
        void keepsDotsElsewhere() {
            String a = EmailNormaliser.key(EmailNormaliser.normalise("email", "j.smith@example.com"));
            String b = EmailNormaliser.key(EmailNormaliser.normalise("email", "js.mith@example.com"));
            assertThat(a).isNotEqualTo(b);
        }

        @Test
        @DisplayName("sub-addressing is stripped everywhere")
        void stripsPlusSuffix() {
            assertThat(EmailNormaliser.key(EmailNormaliser.normalise("email", "jo+housing@example.com")))
                    .isEqualTo("jo@example.com");
        }

        @ParameterizedTest
        @ValueSource(strings = {"not-an-email", "@example.com", "a@b", "a b@example.com"})
        void rejectsMalformed(String input) {
            assertThatThrownBy(() -> EmailNormaliser.normalise("email", input))
                    .isInstanceOf(InvalidFieldException.class);
        }
    }

    @Nested
    @DisplayName("dates of birth")
    class DatesOfBirth {

        @ParameterizedTest
        @ValueSource(strings = {
                "1990-02-01", "01/02/1990", "01-02-1990", "01.02.1990", "1/2/1990",
                "01 Feb 1990", "01 February 1990"})
        @DisplayName("accept the shapes that actually arrive, reading numeric dates day-first")
        void parsesAcceptedFormats(String input) {
            assertThat(DateOfBirthParser.parse("dateOfBirth", input, TODAY))
                    .isEqualTo(LocalDate.of(1990, 2, 1));
        }

        @Test
        @DisplayName("reject impossible calendar dates rather than shifting them")
        void rejectsImpossibleDates() {
            // Lenient parsing would turn this into 3 March. Silently changing someone's date of
            // birth is worse than refusing the row.
            InvalidFieldException e = catchThrowableOfType(
                    () -> DateOfBirthParser.parse("dateOfBirth", "31/02/1990", TODAY),
                    InvalidFieldException.class);
            assertThat(e.violation().code()).isEqualTo("UNPARSEABLE_DATE");
        }

        @Test
        void rejectsFutureDates() {
            InvalidFieldException e = catchThrowableOfType(
                    () -> DateOfBirthParser.parse("dateOfBirth", "2030-01-01", TODAY),
                    InvalidFieldException.class);
            assertThat(e.violation().code()).isEqualTo("DATE_IN_FUTURE");
        }

        @Test
        void rejectsImplausiblyOldDates() {
            InvalidFieldException e = catchThrowableOfType(
                    () -> DateOfBirthParser.parse("dateOfBirth", "1875-01-01", TODAY),
                    InvalidFieldException.class);
            assertThat(e.violation().code()).isEqualTo("DATE_IMPLAUSIBLE");
        }
    }

    @Nested
    @DisplayName("government identity numbers")
    class GovernmentIds {

        /** Twelve digits whose Verhoeff check digit is correct. */
        static final String VALID = "234567890124";

        @Test
        void acceptsValidNumber() {
            assertThat(GovernmentId.normalise("governmentId", VALID)).isEqualTo(VALID);
        }

        @ParameterizedTest
        @ValueSource(strings = {"2345 6789 0124", "2345-6789-0124", "  234567890124  "})
        @DisplayName("separators are how people write it and are not part of the number")
        void stripsSeparators(String input) {
            assertThat(GovernmentId.normalise("governmentId", input)).isEqualTo(VALID);
        }

        @Test
        @DisplayName("catch a single mistyped digit — the commonest transcription error")
        void catchesSingleDigitError() {
            InvalidFieldException e = catchThrowableOfType(
                    () -> GovernmentId.normalise("governmentId", "234567890125"),
                    InvalidFieldException.class);
            assertThat(e.violation().code()).isEqualTo("CHECKSUM_FAILED");
        }

        @Test
        @DisplayName("catch two adjacent digits swapped — the second commonest")
        void catchesAdjacentTransposition() {
            InvalidFieldException e = catchThrowableOfType(
                    () -> GovernmentId.normalise("governmentId", "234657890124"),
                    InvalidFieldException.class);
            assertThat(e.violation().code()).isEqualTo("CHECKSUM_FAILED");
        }

        @ParameterizedTest
        @ValueSource(strings = {"023456789012", "123456789012"})
        @DisplayName("numbers beginning 0 or 1 are never issued")
        void rejectsReservedPrefixes(String input) {
            InvalidFieldException e = catchThrowableOfType(
                    () -> GovernmentId.normalise("governmentId", input),
                    InvalidFieldException.class);
            assertThat(e.violation().code()).isEqualTo("INVALID_PREFIX");
        }

        @ParameterizedTest
        @ValueSource(strings = {"23456789012", "2345678901234", "abcd56789012"})
        void rejectsWrongLength(String input) {
            InvalidFieldException e = catchThrowableOfType(
                    () -> GovernmentId.normalise("governmentId", input),
                    InvalidFieldException.class);
            assertThat(e.violation().code()).isEqualTo("WRONG_LENGTH");
        }

        @Test
        void exposesLastFourForCounterVerification() {
            assertThat(GovernmentId.last4(VALID)).isEqualTo("0124");
        }
    }
}
