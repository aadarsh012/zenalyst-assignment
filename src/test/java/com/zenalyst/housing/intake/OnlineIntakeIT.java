package com.zenalyst.housing.intake;

import static com.zenalyst.housing.intake.IntakeFixtures.VALID_ID_1;
import static com.zenalyst.housing.intake.IntakeFixtures.VALID_ID_2;
import static com.zenalyst.housing.intake.IntakeFixtures.VALID_ID_3;
import static com.zenalyst.housing.intake.IntakeFixtures.VALID_ID_4;
import static org.assertj.core.api.Assertions.assertThat;

import com.zenalyst.housing.platform.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class OnlineIntakeIT extends AbstractIntegrationTest {

    private static final String SCHEME = "ONLINE-IT";

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void createScheme() {
        IntakeFixtures.openScheme(jdbc, SCHEME);
    }

    private ResponseEntity<String> submit(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/api/v1/schemes/" + SCHEME + "/applications",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    @Test
    @DisplayName("an application is accepted and given a quotable number")
    void acceptsApplication() {
        ResponseEntity<String> response = submit(
                IntakeFixtures.json(IntakeFixtures.validRequest("Ramesh Kumar", VALID_ID_1)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody())
                .contains("\"applicationNo\":\"" + SCHEME + "-")
                .contains("\"channel\":\"ONLINE\"")
                .contains("\"governmentIdLast4\":\"0124\"");
    }

    @Test
    @DisplayName("the submitted application can be retrieved by its number")
    void retrievableByNumber() {
        String created = submit(IntakeFixtures.json(
                IntakeFixtures.validRequest("Sita Devi", VALID_ID_2))).getBody();
        String applicationNo = created.replaceAll(".*\"applicationNo\":\"([^\"]+)\".*", "$1");

        ResponseEntity<String> fetched =
                rest.getForEntity("/api/v1/applications/" + applicationNo, String.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).contains("\"fullName\":\"Sita Devi\"");
    }

    @Test
    @DisplayName("the identity number is never stored, only its token and last four digits")
    void doesNotStoreTheIdentityNumber() {
        submit(IntakeFixtures.json(IntakeFixtures.validRequest("Anil Verma", VALID_ID_3)));

        // Searching every text column of the row for the digits we were given.
        Integer leaks = jdbc.queryForObject("""
                SELECT count(*) FROM application
                WHERE raw_payload::text LIKE ? OR full_name LIKE ? OR address_line LIKE ?
                """, Integer.class, "%" + VALID_ID_3 + "%", "%" + VALID_ID_3 + "%", "%" + VALID_ID_3 + "%");
        assertThat(leaks).isZero();

        String rawPayload = jdbc.queryForObject(
                "SELECT raw_payload::text FROM application WHERE full_name = 'Anil Verma'", String.class);
        assertThat(rawPayload).contains("<redacted>");
    }

    @Test
    @DisplayName("every bad field is reported at once, not one at a time")
    void reportsAllViolationsTogether() {
        String body = """
                {
                  "fullName": "",
                  "dateOfBirth": "31/02/1990",
                  "governmentId": "123",
                  "phone": "12345",
                  "email": "not-an-email",
                  "addressLine": "",
                  "category": "NOPE",
                  "gender": "FEMALE"
                }
                """;

        ResponseEntity<String> response = submit(body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .contains("\"field\":\"fullName\"")
                .contains("\"field\":\"dateOfBirth\"")
                .contains("\"field\":\"governmentId\"")
                .contains("\"field\":\"phone\"")
                .contains("\"field\":\"email\"")
                .contains("\"field\":\"addressLine\"")
                .contains("\"field\":\"category\"");
    }

    @Test
    @DisplayName("a mistyped identity number is caught by its check digit, at the door")
    void rejectsMistypedIdentityNumber() {
        ResponseEntity<String> response = submit(IntakeFixtures.json(
                IntakeFixtures.validRequest("Typo Person", "234567890125")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("CHECKSUM_FAILED");
    }

    @Test
    @DisplayName("applications to a closed scheme are refused, with the reason")
    void refusesClosedScheme() {
        IntakeFixtures.scheme(jdbc, "CLOSED-IT", "CLOSED",
                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                java.time.Instant.parse("2026-02-01T00:00:00Z"));

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/schemes/CLOSED-IT/applications", HttpMethod.POST,
                new HttpEntity<>(IntakeFixtures.json(
                        IntakeFixtures.validRequest("Late Applicant", VALID_ID_4)), headers),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("not accepting applications");
    }

    @Test
    @DisplayName("an unknown application number is a problem document, not a stack trace")
    void unknownApplicationNumber() {
        ResponseEntity<String> response =
                rest.getForEntity("/api/v1/applications/NOPE-000001", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).doesNotContain("Exception").contains("not-found");
    }
}
