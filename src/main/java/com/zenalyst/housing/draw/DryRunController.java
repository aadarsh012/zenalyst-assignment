package com.zenalyst.housing.draw;

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class DryRunController {

    private final DryRunService dryRun;

    public DryRunController(DryRunService dryRun) {
        this.dryRun = dryRun;
    }

    /**
     * Rehearses a draw against the latest frozen register and the active quota matrix.
     *
     * <p>Writes nothing and allots nothing. Use it to find an unfillable reservation or a quota
     * matrix that leaves seats empty <em>before</em> a seed is committed to and the result becomes
     * something the authority has to live with.
     *
     * @param seed any value. Rehearsing with the seed you intend to use would defeat the purpose of
     *             committing to it later.
     */
    @PostMapping(path = "/api/v1/schemes/{code}/draws:dry-run",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public DryRunResponse dryRun(
            @PathVariable String code,
            @RequestParam("seed") @NotBlank String seed) {
        return dryRun.run(code, seed);
    }
}
