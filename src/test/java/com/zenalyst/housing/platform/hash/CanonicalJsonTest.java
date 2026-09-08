package com.zenalyst.housing.platform.hash;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Canonicalisation is the foundation of every hash this system publishes. If two renderings of
 * the same data can differ, then an honest recomputation by a journalist produces a different
 * hash from ours, and we have no way to prove which of us is right.
 */
class CanonicalJsonTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private String canonical(String json) throws Exception {
        return CanonicalJson.render(mapper.readTree(json));
    }

    @Test
    @DisplayName("key order in the input does not affect the output")
    void sortsKeys() throws Exception {
        String a = canonical("{\"b\":1,\"a\":2,\"c\":3}");
        String b = canonical("{\"c\":3,\"a\":2,\"b\":1}");
        assertThat(a).isEqualTo(b).isEqualTo("{\"a\":2,\"b\":1,\"c\":3}");
    }

    @Test
    @DisplayName("whitespace in the input does not affect the output")
    void ignoresWhitespace() throws Exception {
        assertThat(canonical("{ \"a\" : 1 ,\n \"b\" : 2 }")).isEqualTo("{\"a\":1,\"b\":2}");
    }

    @Test
    @DisplayName("numbers written three ways canonicalise to one")
    void normalisesNumbers() throws Exception {
        assertThat(canonical("{\"n\":1.0}")).isEqualTo("{\"n\":1}");
        assertThat(canonical("{\"n\":1.500}")).isEqualTo("{\"n\":1.5}");
        assertThat(canonical("{\"n\":1e2}")).isEqualTo("{\"n\":100}");
        assertThat(canonical("{\"n\":-0.0}")).isEqualTo("{\"n\":0}");
    }

    @Test
    @DisplayName("array order is preserved — order is data, not formatting")
    void preservesArrayOrder() throws Exception {
        assertThat(canonical("[3,1,2]")).isEqualTo("[3,1,2]");
    }

    @Test
    @DisplayName("nested objects are canonicalised at every level")
    void recursesIntoNestedStructures() throws Exception {
        assertThat(canonical("{\"z\":{\"b\":1,\"a\":[{\"y\":1,\"x\":2}]}}"))
                .isEqualTo("{\"z\":{\"a\":[{\"x\":2,\"y\":1}],\"b\":1}}");
    }

    @Test
    @DisplayName("strings escape only what JSON requires")
    void escapesMinimally() throws Exception {
        assertThat(canonical("{\"s\":\"a\\\"b\\\\c\\nd\\u0001e\"}"))
                .isEqualTo("{\"s\":\"a\\\"b\\\\c\\nd\\u0001e\"}");
        // Non-ASCII is emitted literally rather than escaped, so the output is valid UTF-8 JSON.
        assertThat(canonical("{\"s\":\"Kumār\"}")).isEqualTo("{\"s\":\"Kumār\"}");
    }

    @Test
    void preservesNullsAndBooleans() throws Exception {
        assertThat(canonical("{\"a\":null,\"b\":true,\"c\":false}"))
                .isEqualTo("{\"a\":null,\"b\":true,\"c\":false}");
    }

    @Test
    @DisplayName("keys sort by code point, not by UTF-16 code unit")
    void sortsByCodePoint() {
        // "😀" (U+1F600) is greater than "�" by code point but smaller by the
        // UTF-16 comparison String.compareTo performs. Getting this wrong would make two
        // implementations disagree on exactly the inputs nobody tests by hand.
        assertThat(CanonicalJson.compareByCodePoint("😀", "�")).isPositive();
        assertThat("😀".compareTo("�")).isNegative();
    }
}
