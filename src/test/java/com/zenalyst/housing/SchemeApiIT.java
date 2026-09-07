package com.zenalyst.housing;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenalyst.housing.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end check that the HTTP layer, the error model and the database are wired together.
 */
class SchemeApiIT extends AbstractIntegrationTest {

    @Test
    @DisplayName("GET /api/v1/schemes returns a list")
    void listsSchemes() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/schemes", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).startsWith("[");
    }

    @Test
    @DisplayName("an unknown scheme returns an RFC 9457 problem document, not a whitelabel page")
    void unknownSchemeReturnsProblemDetail() {
        ResponseEntity<String> response = rest.getForEntity("/api/v1/schemes/NO-SUCH-SCHEME", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .contains("\"type\":\"https://zenalyst.example/problems/not-found\"")
                .contains("\"identifier\":\"NO-SUCH-SCHEME\"")
                .doesNotContain("Exception")
                .doesNotContain("com.zenalyst");
    }

    @Test
    @DisplayName("actuator health reports UP")
    void healthIsUp() {
        ResponseEntity<String> response = rest.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
