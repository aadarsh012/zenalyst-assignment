package com.zenalyst.housing.eligibility;

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
public class EligibilityController {

    private final EligibilityService eligibility;

    public EligibilityController(EligibilityService eligibility) {
        this.eligibility = eligibility;
    }

    /**
     * Why this application does or does not compete, and in which pool.
     *
     * <p>Every rule applied is listed, passed or not. An applicant told only "not eligible" has
     * nothing to correct and nothing to appeal against.
     */
    @GetMapping(path = "/api/v1/applications/{applicationNo}/eligibility",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public EligibilityView eligibility(@PathVariable String applicationNo) {
        return EligibilityView.of(eligibility.assess(applicationNo));
    }

    /**
     * Records that an operator has examined a document.
     *
     * <p>One decision per claim, permanently. A second attempt is refused rather than allowed to
     * overwrite the first.
     */
    @PostMapping(path = "/api/v1/applications/{applicationNo}/verifications",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public EligibilityView verify(
            @PathVariable String applicationNo,
            @Valid @RequestBody VerifyClaimRequest request,
            @RequestParam("verifiedBy") @NotBlank String verifiedBy) {
        return EligibilityView.of(eligibility.verify(applicationNo, request, verifiedBy));
    }

    /**
     * The public shape of an eligibility assessment.
     *
     * @param claimsAwaitingVerification what the applicant still needs somebody to look at, so
     *                                   that "why am I in the general category?" has an actionable
     *                                   answer rather than a puzzling one
     */
    public record EligibilityView(
            String applicationNo,
            boolean eligible,
            String declaredCategory,
            String effectiveCategory,
            boolean effectiveLocalResident,
            boolean effectiveDisability,
            boolean effectiveExServiceperson,
            List<EligibilityDecision.Check> checks,
            List<ClaimType> claimsAwaitingVerification) {

        static EligibilityView of(EligibilityService.Assessment assessment) {
            EligibilityDecision decision = assessment.decision();
            List<ClaimType> awaiting = assessment.candidate().claimsRequiringVerification().stream()
                    .filter(claim -> assessment.candidate().outcomeOf(claim)
                            == EligibilityEvaluator.ClaimOutcome.UNVERIFIED)
                    .toList();

            return new EligibilityView(
                    assessment.application().getApplicationNo(),
                    decision.eligible(),
                    assessment.application().getCategory().name(),
                    decision.effectiveCategory().name(),
                    decision.effectiveLocalResident(),
                    decision.effectiveDisability(),
                    decision.effectiveExServiceperson(),
                    decision.checks(),
                    awaiting);
        }
    }
}
