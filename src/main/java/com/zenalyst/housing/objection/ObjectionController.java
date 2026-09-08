package com.zenalyst.housing.objection;

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

@RestController
@Validated
public class ObjectionController {

    private final ObjectionService objections;

    public ObjectionController(ObjectionService objections) {
        this.objections = objections;
    }

    /**
     * Files a challenge to a published result.
     *
     * <p>Open to anybody. An applicant objects about their own treatment; a journalist who cannot
     * reproduce the published root objects about the conduct of the draw, and has as much standing
     * to do so.
     */
    @PostMapping(path = "/api/v1/schemes/{code}/objections",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectionResponse file(
            @PathVariable String code,
            @Valid @RequestBody FileObjectionRequest request,
            @RequestParam("filedBy") @NotBlank String filedBy) {
        return objections.file(code, request, filedBy);
    }

    /**
     * Adjudicates an objection.
     *
     * <p>Upholding one changes nothing on its own. It records a finding and states the remedy; the
     * correction and any superseding draw are separate, deliberate acts with their own audit trail.
     */
    @PostMapping(path = "/api/v1/objections/{objectionId}/decision",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectionResponse decide(
            @PathVariable UUID objectionId,
            @Valid @RequestBody ObjectionDecisionRequest request,
            @RequestParam("decidedBy") @NotBlank String decidedBy) {
        return objections.decide(objectionId, request, decidedBy);
    }

    @GetMapping(path = "/api/v1/schemes/{code}/objections",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public List<ObjectionResponse> list(
            @PathVariable String code,
            @RequestParam(value = "status", required = false) ObjectionStatus status) {
        return objections.list(code, status);
    }

    @GetMapping(path = "/api/v1/objections/{objectionId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectionResponse get(@PathVariable UUID objectionId) {
        return objections.get(objectionId);
    }
}
