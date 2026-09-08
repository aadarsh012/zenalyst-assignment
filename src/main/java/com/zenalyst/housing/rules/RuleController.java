package com.zenalyst.housing.rules;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
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
public class RuleController {

    private final RuleService rules;

    public RuleController(RuleService rules) {
        this.rules = rules;
    }

    /** Creates a draft quota matrix. Validated now; no draw can use it until it is activated. */
    @PostMapping(path = "/api/v1/schemes/{code}/rules",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RuleVersionResponse create(
            @PathVariable String code,
            @Valid @RequestBody CreateRuleVersionRequest request,
            @RequestParam("createdBy") @NotBlank String createdBy) {
        return rules.create(code, request, createdBy);
    }

    /** Puts a version in force, superseding whatever it replaces, in one transaction. */
    @PostMapping(path = "/api/v1/schemes/{code}/rules/{version}:activate",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public RuleVersionResponse activate(
            @PathVariable String code,
            @PathVariable String version,
            @RequestParam("activatedBy") @NotBlank String activatedBy) {
        return rules.activate(code, version, activatedBy);
    }

    /** Every version this scheme has had, superseded ones included. */
    @GetMapping(path = "/api/v1/schemes/{code}/rules",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public List<RuleVersionResponse> list(@PathVariable String code) {
        return rules.list(code);
    }
}
