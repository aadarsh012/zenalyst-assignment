package com.zenalyst.housing.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenalyst.housing.intake.IntakeFixtures;
import com.zenalyst.housing.intake.SubmitApplicationRequest;
import com.zenalyst.housing.platform.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class RegistryFreezeIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    private String scheme;

    @BeforeEach
    void createScheme(TestInfo testInfo) {
        scheme = "FREEZE-" + testInfo.getTestMethod().orElseThrow().getName();
        IntakeFixtures.openScheme(jdbc, scheme);
    }

    // --- eligibility -------------------------------------------------------

    @Test
    @DisplayName("an unverified category claim costs the benefit, not the place in the draw")
    void unverifiedClaimDoesNotDisqualify() {
        String applicationNo = submit("Sita Devi", "1990-02-01", IntakeFixtures.VALID_ID_1, "SC");

        JsonNode eligibility = eligibility(applicationNo);

        assertThat(eligibility.path("eligible").asBoolean()).isTrue();
        assertThat(eligibility.path("declaredCategory").asText()).isEqualTo("SC");
        assertThat(eligibility.path("effectiveCategory").asText()).isEqualTo("GEN");
        assertThat(codes(eligibility, "NOT_VERIFIED")).contains("CATEGORY_CLAIM");
        assertThat(eligibility.path("claimsAwaitingVerification").toString()).contains("CATEGORY");
    }

    @Test
    @DisplayName("verifying the certificate moves the applicant into their category")
    void verificationHonoursTheClaim() {
        String applicationNo = submit("Sita Devi", "1990-02-01", IntakeFixtures.VALID_ID_1, "SC");

        JsonNode afterVerification = verify(applicationNo, "CATEGORY", "VERIFIED", "CERT-SC-4471");

        assertThat(afterVerification.path("effectiveCategory").asText()).isEqualTo("SC");

        // Claims are verified one at a time. This applicant also claimed local residence, which
        // nobody has looked at yet — so she competes as SC but without local preference, and the
        // response says exactly which document is still outstanding.
        assertThat(afterVerification.path("claimsAwaitingVerification").toString())
                .doesNotContain("CATEGORY")
                .contains("LOCAL_RESIDENCE");
        assertThat(afterVerification.path("effectiveLocalResident").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("an underage applicant is disqualified, and told why")
    void underageApplicantIsDisqualified() {
        // The scheme closes 2027-01-01, so someone born in 2010 is seventeen at the reference date.
        String applicationNo = submit("Young Applicant", "2010-06-01", IntakeFixtures.VALID_ID_2, "GEN");

        JsonNode eligibility = eligibility(applicationNo);

        assertThat(eligibility.path("eligible").asBoolean()).isFalse();
        assertThat(codes(eligibility, "FAIL")).contains("MINIMUM_AGE");
    }

    @Test
    @DisplayName("a claim cannot be verified twice")
    void verificationIsRecordedOnce() {
        String applicationNo = submit("Sita Devi", "1990-02-01", IntakeFixtures.VALID_ID_1, "SC");
        verify(applicationNo, "CATEGORY", "VERIFIED", "CERT-1");

        ResponseEntity<String> second = rest.exchange(
                "/api/v1/applications/" + applicationNo + "/verifications?verifiedBy=clerk-2",
                HttpMethod.POST,
                json("{\"claim\":\"CATEGORY\",\"outcome\":\"REJECTED\",\"evidenceReference\":\"CERT-1\"}"),
                String.class);

        // Overwriting would erase that the certificate had been accepted and then wasn't, which is
        // exactly what an applicant contesting the outcome needs to be able to establish.
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody()).contains("already recorded");
    }

    // --- freezing ----------------------------------------------------------

    @Test
    @DisplayName("freezing publishes a root, a rules hash and the counts")
    void freezePublishesARoot() {
        submit("Sita Devi", "1990-02-01", IntakeFixtures.VALID_ID_1, "GEN");
        submit("Ramesh Kumar", "1988-05-05", IntakeFixtures.VALID_ID_2, "OBC");

        JsonNode frozen = freeze();

        assertThat(frozen.path("registryRoot").asText()).matches("^[0-9a-f]{64}$");
        assertThat(frozen.path("rulesHash").asText()).matches("^[0-9a-f]{64}$");
        assertThat(frozen.path("candidateCount").asInt()).isEqualTo(2);
        assertThat(frozen.path("eligibleCount").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("freezing unchanged data twice produces the identical root")
    void freezingIsDeterministic() {
        submit("Sita Devi", "1990-02-01", IntakeFixtures.VALID_ID_1, "GEN");
        submit("Ramesh Kumar", "1988-05-05", IntakeFixtures.VALID_ID_2, "OBC");

        // This is the property everything else rests on. If two freezes of identical data could
        // differ, no third party could ever recompute our root and get ours.
        assertThat(freeze().path("registryRoot").asText())
                .isEqualTo(freeze().path("registryRoot").asText());
    }

    @Test
    @DisplayName("changing anything that affects the draw changes the root")
    void anyChangeChangesTheRoot() {
        String applicationNo = submit("Sita Devi", "1990-02-01", IntakeFixtures.VALID_ID_1, "SC");
        String before = freeze().path("registryRoot").asText();

        // Verifying the certificate moves her from GEN to SC — a different pool, a different draw.
        verify(applicationNo, "CATEGORY", "VERIFIED", "CERT-SC-4471");

        assertThat(freeze().path("registryRoot").asText()).isNotEqualTo(before);
    }

    @Test
    @DisplayName("adding an applicant changes the root")
    void addingAnApplicantChangesTheRoot() {
        submit("Sita Devi", "1990-02-01", IntakeFixtures.VALID_ID_1, "GEN");
        String before = freeze().path("registryRoot").asText();

        submit("Ramesh Kumar", "1988-05-05", IntakeFixtures.VALID_ID_2, "OBC");

        assertThat(freeze().path("registryRoot").asText()).isNotEqualTo(before);
    }

    @Test
    @DisplayName("ineligible applicants are in the register with their reasons, not omitted")
    void ineligibleApplicantsAreAccountedFor() {
        submit("Adult Applicant", "1990-02-01", IntakeFixtures.VALID_ID_1, "GEN");
        submit("Young Applicant", "2010-06-01", IntakeFixtures.VALID_ID_2, "GEN");

        JsonNode frozen = freeze();
        assertThat(frozen.path("candidateCount").asInt()).isEqualTo(2);
        assertThat(frozen.path("eligibleCount").asInt()).isEqualTo(1);

        // Four thousand applied; the published register must account for all four thousand.
        // Silently dropping the ineligible would make the count unexplainable.
        String published = candidates(frozen.path("registryRoot").asText()).toString();
        assertThat(published).contains("MINIMUM_AGE");
    }

    @Test
    @DisplayName("the published register contains no names, addresses or contact details")
    void publishedRegisterCarriesNoPersonalData() {
        submit("Sita Devi", "1990-02-01", IntakeFixtures.VALID_ID_1, "GEN");
        String published = candidates(freeze().path("registryRoot").asText()).toString();

        assertThat(published)
                .doesNotContain("Sita Devi")
                .doesNotContain("applicant@example.com")
                .doesNotContain("9876543210")
                .doesNotContain("Nehru Road")
                .doesNotContain(IntakeFixtures.VALID_ID_1);
    }

    // --- inclusion proofs --------------------------------------------------

    @Test
    @DisplayName("every applicant can prove their row was among the inputs")
    void everyApplicantCanProveInclusion() {
        List<String> applications = new ArrayList<>();
        String[] identityNumbers = {
                IntakeFixtures.VALID_ID_1, IntakeFixtures.VALID_ID_2, IntakeFixtures.VALID_ID_3,
                IntakeFixtures.VALID_ID_4, IntakeFixtures.VALID_ID_5};
        for (int i = 0; i < identityNumbers.length; i++) {
            applications.add(submit("Applicant " + i, "199%d-02-01".formatted(i), identityNumbers[i], "GEN"));
        }

        String root = freeze().path("registryRoot").asText();

        for (String applicationNo : applications) {
            JsonNode proof = proof(root, applicationNo);
            assertThat(proof.path("verified").asBoolean())
                    .as("proof for %s", applicationNo).isTrue();
            assertThat(proof.path("canonicalJson").asText()).contains(applicationNo);
            assertThat(proof.path("proof").size()).isGreaterThan(0);
        }
    }

    @Test
    @DisplayName("a proof recomputes the root independently, exactly as an outsider would")
    void proofVerifiesWithoutTheService() {
        submit("Sita Devi", "1990-02-01", IntakeFixtures.VALID_ID_1, "GEN");
        String second = submit("Ramesh Kumar", "1988-05-05", IntakeFixtures.VALID_ID_2, "OBC");
        submit("Anil Verma", "1985-03-03", IntakeFixtures.VALID_ID_3, "GEN");

        String root = freeze().path("registryRoot").asText();
        JsonNode response = proof(root, second);

        List<MerkleTree.ProofStep> steps = new ArrayList<>();
        for (JsonNode step : response.path("proof")) {
            steps.add(new MerkleTree.ProofStep(
                    MerkleTree.Side.valueOf(step.path("side").asText()), step.path("hash").asText()));
        }

        // Recomputed here with the published bytes and nothing else — no repository, no service.
        assertThat(MerkleTree.verify(response.path("canonicalJson").asText(), steps, root)).isTrue();
    }

    @Test
    @DisplayName("the published rows rebuild the published root")
    void publishedRowsRebuildTheRoot() {
        submit("Sita Devi", "1990-02-01", IntakeFixtures.VALID_ID_1, "GEN");
        submit("Ramesh Kumar", "1988-05-05", IntakeFixtures.VALID_ID_2, "OBC");
        submit("Anil Verma", "1985-03-03", IntakeFixtures.VALID_ID_3, "GEN");

        String root = freeze().path("registryRoot").asText();

        List<String> published = new ArrayList<>();
        for (JsonNode candidate : candidates(root)) {
            published.add(candidate.path("canonicalJson").asText());
        }

        // A journalist downloads the register and rebuilds the tree. This is the whole offer.
        assertThat(MerkleTree.of(published).root()).isEqualTo(root);
    }

    @Test
    @DisplayName("someone who was not a candidate gets no proof")
    void noProofForANonCandidate() {
        submit("Sita Devi", "1990-02-01", IntakeFixtures.VALID_ID_1, "GEN");
        String root = freeze().path("registryRoot").asText();

        ResponseEntity<String> response = rest.getForEntity(
                "/api/v1/registry/" + root + "/proof/NOPE-000001", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("the freeze and every verification are in the audit chain")
    void everythingIsAudited() {
        String applicationNo = submit("Sita Devi", "1990-02-01", IntakeFixtures.VALID_ID_1, "SC");
        verify(applicationNo, "CATEGORY", "VERIFIED", "CERT-SC-4471");
        String root = freeze().path("registryRoot").asText();

        String verification = jdbc.queryForObject("""
                SELECT payload::text FROM audit_event
                WHERE action = 'CLAIM_VERIFIED' AND subject_id = ?
                """, String.class, applicationNo);
        assertThat(verification).contains("CATEGORY").contains("CERT-SC-4471");

        String freeze = jdbc.queryForObject("""
                SELECT payload::text FROM audit_event
                WHERE action = 'REGISTRY_FROZEN' AND subject_id = ?
                ORDER BY seq DESC LIMIT 1
                """, String.class, scheme);
        assertThat(freeze).contains(root);
    }

    // --- helpers -----------------------------------------------------------

    private String submit(String name, String dateOfBirth, String governmentId, String category) {
        SubmitApplicationRequest request = new SubmitApplicationRequest(
                name, dateOfBirth, governmentId, "9876543210", "applicant@example.com",
                "12 Nehru Road", "W-07", category, "FEMALE", "true", "false", "false", "250000");

        ResponseEntity<String> response = rest.exchange(
                "/api/v1/schemes/" + scheme + "/applications", HttpMethod.POST,
                json(IntakeFixtures.json(request)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return read(response.getBody()).path("applicationNo").asText();
    }

    private JsonNode eligibility(String applicationNo) {
        return get("/api/v1/applications/" + applicationNo + "/eligibility");
    }

    private JsonNode verify(String applicationNo, String claim, String outcome, String evidence) {
        ResponseEntity<String> response = rest.exchange(
                "/api/v1/applications/" + applicationNo + "/verifications?verifiedBy=clerk-anita",
                HttpMethod.POST,
                json("{\"claim\":\"%s\",\"outcome\":\"%s\",\"evidenceReference\":\"%s\"}"
                        .formatted(claim, outcome, evidence)),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return read(response.getBody());
    }

    private JsonNode freeze() {
        ResponseEntity<String> response = rest.postForEntity(
                "/api/v1/schemes/" + scheme + "/registry:freeze?frozenBy=registrar-1", null, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return read(response.getBody());
    }

    private JsonNode candidates(String root) {
        return get("/api/v1/registry/" + root + "/candidates");
    }

    private JsonNode proof(String root, String applicationNo) {
        return get("/api/v1/registry/" + root + "/proof/" + applicationNo);
    }

    private JsonNode get(String path) {
        ResponseEntity<String> response = rest.getForEntity(path, String.class);
        assertThat(response.getStatusCode()).as(path).isEqualTo(HttpStatus.OK);
        return read(response.getBody());
    }

    private List<String> codes(JsonNode eligibility, String outcome) {
        List<String> codes = new ArrayList<>();
        for (JsonNode check : eligibility.path("checks")) {
            if (check.path("outcome").asText().equals(outcome)) {
                codes.add(check.path("code").asText());
            }
        }
        return codes;
    }

    private HttpEntity<String> json(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(body, headers);
    }

    private JsonNode read(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("unreadable response: " + body, e);
        }
    }
}
