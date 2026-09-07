package com.zenalyst.housing.normalisation;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Normalises email addresses, and derives a matching key that collapses provider-specific
 * aliasing.
 *
 * <p>Two keys are produced from one address. The stored address is what we would actually send
 * mail to. The key is what deduplication compares, and it folds together the addresses that
 * reach the same inbox: {@code r.kumar+flat@gmail.com}, {@code rkumar@gmail.com} and
 * {@code RKumar@googlemail.com} are one mailbox, and a person re-applying because they were not
 * sure the first one went through will often use a variant without thinking about it.
 *
 * <p>Dot-folding is applied only to Google domains, because only Google ignores dots. Applying
 * it universally would merge {@code j.smith@example.com} and {@code js.mith@example.com}, which
 * on most mail servers are two different people. Sub-address stripping ({@code +suffix}) is
 * applied generally, as it is near-universal and is not a distinguishing part of the mailbox.
 *
 * <p>Note that the key never merges anyone on its own: deduplication requires name and date of
 * birth to match as well. Its job is to stop a trivial variation from hiding a duplicate.
 */
public final class EmailNormaliser {

    private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> GOOGLE_DOMAINS = Set.of("gmail.com", "googlemail.com");
    private static final int MAX_LENGTH = 254;

    private EmailNormaliser() {
    }

    /** @return the address lowercased and trimmed, or {@code null} if absent (email is optional) */
    public static String normalise(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() > MAX_LENGTH) {
            throw new InvalidFieldException(field, "TOO_LONG",
                    "must be at most %d characters".formatted(MAX_LENGTH));
        }
        if (!SHAPE.matcher(trimmed).matches()) {
            throw new InvalidFieldException(field, "NOT_AN_EMAIL",
                    "must be an email address of the form name@example.com");
        }
        return trimmed;
    }

    /** @return the deduplication key for a normalised address, or {@code null} if absent */
    public static String key(String normalised) {
        if (normalised == null) {
            return null;
        }
        int at = normalised.lastIndexOf('@');
        String local = normalised.substring(0, at);
        String domain = normalised.substring(at + 1);

        if (GOOGLE_DOMAINS.contains(domain)) {
            domain = "gmail.com";
            local = local.replace(".", "");
        }

        int plus = local.indexOf('+');
        if (plus > 0) {
            local = local.substring(0, plus);
        }

        return local + "@" + domain;
    }
}
