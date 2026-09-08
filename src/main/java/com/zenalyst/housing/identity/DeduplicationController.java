package com.zenalyst.housing.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deduplication endpoints.
 *
 * <p>{@code runBy} and {@code decidedBy} are request parameters today and will come from the
 * authenticated principal in phase 8. They are required rather than optional because an
 * unattributed decision about whose application counts is not a decision anybody can defend
 * later, and defaulting them to {@code "system"} would quietly make that the norm.
 */
@RestController
@Validated
public class DeduplicationController {

    private final DeduplicationService deduplication;
    private final IdentityService identity;

    public DeduplicationController(DeduplicationService deduplication, IdentityService identity) {
        this.deduplication = deduplication;
        this.identity = identity;
    }

    /**
     * Runs a deduplication pass over the whole scheme.
     *
     * <p>Safe to run repeatedly: the same register always produces the same conclusions.
     */
    @PostMapping(path = "/api/v1/schemes/{code}/deduplication:run",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DeduplicationReport run(
            @PathVariable String code,
            @RequestParam("runBy") @NotBlank String runBy) {
        return deduplication.run(code, runBy);
    }

    /** Whether this application competes in the draw, and if not, which one does instead. */
    @GetMapping(path = "/api/v1/applications/{applicationNo}/identity",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public IdentityResponse identity(@PathVariable String applicationNo) {
        return identity.identityOf(applicationNo);
    }

    /**
     * The fuzzy matches awaiting a human.
     *
     * @param status omit to see every review including the decided ones, which is what an auditor
     *               asking "what did you do about the borderline cases?" needs
     */
    @GetMapping(path = "/api/v1/schemes/{code}/duplicate-reviews",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ReviewSummary> reviewQueue(
            @PathVariable String code,
            @RequestParam(value = "status", required = false) ReviewStatus status) {
        return identity.reviewQueue(code, status);
    }

    /**
     * Records a decision on a fuzzy match and rebuilds the scheme's links immediately.
     *
     * @return the fresh deduplication report, so the caller can see the effect of their decision
     *         rather than having to go and look for it
     */
    @PostMapping(path = "/api/v1/duplicate-reviews/{reviewId}/decision",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DeduplicationReport decide(
            @PathVariable UUID reviewId,
            @Valid @RequestBody ReviewDecisionRequest request,
            @RequestParam("decidedBy") @NotBlank String decidedBy) {
        return identity.decide(reviewId, request, decidedBy);
    }
}
