package com.zenalyst.housing.draw;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The draw ceremony, in the order it must happen.
 *
 * <p>Each endpoint refuses to run out of sequence. That is not defensive coding for its own sake:
 * every one of those orderings is a way an outcome could be chosen rather than discovered.
 */
@RestController
@Validated
public class DrawController {

    private final DrawService draws;

    public DrawController(DrawService draws) {
        this.draws = draws;
    }

    /**
     * Creates a draw and publishes its commitment.
     *
     * <p>Fixes the frozen register, the active quota matrix and the seed commitment together. The
     * seed itself is not in the response — that is the point of committing to it.
     *
     * @param supersedes an existing published draw this one replaces. Permitted only where an
     *                   objection against that draw has been upheld: a result is replaced because
     *                   a challenge was accepted, never because somebody preferred a different one.
     *                   The superseded draw is not modified, and stays published and verifiable.
     */
    @PostMapping(path = "/api/v1/schemes/{code}/draws",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DrawResponse commit(
            @PathVariable String code,
            @RequestParam("committedBy") @NotBlank String committedBy,
            @RequestParam(value = "supersedes", required = false) UUID supersedes) {
        return draws.commit(code, committedBy, supersedes);
    }

    /** Publishes the seed. Anyone can now check it against the commitment. */
    @PostMapping(path = "/api/v1/draws/{drawId}/reveal",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DrawResponse reveal(
            @PathVariable UUID drawId,
            @RequestParam("revealedBy") @NotBlank String revealedBy) {
        return draws.reveal(drawId, revealedBy);
    }

    /**
     * Queues the draw for execution.
     *
     * <p>Returns {@code 202} with the draw in {@code RUNNING}. Poll {@code GET /draws/{id}} for
     * {@code COMPLETED}. Two simultaneous calls result in exactly one execution — the status
     * transition happens under a row lock.
     */
    @PostMapping(path = "/api/v1/draws/{drawId}/execute",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DrawResponse> execute(
            @PathVariable UUID drawId,
            @RequestParam("executedBy") @NotBlank String executedBy) {
        return ResponseEntity.accepted().body(draws.execute(drawId, executedBy));
    }

    /**
     * Declares the result final.
     *
     * <p>After this a database trigger refuses any change to the draw or its allotments. A mistaken
     * result is superseded by a new draw, never edited.
     */
    @PostMapping(path = "/api/v1/draws/{drawId}/publish",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DrawResponse publish(
            @PathVariable UUID drawId,
            @RequestParam("publishedBy") @NotBlank String publishedBy) {
        return draws.publish(drawId, publishedBy);
    }

    @GetMapping(path = "/api/v1/draws/{drawId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public DrawResponse get(@PathVariable UUID drawId) {
        return draws.get(drawId);
    }

    @GetMapping(path = "/api/v1/schemes/{code}/draws", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<DrawResponse> list(@PathVariable String code) {
        return draws.listForScheme(code);
    }
}
