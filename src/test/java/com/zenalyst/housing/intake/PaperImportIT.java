package com.zenalyst.housing.intake;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenalyst.housing.platform.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

class PaperImportIT extends AbstractIntegrationTest {

    private static final String SCHEME = "PAPER-IT";

    private static final String HEADER =
            "paper_reference,received_at,full_name,date_of_birth,government_id,phone,email,"
                    + "address_line,ward_code,category,gender,local_resident,disability,"
                    + "ex_serviceperson,annual_income";

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void createScheme() {
        IntakeFixtures.openScheme(jdbc, SCHEME);
    }

    private ResponseEntity<String> upload(String csv) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        ByteArrayResource file = new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "applications.csv";
            }
        };
        form.add("file", file);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        return rest.postForEntity(
                "/api/v1/schemes/" + SCHEME + "/applications:import?enteredBy=clerk-3",
                new HttpEntity<>(form, headers), String.class);
    }

    @Test
    @DisplayName("good rows are accepted and bad rows are reported, in the same response")
    void partialFailureIsAResultNotAnError() {
        String csv = HEADER + "\n"
                + "RCPT-001,2026-03-10,Ramesh Kumar,1990-02-01," + IntakeFixtures.VALID_ID_1
                + ",9876543210,r@example.com,\"12 Nehru Road, Ward 7\",W-07,OBC,MALE,true,false,false,250000\n"
                + "RCPT-002,2026-03-10,,1990-02-01,234567890125,not-a-phone,bad-email,,W-07,NOPE,MALE,true,false,false,x\n"
                + "RCPT-003,2026-03-11,Sita Devi,01/03/1985," + IntakeFixtures.VALID_ID_2
                + ",9876543211,s@example.com,\"9 MG Road\",W-02,SC,FEMALE,false,true,false,120000\n";

        ResponseEntity<String> response = upload(csv);

        // 200, not 400: most of the file succeeded, and the operator needs the per-row detail.
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"totalRows\":3")
                .contains("\"accepted\":2")
                .contains("\"rejected\":1")
                .contains("\"rowNumber\":3")          // the bad row, numbered as a spreadsheet shows it
                .contains("\"paperReference\":\"RCPT-002\"")
                .contains("CHECKSUM_FAILED");
    }

    @Test
    @DisplayName("re-uploading a corrected file does not import the good rows a second time")
    void reUploadSkipsRowsAlreadyImported() {
        String first = HEADER + "\n"
                + "RCPT-101,2026-03-10,Anil Verma,1990-02-01," + IntakeFixtures.VALID_ID_3
                + ",9876543212,a@example.com,\"1 Park Street\",W-01,GEN,MALE,true,false,false,300000\n"
                + "RCPT-102,2026-03-10,Broken Row,1990-02-01,123,,,,W-01,GEN,MALE,,,,\n";

        assertThat(upload(first).getBody()).contains("\"accepted\":1").contains("\"rejected\":1");

        // The clerk fixes row 2 and re-uploads the whole file, as anyone would.
        String corrected = HEADER + "\n"
                + "RCPT-101,2026-03-10,Anil Verma,1990-02-01," + IntakeFixtures.VALID_ID_3
                + ",9876543212,a@example.com,\"1 Park Street\",W-01,GEN,MALE,true,false,false,300000\n"
                + "RCPT-102,2026-03-10,Fixed Row,1990-02-01," + IntakeFixtures.VALID_ID_4
                + ",9876543213,f@example.com,\"2 Park Street\",W-01,GEN,MALE,true,false,false,300000\n";

        ResponseEntity<String> second = upload(corrected);

        assertThat(second.getBody())
                .contains("\"accepted\":1")           // only the newly fixed row
                .contains("\"alreadyImported\":1");   // the row that had already landed

        Integer anilRows = jdbc.queryForObject(
                "SELECT count(*) FROM application WHERE paper_reference = 'RCPT-101'", Integer.class);
        assertThat(anilRows).isEqualTo(1);
    }

    @Test
    @DisplayName("a form handed in before the deadline counts, even when typed up long after")
    void receiptDateIsTheSubmissionDate() {
        String csv = HEADER + "\n"
                + "RCPT-201,2026-03-14,Onthe Time,1990-02-01," + IntakeFixtures.VALID_ID_5
                + ",9876543214,t@example.com,\"5 Station Road\",W-03,ST,FEMALE,true,false,false,90000\n";

        assertThat(upload(csv).getBody()).contains("\"accepted\":1");

        var row = jdbc.queryForMap(
                "SELECT submitted_at, recorded_at, channel FROM application WHERE paper_reference = 'RCPT-201'");

        // This is the distinction the whole paper channel exists to preserve: the applicant
        // applied on 14 March; we recorded it whenever the clerk got to it.
        assertThat(row.get("submitted_at").toString()).startsWith("2026-03-14");
        assertThat(((java.sql.Timestamp) row.get("recorded_at")).toInstant())
                .isAfter(((java.sql.Timestamp) row.get("submitted_at")).toInstant());
        assertThat(row.get("channel")).isEqualTo("PAPER");
    }

    @Test
    @DisplayName("a row with no receipt reference is refused — it cannot be traced to a form")
    void refusesRowWithoutPaperReference() {
        String csv = HEADER + "\n"
                + ",2026-03-10,No Reference,1990-02-01," + IntakeFixtures.VALID_ID_1
                + ",9876543210,n@example.com,\"7 Lake View\",W-04,GEN,MALE,true,false,false,100000\n";

        assertThat(upload(csv).getBody())
                .contains("\"rejected\":1")
                .contains("\"field\":\"paper_reference\"");
    }

    @Test
    @DisplayName("a file missing required columns is refused before any row is processed")
    void refusesFileWithMissingColumns() {
        ResponseEntity<String> response = upload("full_name,date_of_birth\nRamesh,1990-02-01\n");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("missing required column");
    }

    @Test
    @DisplayName("quoted fields containing commas survive intact")
    void handlesQuotedFields() {
        String csv = HEADER + "\n"
                + "RCPT-301,2026-03-10,Comma Person,1990-02-01," + IntakeFixtures.VALID_ID_2
                + ",9876543215,c@example.com,\"Flat 4, Block B, Nehru Nagar\",W-05,EWS,OTHER,true,false,false,80000\n";

        assertThat(upload(csv).getBody()).contains("\"accepted\":1");

        String address = jdbc.queryForObject(
                "SELECT address_line FROM application WHERE paper_reference = 'RCPT-301'", String.class);
        assertThat(address).isEqualTo("Flat 4, Block B, Nehru Nagar");
    }
}
