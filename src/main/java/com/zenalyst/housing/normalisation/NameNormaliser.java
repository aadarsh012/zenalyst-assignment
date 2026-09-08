package com.zenalyst.housing.normalisation;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reduces a name to a matching key: accents folded, case removed, punctuation dropped,
 * whitespace collapsed.
 *
 * <p>Two forms of the name are kept by the system. The name as written is what appears on
 * correspondence and on the published list, because that is the applicant's name and nobody
 * wants to receive a flat allotment addressed to "RAMESH KUMAR" when they wrote "Ramesh Kumār".
 * The key produced here exists only to decide whether two records might be the same person.
 *
 * <p><strong>Token order is preserved.</strong> "RAMESH KUMAR" and "KUMAR RAMESH" produce
 * different keys, even though a data-entry clerk may well have swapped given and family name.
 * Catching that reordering belongs to fuzzy matching, where a human confirms the merge — not
 * here, where the key feeds automatic merging. Collapsing the order would silently merge two
 * genuinely different people who happen to share reversed names, which is a far worse failure
 * than making a human look at a maybe.
 */
public final class NameNormaliser {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final int MAX_LENGTH = 200;

    private NameNormaliser() {
    }

    /**
     * @return the display form: trimmed, internal whitespace collapsed, otherwise untouched
     */
    public static String displayForm(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidFieldException(field, "REQUIRED", "must be provided");
        }
        String collapsed = WHITESPACE.matcher(raw.trim()).replaceAll(" ");
        if (collapsed.length() > MAX_LENGTH) {
            throw new InvalidFieldException(field, "TOO_LONG",
                    "must be at most %d characters".formatted(MAX_LENGTH));
        }
        return collapsed;
    }

    /**
     * @return the matching key: uppercase, unaccented, alphanumeric, single-spaced
     */
    public static String key(String field, String raw) {
        String display = displayForm(field, raw);

        String decomposed = Normalizer.normalize(display, Normalizer.Form.NFD);
        String unaccented = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        String alphanumeric = NON_ALPHANUMERIC.matcher(unaccented).replaceAll(" ");
        String key = WHITESPACE.matcher(alphanumeric.trim()).replaceAll(" ").toUpperCase(Locale.ROOT);

        if (key.isEmpty()) {
            throw new InvalidFieldException(field, "NOT_A_NAME",
                    "must contain at least one letter or digit");
        }
        return key;
    }
}
