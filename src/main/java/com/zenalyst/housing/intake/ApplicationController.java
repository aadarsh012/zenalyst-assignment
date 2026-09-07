package com.zenalyst.housing.intake;

import jakarta.validation.constraints.NotBlank;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Intake endpoints.
 *
 * <p>Online submission and paper import are separate endpoints rather than one endpoint with a
 * channel parameter, and that separation is a safety property rather than a matter of taste.
 * Only the paper path may set the submission date to something other than now — a clerk typing
 * up a form received last week has to. If the two shared an endpoint, any member of the public
 * could post a backdated application and claim to have met a deadline they missed. Keeping the
 * capability on its own endpoint means phase 8 secures it by putting a role on one route, with
 * no risk of the public route inheriting the privilege.
 */
@RestController
@Validated
public class ApplicationController {

    private final IntakeService intake;
    private final PaperImportService paperImport;

    public ApplicationController(IntakeService intake, PaperImportService paperImport) {
        this.intake = intake;
        this.paperImport = paperImport;
    }

    /**
     * Submit an application online.
     *
     * <p>Supplying an {@code Idempotency-Key} is optional but strongly advised: with one, a
     * retried or double-clicked submission returns the original response instead of creating a
     * second application. Without one, the second submission becomes a second application and is
     * dealt with later by deduplication.
     */
    @PostMapping(path = "/api/v1/schemes/{code}/applications",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> submit(
            @PathVariable String code,
            @RequestBody SubmitApplicationRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {

        IntakeOutcome outcome = intake.submitOnline(code, request, idempotencyKey);

        return ResponseEntity.status(outcome.status())
                .contentType(MediaType.APPLICATION_JSON)
                // Tells a retrying client that its earlier attempt had already succeeded, rather
                // than leaving it to infer that from an unexpected 200.
                .header("Idempotent-Replay", Boolean.toString(outcome.replayed()))
                .body(outcome.body());
    }

    /**
     * Import a file of paper applications.
     *
     * <p>Returns {@code 200} with a per-row report even when rows failed. See
     * {@link ImportReport} for why partial success is a result rather than an error.
     */
    @PostMapping(path = "/api/v1/schemes/{code}/applications:import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ImportReport importPaper(
            @PathVariable String code,
            @RequestPart("file") MultipartFile file,
            @RequestParam("enteredBy") @NotBlank String enteredBy) throws IOException {

        try (Reader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            return paperImport.importCsv(code, reader, enteredBy);
        }
    }

    @GetMapping(path = "/api/v1/applications/{applicationNo}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApplicationResponse byApplicationNo(@PathVariable String applicationNo) {
        return intake.byApplicationNo(applicationNo);
    }
}
