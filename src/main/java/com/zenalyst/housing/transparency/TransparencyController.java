package com.zenalyst.housing.transparency;

import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The endpoints that make the result answerable rather than merely correct.
 *
 * <p>The brief says the final list will be questioned by an applicant, by a newspaper, and quite
 * possibly in court. There is one endpoint here for each of them.
 */
@RestController
public class TransparencyController {

    private final ExplainService explain;
    private final DrawVerificationService verification;
    private final AuditChainVerifier auditChain;
    private final ResultsExportService export;

    public TransparencyController(
            ExplainService explain,
            DrawVerificationService verification,
            AuditChainVerifier auditChain,
            ResultsExportService export) {
        this.explain = explain;
        this.verification = verification;
        this.auditChain = auditChain;
        this.export = export;
    }

    /**
     * Why this application got a flat, or did not.
     *
     * <p>The applicant's endpoint. Returns the whole chain of reasoning — identity, eligibility,
     * inclusion in the frozen register, lottery rank, standing in every pool competed in, and the
     * cutoff each of those pools reached.
     *
     * <p><strong>An applicant sees their own file and nobody else's.</strong> The token's subject is
     * their application number and it is compared against the one requested — the JWT principal
     * deciding access rather than merely proving somebody logged in. Staff roles may read any file,
     * because investigating a complaint requires it.
     *
     * <p>This is the one transparency endpoint that is not public. The response carries no name or
     * address, but it does carry an applicant's category and the reasons they were found ineligible,
     * and application numbers are sequential. Leaving it open would let anybody walk the register
     * and build exactly the profile the rest of the system takes care not to store.
     */
    @PreAuthorize("hasAnyRole('AUDITOR', 'OPERATOR', 'ADMIN') or authentication.name == #applicationNo")
    @GetMapping(path = "/api/v1/applications/{applicationNo}/explain",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ExplainResponse explain(@PathVariable String applicationNo) {
        return explain.explain(applicationNo);
    }

    /**
     * Re-derives a draw from its published inputs and reports whether the stored result matches.
     *
     * <p>The court's endpoint. Rebuilds the register's Merkle root, checks the seed against its
     * commitment, re-runs the allocator, and compares the allotment name by name.
     *
     * <p>A {@code POST} rather than a {@code GET} because it recomputes an entire draw; it is not a
     * lookup and should not be cached as one.
     */
    @PostMapping(path = "/api/v1/draws/{drawId}/verify",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DrawVerificationReport verify(@PathVariable UUID drawId) {
        return verification.verify(drawId);
    }

    /**
     * Rehashes the entire audit chain and reports the first event that fails, if any.
     *
     * <p>The auditor's endpoint. Recomputes each event's hash from its stored contents rather than
     * comparing stored hashes to each other — the latter catches a deleted event but not an edited
     * one.
     */
    @GetMapping(path = "/api/v1/audit/verify", produces = MediaType.APPLICATION_JSON_VALUE)
    public AuditChainReport verifyAuditChain() {
        return auditChain.verify();
    }

    /**
     * The published allotment as a file.
     *
     * <p>The newspaper's endpoint. Carries every hash needed to check it in the header, and no
     * personal data in the rows.
     */
    @GetMapping(path = "/api/v1/draws/{drawId}/results.csv", produces = "text/csv")
    public ResponseEntity<String> results(@PathVariable UUID drawId) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"allotment-%s.csv\"".formatted(drawId))
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .body(export.exportCsv(drawId));
    }
}
