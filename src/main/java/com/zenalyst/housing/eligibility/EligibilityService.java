package com.zenalyst.housing.eligibility;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.audit.AuditAction;
import com.zenalyst.housing.audit.AuditWriter;
import com.zenalyst.housing.identity.DuplicateLink;
import com.zenalyst.housing.identity.DuplicateLinkRepository;
import com.zenalyst.housing.intake.Application;
import com.zenalyst.housing.intake.ApplicationRepository;
import com.zenalyst.housing.platform.error.ApiException;
import com.zenalyst.housing.scheme.Scheme;
import com.zenalyst.housing.scheme.SchemeRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the eligibility rules to real applications, and records operators' verification
 * decisions.
 *
 * <p>The rules themselves live in {@link EligibilityEvaluator}, which knows nothing about the
 * database. This class exists to assemble its inputs and to write down what humans decide.
 */
@Service
public class EligibilityService {

    private final ApplicationRepository applications;
    private final SchemeRepository schemes;
    private final DuplicateLinkRepository duplicateLinks;
    private final ClaimVerificationRepository verifications;
    private final AuditWriter audit;
    private final Clock clock;
    private final int minimumAge;
    private final java.math.BigDecimal ewsIncomeLimit;

    public EligibilityService(
            ApplicationRepository applications,
            SchemeRepository schemes,
            DuplicateLinkRepository duplicateLinks,
            ClaimVerificationRepository verifications,
            AuditWriter audit,
            Clock clock,
            @Value("${housing.eligibility.minimum-age}") int minimumAge,
            @Value("${housing.eligibility.ews-annual-income-limit}") java.math.BigDecimal ewsIncomeLimit) {
        this.applications = applications;
        this.schemes = schemes;
        this.duplicateLinks = duplicateLinks;
        this.verifications = verifications;
        this.audit = audit;
        this.clock = clock;
        this.minimumAge = minimumAge;
        this.ewsIncomeLimit = ewsIncomeLimit;
    }

    /**
     * The rules in force for a scheme.
     *
     * <p>Age is measured at the scheme's closing date, so that every applicant is judged at the
     * same instant no matter when the evaluation runs.
     */
    public EligibilityRules rulesFor(Scheme scheme) {
        return new EligibilityRules(
                minimumAge,
                ewsIncomeLimit,
                LocalDate.ofInstant(scheme.getApplicationsCloseAt(), ZoneOffset.UTC));
    }

    /** Evaluates every application in the scheme, in application-number order. */
    @Transactional(readOnly = true)
    public List<Assessment> assessAll(Scheme scheme) {
        EligibilityRules rules = rulesFor(scheme);

        Map<UUID, String> supersededBy = new HashMap<>();
        for (DuplicateLink link : duplicateLinks.findAll()) {
            if (link.getSchemeId().equals(scheme.getId())) {
                supersededBy.put(link.getDuplicateApplicationId(),
                        applications.findById(link.getCanonicalApplicationId())
                                .map(Application::getApplicationNo).orElse("another application"));
            }
        }

        Map<UUID, Map<ClaimType, EligibilityEvaluator.ClaimOutcome>> claims = new HashMap<>();
        for (ClaimVerification verification : verifications.findBySchemeId(scheme.getId())) {
            claims.computeIfAbsent(verification.getApplicationId(), key -> new EnumMap<>(ClaimType.class))
                    .put(verification.getClaim(),
                            verification.getOutcome() == ClaimVerification.Outcome.VERIFIED
                                    ? EligibilityEvaluator.ClaimOutcome.VERIFIED
                                    : EligibilityEvaluator.ClaimOutcome.REJECTED);
        }

        return applications.findAll().stream()
                .filter(application -> application.getSchemeId().equals(scheme.getId()))
                .sorted((a, b) -> a.getApplicationNo().compareTo(b.getApplicationNo()))
                .map(application -> {
                    EligibilityEvaluator.Candidate candidate = toCandidate(
                            application, supersededBy.get(application.getId()),
                            claims.getOrDefault(application.getId(), Map.of()));
                    return new Assessment(application, candidate,
                            EligibilityEvaluator.evaluate(candidate, rules));
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public Assessment assess(String applicationNo) {
        Application application = applications.findByApplicationNo(applicationNo)
                .orElseThrow(() -> ApiException.notFound("application", applicationNo));
        Scheme scheme = schemes.findById(application.getSchemeId())
                .orElseThrow(() -> ApiException.notFound("scheme", application.getSchemeId().toString()));

        return assessAll(scheme).stream()
                .filter(assessment -> assessment.application().getApplicationNo().equals(applicationNo))
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("application", applicationNo));
    }

    @Transactional
    public Assessment verify(String applicationNo, VerifyClaimRequest request, String verifiedBy) {
        Application application = applications.findByApplicationNo(applicationNo)
                .orElseThrow(() -> ApiException.notFound("application", applicationNo));

        verifications.findByApplicationIdAndClaim(application.getId(), request.claim())
                .ifPresent(existing -> {
                    throw ApiException.conflict(
                            "The %s claim on %s was already recorded as %s by %s."
                                    .formatted(request.claim(), applicationNo,
                                            existing.getOutcome(), existing.getVerifiedBy()),
                            Map.of("applicationNo", applicationNo,
                                    "claim", request.claim().name(),
                                    "existingOutcome", existing.getOutcome().name()));
                });

        Instant now = clock.instant();
        verifications.saveAndFlush(ClaimVerification.of(
                application.getId(), request.claim(), request.outcome(),
                request.evidenceReference(), request.note(), verifiedBy, now));

        audit.append(verifiedBy, AuditAction.CLAIM_VERIFIED, "application", applicationNo,
                verificationPayload(applicationNo, request));

        return assess(applicationNo);
    }

    private EligibilityEvaluator.Candidate toCandidate(
            Application application, String supersededBy,
            Map<ClaimType, EligibilityEvaluator.ClaimOutcome> claims) {

        return new EligibilityEvaluator.Candidate(
                application.getApplicationNo(),
                application.getDateOfBirth(),
                application.getCategory(),
                application.isLocalResident(),
                application.hasDisability(),
                application.isExServiceperson(),
                application.getAnnualIncome(),
                supersededBy,
                claims);
    }

    private ObjectNode verificationPayload(String applicationNo, VerifyClaimRequest request) {
        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("applicationNo", applicationNo);
        payload.put("claim", request.claim().name());
        payload.put("outcome", request.outcome().name());
        if (request.evidenceReference() != null) {
            payload.put("evidenceReference", request.evidenceReference());
        }
        if (request.note() != null) {
            payload.put("note", request.note());
        }
        return payload;
    }

    /** An application together with what the rules concluded about it. */
    public record Assessment(
            Application application,
            EligibilityEvaluator.Candidate candidate,
            EligibilityDecision decision) {
    }
}
