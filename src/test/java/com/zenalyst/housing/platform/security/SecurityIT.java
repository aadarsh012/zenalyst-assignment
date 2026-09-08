package com.zenalyst.housing.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenalyst.housing.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Who may call what.
 *
 * <p>The two halves matter equally. Endpoints that can influence an outcome must refuse an
 * unauthenticated caller, and endpoints by which the system is checked must accept one — a
 * verification that requires the authority's own credentials proves nothing.
 */
class SecurityIT extends AbstractIntegrationTest {

    @Nested
    @DisplayName("checking the result needs no credentials, on purpose")
    class Open {

        @Test
        @DisplayName("a journalist can read the register and rebuild its root")
        void registryIsPublic() {
            assertThat(anonymous().getForEntity("/api/v1/registry/" + "0".repeat(64) + "/candidates",
                    String.class).getStatusCode())
                    // 404 because that root does not exist — not 401, which is the point.
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("anybody can verify the audit chain")
        void auditVerifyIsPublic() {
            assertThat(anonymous().getForEntity("/api/v1/audit/verify", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("anybody can list schemes and read a draw")
        void schemeAndDrawReadsArePublic() {
            assertThat(anonymous().getForEntity("/api/v1/schemes", String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("anybody can apply")
        void applyingIsPublic() {
            ResponseEntity<String> response = anonymous().postForEntity(
                    "/api/v1/schemes/NO-SUCH-SCHEME/applications", "{}", String.class);
            // Rejected on its merits, not for want of a token.
            assertThat(response.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("influencing the outcome needs the right role")
    class Restricted {

        @Test
        @DisplayName("the only route that can backdate a submission is closed to the public")
        void paperImportIsRestricted() {
            // Unsecured, anybody could post a backdated application and claim a deadline they had
            // missed. It was put on its own route in phase 1 precisely so it could be closed here.
            assertThat(anonymous().postForEntity(
                    "/api/v1/schemes/X/applications:import?enteredBy=x", null, String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("an operator cannot freeze the register or run a draw")
        void operatorsCannotDecideOutcomes() {
            TestRestTemplate operator = withRoles("clerk", Role.OPERATOR);

            assertThat(operator.postForEntity(
                    "/api/v1/schemes/X/registry:freeze?frozenBy=x", null, String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(operator.postForEntity(
                    "/api/v1/schemes/X/draws?committedBy=x", null, String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("an auditor may read but not decide")
        void auditorsCannotDecideOutcomes() {
            TestRestTemplate auditor = withRoles("auditor", Role.AUDITOR);

            assertThat(auditor.postForEntity(
                    "/api/v1/schemes/X/rules?createdBy=x", null, String.class)
                    .getStatusCode()).isIn(HttpStatus.FORBIDDEN, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }

        @Test
        @DisplayName("an applicant's token grants nothing operational")
        void applicantsCannotOperate() {
            TestRestTemplate applicant = withRoles("MHS-2026-000001", Role.APPLICANT);

            assertThat(applicant.postForEntity(
                    "/api/v1/schemes/X/deduplication:run?runBy=x", null, String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    @DisplayName("an applicant sees their own file and nobody else's")
    class PerApplicant {

        @Test
        @DisplayName("without a token, nothing")
        void explainNeedsAuthentication() {
            assertThat(anonymous().getForEntity(
                    "/api/v1/applications/MHS-2026-000001/explain", String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("an applicant cannot read somebody else's file")
        void applicantsCannotReadOtherFiles() {
            TestRestTemplate applicant = withRoles("MHS-2026-000001", Role.APPLICANT);

            // The token's subject is compared against the application requested. This is the JWT
            // principal deciding access rather than merely proving somebody logged in.
            assertThat(applicant.getForEntity(
                    "/api/v1/applications/MHS-2026-000999/explain", String.class)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("staff may read any file, because investigating a complaint requires it")
        void auditorsMayReadAnyFile() {
            TestRestTemplate auditor = withRoles("auditor", Role.AUDITOR);

            assertThat(auditor.getForEntity(
                    "/api/v1/applications/NO-SUCH-APPLICATION/explain", String.class)
                    .getStatusCode())
                    // 404, not 403: the authorisation passed and the application simply is not there.
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Test
    @DisplayName("a token this service did not sign is refused")
    void forgedTokensAreRefused() {
        TestRestTemplate forged = withHeader("Authorization",
                "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiIsInJvbGVzIjpbIkFETUlOIl19.not-a-signature");

        assertThat(forged.postForEntity("/api/v1/schemes/X/registry:freeze?frozenBy=x", null, String.class)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("every response carries a correlation id, and honours one that was supplied")
    void correlationIdIsAlwaysPresent() {
        ResponseEntity<String> generated = anonymous().getForEntity("/api/v1/schemes", String.class);
        assertThat(generated.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();

        TestRestTemplate supplied = withHeader("X-Correlation-Id", "trace-from-gateway");
        assertThat(supplied.getForEntity("/api/v1/schemes", String.class)
                .getHeaders().getFirst("X-Correlation-Id")).isEqualTo("trace-from-gateway");
    }
}
