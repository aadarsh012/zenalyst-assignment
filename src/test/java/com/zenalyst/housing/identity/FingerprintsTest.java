package com.zenalyst.housing.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FingerprintsTest {

    private static final LocalDate DOB = LocalDate.of(1990, 2, 1);
    private static final String TOKEN = "a".repeat(64);

    @Test
    @DisplayName("the same inputs always produce the same fingerprint")
    void isDeterministic() {
        assertThat(Fingerprints.nameDobPhone("RAMESH KUMAR", DOB, "+919876543210"))
                .isEqualTo(Fingerprints.nameDobPhone("RAMESH KUMAR", DOB, "+919876543210"))
                .get().asString().matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("any change to any field changes the fingerprint")
    void everyFieldParticipates() {
        Optional<String> base = Fingerprints.nameDobPhone("RAMESH KUMAR", DOB, "+919876543210");

        assertThat(Fingerprints.nameDobPhone("RAMESH KUMARR", DOB, "+919876543210")).isNotEqualTo(base);
        assertThat(Fingerprints.nameDobPhone("RAMESH KUMAR", DOB.plusDays(1), "+919876543210")).isNotEqualTo(base);
        assertThat(Fingerprints.nameDobPhone("RAMESH KUMAR", DOB, "+919876543211")).isNotEqualTo(base);
    }

    @Test
    @DisplayName("tiers cannot collide with each other")
    void tiersAreSeparated() {
        // The tier is hashed along with the fields, so even identical field values across two
        // tiers produce different fingerprints. Without that, a phone-tier match could be
        // mistaken for the far stronger identity-number match.
        Optional<String> phone = Fingerprints.nameDobPhone("RAMESH KUMAR", DOB, "SAME");
        Optional<String> email = Fingerprints.nameDobEmail("RAMESH KUMAR", DOB, "SAME");

        assertThat(phone).isNotEqualTo(email);
    }

    @Test
    @DisplayName("field boundaries cannot be forged by embedding a separator in a value")
    void fieldsCannotBleedIntoEachOther() {
        // Under naive concatenation, a name of "PRIYA|1990-02-01" with no phone number could
        // produce the same joined string as a name of "PRIYA" with that date of birth. Hashing a
        // structured document rather than a joined string makes the collision impossible.
        Optional<String> forged = Fingerprints.nameDobEmail("PRIYA|1990-02-01", DOB, "x@example.com");
        Optional<String> genuine = Fingerprints.nameDobEmail("PRIYA", DOB, "1990-02-01|x@example.com");

        assertThat(forged).isNotEqualTo(genuine);
    }

    @Test
    @DisplayName("an absent optional field yields no fingerprint rather than a weaker one")
    void missingFieldsProduceNoFingerprint() {
        // An applicant who gave no phone number must not be matched to every other applicant who
        // also gave none.
        assertThat(Fingerprints.nameDobPhone("RAMESH KUMAR", DOB, null)).isEmpty();
        assertThat(Fingerprints.nameDobPhone("RAMESH KUMAR", DOB, "  ")).isEmpty();
        assertThat(Fingerprints.nameDobEmail("RAMESH KUMAR", DOB, null)).isEmpty();
        assertThat(Fingerprints.nameDobEmail(null, DOB, "a@example.com")).isEmpty();
        assertThat(Fingerprints.governmentId(null)).isEmpty();
    }

    @Test
    @DisplayName("the identity tier fingerprints the token, never the number")
    void identityTierUsesTheToken() {
        assertThat(Fingerprints.governmentId(TOKEN))
                .isPresent()
                .get().asString()
                // The token is itself an HMAC; fingerprinting it again keeps the raw number out
                // of every layer of this system, exactly as ADR-0003 requires.
                .isNotEqualTo(TOKEN);
    }
}
