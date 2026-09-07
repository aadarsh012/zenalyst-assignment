package com.zenalyst.housing.platform.hash;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Serialises JSON to a single canonical byte sequence, so that the same data always hashes to
 * the same value.
 *
 * <p>Ordinary JSON serialisation is not deterministic enough to hash. Object key order depends
 * on insertion order, whitespace is arbitrary, and {@code 1.0}, {@code 1.00} and {@code 1e0} are
 * the same number written three ways. Any of those differences changes the hash without changing
 * the data — which, in a system whose whole claim is "recompute this yourself and you will get
 * our answer", turns an honest result into an apparent forgery.
 *
 * <p>The rules, chosen to be reimplementable in half a page of any language:
 * <ul>
 *   <li>object keys sorted by Unicode code point, duplicates impossible</li>
 *   <li>no whitespace anywhere outside string values</li>
 *   <li>numbers in plain notation, no exponent, no trailing zeros, {@code -0} normalised to
 *       {@code 0}</li>
 *   <li>strings escaped minimally: only what JSON requires, always with lowercase {@code \\u}
 *       escapes for control characters</li>
 *   <li>array order preserved — order is data</li>
 * </ul>
 *
 * <p>This is deliberately close to RFC 8785 (JCS) without depending on it, so that a verifier
 * can be written from the list above without pulling in a library.
 */
public final class CanonicalJson {

    private CanonicalJson() {
    }

    public static String render(JsonNode node) {
        StringBuilder out = new StringBuilder();
        write(node, out);
        return out.toString();
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            writeObject((ObjectNode) node, out);
        } else if (node.isArray()) {
            writeArray((ArrayNode) node, out);
        } else if (node.isTextual()) {
            writeString(node.textValue(), out);
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else if (node.isNumber()) {
            out.append(number(node));
        } else {
            throw new IllegalArgumentException("unsupported JSON node type: " + node.getNodeType());
        }
    }

    private static void writeObject(ObjectNode node, StringBuilder out) {
        List<String> names = new ArrayList<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            names.add(it.next());
        }
        // Sort by code point rather than by the default String ordering, which compares UTF-16
        // code units and therefore orders supplementary characters differently.
        names.sort(CanonicalJson::compareByCodePoint);

        out.append('{');
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            writeString(names.get(i), out);
            out.append(':');
            write(node.get(names.get(i)), out);
        }
        out.append('}');
    }

    private static void writeArray(ArrayNode node, StringBuilder out) {
        out.append('[');
        for (int i = 0; i < node.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            write(node.get(i), out);
        }
        out.append(']');
    }

    private static String number(JsonNode node) {
        BigDecimal value = node.decimalValue().stripTrailingZeros();
        if (value.signum() == 0) {
            return "0";
        }
        return value.toPlainString();
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    static int compareByCodePoint(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }
}
