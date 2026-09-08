package com.zenalyst.housing.normalisation;

import java.util.regex.Pattern;

/**
 * Validates and normalises a twelve-digit national identity number.
 *
 * <p>The number carries a Verhoeff check digit, which catches every single-digit error and every
 * adjacent transposition — the two mistakes a clerk copying twelve digits off a paper form
 * actually makes. Checking it at the door means a mistyped identity number is rejected while the
 * form is still on the counter, rather than surfacing weeks later as a person who mysteriously
 * failed to deduplicate against their own second application.
 *
 * <p>This class only ever returns the number for immediate hashing. The number itself is never
 * persisted — see {@code IdentityHasher}.
 */
public final class GovernmentId {

    private static final Pattern NON_DIGITS = Pattern.compile("[^0-9]");
    private static final Pattern TWELVE_DIGITS = Pattern.compile("^[0-9]{12}$");

    /** Verhoeff multiplication table over the dihedral group D5. */
    private static final int[][] D = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 2, 3, 4, 0, 6, 7, 8, 9, 5},
            {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
            {3, 4, 0, 1, 2, 8, 9, 5, 6, 7},
            {4, 0, 1, 2, 3, 9, 5, 6, 7, 8},
            {5, 9, 8, 7, 6, 0, 4, 3, 2, 1},
            {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
            {7, 6, 5, 9, 8, 2, 1, 0, 4, 3},
            {8, 7, 6, 5, 9, 3, 2, 1, 0, 4},
            {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};

    /** Verhoeff permutation table. */
    private static final int[][] P = {
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9},
            {1, 5, 7, 6, 2, 8, 3, 0, 9, 4},
            {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
            {8, 9, 1, 6, 0, 4, 3, 5, 2, 7},
            {9, 4, 5, 3, 1, 2, 6, 8, 7, 0},
            {4, 2, 8, 6, 5, 7, 3, 9, 0, 1},
            {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
            {7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};

    private GovernmentId() {
    }

    /**
     * @return the twelve digits, separators removed
     * @throws InvalidFieldException if the number is the wrong shape or fails its check digit
     */
    public static String normalise(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidFieldException(field, "REQUIRED", "must be provided");
        }
        String digits = NON_DIGITS.matcher(raw).replaceAll("");

        if (!TWELVE_DIGITS.matcher(digits).matches()) {
            throw new InvalidFieldException(field, "WRONG_LENGTH",
                    "must be twelve digits");
        }
        // A real number never begins 0 or 1; the issuing authority does not allocate them.
        char first = digits.charAt(0);
        if (first == '0' || first == '1') {
            throw new InvalidFieldException(field, "INVALID_PREFIX",
                    "must not begin with 0 or 1");
        }
        if (!hasValidCheckDigit(digits)) {
            throw new InvalidFieldException(field, "CHECKSUM_FAILED",
                    "failed its check digit; it has most likely been mistyped");
        }
        return digits;
    }

    /** @return the last four digits, for confirming identity with an applicant at a counter */
    public static String last4(String normalised) {
        return normalised.substring(normalised.length() - 4);
    }

    /**
     * Verhoeff checksum. Valid numbers reduce to zero when folded right-to-left through the
     * permutation and multiplication tables.
     */
    public static boolean hasValidCheckDigit(String digits) {
        int checksum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int digit = digits.charAt(digits.length() - 1 - i) - '0';
            checksum = D[checksum][P[i % 8][digit]];
        }
        return checksum == 0;
    }
}
