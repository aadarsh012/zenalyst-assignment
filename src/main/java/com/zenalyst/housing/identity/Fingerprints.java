package com.zenalyst.housing.identity;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.platform.hash.CanonicalJson;
import com.zenalyst.housing.platform.hash.Hashing;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Derives the fingerprints two applications are compared on.
 *
 * <p>A fingerprint is a hash of the fields that define one tier of identity. Two applications
 * with the same fingerprint at the same tier matched on exactly those fields — nothing more is
 * implied, and nothing less is needed.
 *
 * <p>The fields are assembled into a JSON object and hashed through
 * {@link CanonicalJson}, rather than concatenated with a separator. Concatenation invites a
 * collision that is easy to miss: a name of {@code "PRIYA|1990-01-01"} with no phone number would
 * produce the same joined string as a name of {@code "PRIYA"} with date of birth
 * {@code 1990-01-01}. Structured hashing makes such a collision impossible rather than unlikely,
 * and it reuses the canonicaliser the audit chain already depends on.
 *
 * <p>Fingerprints are returned as {@link Optional} because phone and email are optional fields.
 * An applicant who supplied neither has only the identity-number tier, which is by far the
 * strongest anyway.
 */
public final class Fingerprints {

    private Fingerprints() {
    }

    public static Optional<String> governmentId(String governmentIdToken) {
        if (isBlank(governmentIdToken)) {
            return Optional.empty();
        }
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        fields.put("governmentIdToken", governmentIdToken);
        return Optional.of(hash(MatchTier.GOVERNMENT_ID, fields));
    }

    public static Optional<String> nameDobPhone(String nameKey, LocalDate dateOfBirth, String phoneE164) {
        if (isBlank(nameKey) || dateOfBirth == null || isBlank(phoneE164)) {
            return Optional.empty();
        }
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        fields.put("dateOfBirth", dateOfBirth.toString());
        fields.put("nameKey", nameKey);
        fields.put("phoneE164", phoneE164);
        return Optional.of(hash(MatchTier.NAME_DOB_PHONE, fields));
    }

    public static Optional<String> nameDobEmail(String nameKey, LocalDate dateOfBirth, String emailKey) {
        if (isBlank(nameKey) || dateOfBirth == null || isBlank(emailKey)) {
            return Optional.empty();
        }
        ObjectNode fields = JsonNodeFactory.instance.objectNode();
        fields.put("dateOfBirth", dateOfBirth.toString());
        fields.put("emailKey", emailKey);
        fields.put("nameKey", nameKey);
        return Optional.of(hash(MatchTier.NAME_DOB_EMAIL, fields));
    }

    /**
     * The tier is part of the hashed content, so a fingerprint from one tier can never be equal to
     * a fingerprint from another even if their fields somehow coincided.
     */
    private static String hash(MatchTier tier, ObjectNode fields) {
        ObjectNode document = JsonNodeFactory.instance.objectNode();
        document.set("fields", fields);
        document.put("tier", tier.name());
        return Hashing.sha256Hex(CanonicalJson.render(document));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
