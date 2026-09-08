package com.zenalyst.housing.registry;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class RegistryController {

    private final RegistryFreezeService registry;

    public RegistryController(RegistryFreezeService registry) {
        this.registry = registry;
    }

    /**
     * Freezes the candidate register and publishes its root.
     *
     * <p>Must happen before any seed exists. An authority that could still change the candidate
     * list after seeing the seed could choose the outcome.
     */
    @PostMapping(path = "/api/v1/schemes/{code}/registry:freeze",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public FreezeResponse freeze(
            @PathVariable String code,
            @RequestParam("frozenBy") @NotBlank String frozenBy) {
        return registry.freeze(code, frozenBy);
    }

    /** The most recent freeze for a scheme. */
    @GetMapping(path = "/api/v1/schemes/{code}/registry",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public FreezeResponse latest(@PathVariable String code) {
        FrozenRegistry latest = registry.latestFor(code);
        return new FreezeResponse(
                code, latest.getId().toString(), latest.getRegistryRoot(), latest.getRulesHash(),
                latest.getCandidateCount(), latest.getEligibleCount(),
                latest.getFrozenAt(), latest.getFrozenBy());
    }

    /**
     * The published rows behind a root.
     *
     * <p>Contains no names, addresses or contact details — only the attributes that decide
     * allocation. Published in full so that anyone can rebuild the tree and check the root.
     */
    @GetMapping(path = "/api/v1/registry/{registryRoot}/candidates",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public List<PublishedCandidate> candidates(@PathVariable String registryRoot) {
        return registry.candidatesOf(registryRoot).stream()
                .map(candidate -> new PublishedCandidate(
                        candidate.getLeafIndex(), candidate.getApplicationNo(),
                        candidate.getLeafHash(), candidate.getCanonicalJson()))
                .toList();
    }

    /** One applicant's proof that their row was among the inputs. */
    @GetMapping(path = "/api/v1/registry/{registryRoot}/proof/{applicationNo}",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public InclusionProofResponse proof(
            @PathVariable String registryRoot,
            @PathVariable String applicationNo) {
        return registry.proofOf(registryRoot, applicationNo);
    }

    /** A frozen row exactly as published: its position, its bytes, and its hash. */
    public record PublishedCandidate(
            int leafIndex, String applicationNo, String leafHash, String canonicalJson) {
    }
}
