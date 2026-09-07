package com.zenalyst.housing.normalisation;

import java.util.regex.Pattern;

/**
 * Normalises Indian mobile numbers to E.164 ({@code +919876543210}).
 *
 * <p>The same person's number arrives as {@code 9876543210}, {@code 09876543210},
 * {@code +91 98765 43210}, {@code 91-9876-543210} and {@code (+91) 9876543210}, depending on
 * whether they typed it themselves or a clerk copied it off a form. All five are the same
 * number, and deduplication has to see them as such.
 *
 * <p>Scope is deliberately Indian mobile numbers only. A general-purpose library would be the
 * right answer for an international system; here, accepting a number this system cannot in fact
 * dial or match would be worse than rejecting it at the door.
 */
public final class PhoneNormaliser {

    private static final Pattern NON_DIGITS = Pattern.compile("[^0-9]");
    /** Indian mobile numbers are ten digits beginning 6, 7, 8 or 9. */
    private static final Pattern INDIAN_MOBILE = Pattern.compile("^[6-9][0-9]{9}$");

    private PhoneNormaliser() {
    }

    /**
     * @return the number in E.164, or {@code null} if {@code raw} was absent (phone is optional)
     */
    public static String toE164(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        String digits = NON_DIGITS.matcher(raw).replaceAll("");
        String national = stripCountryAndTrunkPrefixes(digits);

        if (!INDIAN_MOBILE.matcher(national).matches()) {
            throw new InvalidFieldException(field, "NOT_AN_INDIAN_MOBILE",
                    "must be a ten-digit Indian mobile number, optionally prefixed with +91");
        }
        return "+91" + national;
    }

    private static String stripCountryAndTrunkPrefixes(String digits) {
        String remaining = digits;
        // "0091..." — international access code written out
        if (remaining.length() == 14 && remaining.startsWith("0091")) {
            remaining = remaining.substring(4);
        }
        // "91..." — country code, with or without a leading +
        if (remaining.length() == 12 && remaining.startsWith("91")) {
            remaining = remaining.substring(2);
        }
        // "0..." — domestic trunk prefix
        if (remaining.length() == 11 && remaining.startsWith("0")) {
            remaining = remaining.substring(1);
        }
        return remaining;
    }
}
