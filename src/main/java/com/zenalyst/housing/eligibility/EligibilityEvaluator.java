package com.zenalyst.housing.eligibility;

import com.zenalyst.housing.intake.Category;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Decides whether an application competes, and on what terms.
 *
 * <h2>Two kinds of rule</h2>
 *
 * <p><strong>Disqualifying rules</strong> remove an applicant from the draw: being under age, or
 * being a duplicate of somebody else's application. There are deliberately few of them, and each
 * rests on something objective.
 *
 * <p><strong>Claim rules</strong> decide which pool an applicant competes in. Failing one is not a
 * disqualification: an unverified category certificate means competing on open merit, and an
 * unverified residence proof means no local preference. The applicant still competes.
 *
 * <p>That asymmetry is the substance of this class. The obvious design — treat an unverified claim
 * as a failed claim and disqualify — would throw out applicants whose only fault was that their
 * certificate had not reached the top of an official's pile. The scheme would then be allocating
 * flats partly on the basis of its own administrative backlog.
 *
 * <p>Pure by construction: no clock, no database, no Spring. The reference date arrives in the
 * rules so that every applicant is judged at the same instant regardless of when the check runs.
 */
public final class EligibilityEvaluator {

    private EligibilityEvaluator() {
    }

    public static EligibilityDecision evaluate(Candidate candidate, EligibilityRules rules) {
        List<EligibilityDecision.Check> checks = new ArrayList<>();

        boolean eligible = true;
        eligible &= checkNotADuplicate(candidate, checks);
        eligible &= checkAge(candidate, rules, checks);

        Category effectiveCategory = resolveCategory(candidate, rules, checks);
        boolean localResident = resolveLocalResidence(candidate, checks);
        boolean disability = resolveHorizontalClaim(
                candidate, ClaimType.DISABILITY, candidate.claimsDisability(),
                "DISABILITY_CLAIM", "disability", checks);
        boolean exService = resolveHorizontalClaim(
                candidate, ClaimType.EX_SERVICE, candidate.claimsExServiceperson(),
                "EX_SERVICE_CLAIM", "ex-servicepersons'", checks);

        return new EligibilityDecision(
                eligible, effectiveCategory, localResident, disability, exService, checks);
    }

    private static boolean checkNotADuplicate(Candidate candidate, List<EligibilityDecision.Check> checks) {
        if (candidate.duplicateOf() != null) {
            checks.add(EligibilityDecision.Check.disqualify("DUPLICATE_APPLICATION",
                    "Another submission by the same person (%s) stands in the draw. This one does not compete."
                            .formatted(candidate.duplicateOf())));
            return false;
        }
        checks.add(EligibilityDecision.Check.pass("DUPLICATE_APPLICATION",
                "No other submission by this person was found."));
        return true;
    }

    private static boolean checkAge(
            Candidate candidate, EligibilityRules rules, List<EligibilityDecision.Check> checks) {

        int age = Period.between(candidate.dateOfBirth(), rules.ageReferenceDate()).getYears();
        if (age < rules.minimumAge()) {
            checks.add(EligibilityDecision.Check.disqualify("MINIMUM_AGE",
                    "Aged %d on %s; the scheme requires %d."
                            .formatted(age, rules.ageReferenceDate(), rules.minimumAge())));
            return false;
        }
        checks.add(EligibilityDecision.Check.pass("MINIMUM_AGE",
                "Aged %d on %s.".formatted(age, rules.ageReferenceDate())));
        return true;
    }

    /**
     * Resolves the category actually competed in.
     *
     * <p>An EWS claim needs two things: a verified income certificate, and a declared income within
     * the limit. Either missing, and the applicant competes as GEN.
     */
    private static Category resolveCategory(
            Candidate candidate, EligibilityRules rules, List<EligibilityDecision.Check> checks) {

        if (candidate.claimedCategory() == Category.GEN) {
            checks.add(EligibilityDecision.Check.pass("CATEGORY_CLAIM",
                    "Competing in the general category; no certificate required."));
            return Category.GEN;
        }

        ClaimOutcome categoryClaim = candidate.outcomeOf(ClaimType.CATEGORY);
        if (categoryClaim != ClaimOutcome.VERIFIED) {
            checks.add(categoryCheckFor(categoryClaim, candidate.claimedCategory()));
            return Category.GEN;
        }
        checks.add(EligibilityDecision.Check.pass("CATEGORY_CLAIM",
                "%s certificate verified.".formatted(candidate.claimedCategory())));

        if (candidate.claimedCategory() != Category.EWS) {
            return candidate.claimedCategory();
        }
        return resolveEwsIncome(candidate, rules, checks);
    }

    private static Category resolveEwsIncome(
            Candidate candidate, EligibilityRules rules, List<EligibilityDecision.Check> checks) {

        BigDecimal income = candidate.annualIncome();
        if (income == null) {
            checks.add(EligibilityDecision.Check.fail("EWS_INCOME",
                    "No income was declared, so the EWS claim cannot stand. Competing in the general category."));
            return Category.GEN;
        }
        if (income.compareTo(rules.ewsAnnualIncomeLimit()) > 0) {
            checks.add(EligibilityDecision.Check.fail("EWS_INCOME",
                    "Declared income %s exceeds the EWS limit of %s. Competing in the general category."
                            .formatted(income.toPlainString(), rules.ewsAnnualIncomeLimit().toPlainString())));
            return Category.GEN;
        }
        if (candidate.outcomeOf(ClaimType.INCOME) != ClaimOutcome.VERIFIED) {
            checks.add(EligibilityDecision.Check.notVerified("EWS_INCOME",
                    "Income certificate not yet verified. Competing in the general category until it is."));
            return Category.GEN;
        }
        checks.add(EligibilityDecision.Check.pass("EWS_INCOME",
                "Declared income %s is within the EWS limit of %s, and verified."
                        .formatted(income.toPlainString(), rules.ewsAnnualIncomeLimit().toPlainString())));
        return Category.EWS;
    }

    private static boolean resolveLocalResidence(Candidate candidate, List<EligibilityDecision.Check> checks) {
        if (!candidate.claimsLocalResidence()) {
            checks.add(EligibilityDecision.Check.pass("LOCAL_RESIDENCE_CLAIM",
                    "No local-residence preference claimed."));
            return false;
        }
        return switch (candidate.outcomeOf(ClaimType.LOCAL_RESIDENCE)) {
            case VERIFIED -> {
                checks.add(EligibilityDecision.Check.pass("LOCAL_RESIDENCE_CLAIM",
                        "Proof of residence verified; local preference applies."));
                yield true;
            }
            case REJECTED -> {
                checks.add(EligibilityDecision.Check.fail("LOCAL_RESIDENCE_CLAIM",
                        "Proof of residence was examined and refused; local preference does not apply."));
                yield false;
            }
            case UNVERIFIED -> {
                checks.add(EligibilityDecision.Check.notVerified("LOCAL_RESIDENCE_CLAIM",
                        "Proof of residence not yet verified; local preference does not apply until it is."));
                yield false;
            }
        };
    }

    private static boolean resolveHorizontalClaim(
            Candidate candidate, ClaimType claim, boolean claimed,
            String code, String label, List<EligibilityDecision.Check> checks) {

        if (!claimed) {
            checks.add(EligibilityDecision.Check.pass(code, "No %s reservation claimed.".formatted(label)));
            return false;
        }
        return switch (candidate.outcomeOf(claim)) {
            case VERIFIED -> {
                checks.add(EligibilityDecision.Check.pass(code,
                        "Certificate verified; the %s reservation applies.".formatted(label)));
                yield true;
            }
            case REJECTED -> {
                checks.add(EligibilityDecision.Check.fail(code,
                        "Certificate examined and refused; the %s reservation does not apply.".formatted(label)));
                yield false;
            }
            case UNVERIFIED -> {
                checks.add(EligibilityDecision.Check.notVerified(code,
                        "Certificate not yet verified; the %s reservation does not apply until it is."
                                .formatted(label)));
                yield false;
            }
        };
    }

    private static EligibilityDecision.Check categoryCheckFor(ClaimOutcome outcome, Category claimed) {
        return outcome == ClaimOutcome.REJECTED
                ? EligibilityDecision.Check.fail("CATEGORY_CLAIM",
                        "%s certificate was examined and refused. Competing in the general category."
                                .formatted(claimed))
                : EligibilityDecision.Check.notVerified("CATEGORY_CLAIM",
                        "%s certificate not yet verified. Competing in the general category until it is."
                                .formatted(claimed));
    }

    /** Where a claim has got to. */
    public enum ClaimOutcome {
        VERIFIED, REJECTED, UNVERIFIED
    }

    /**
     * Everything the evaluator is allowed to see.
     *
     * <p>Deliberately not the {@code Application} entity. Passing a JPA entity in would let a rule
     * quietly reach into a lazily-loaded association and make eligibility depend on database state
     * that is not part of the frozen snapshot.
     *
     * @param duplicateOf the application number that supersedes this one, or {@code null}
     */
    public record Candidate(
            String applicationNo,
            LocalDate dateOfBirth,
            Category claimedCategory,
            boolean claimsLocalResidence,
            boolean claimsDisability,
            boolean claimsExServiceperson,
            BigDecimal annualIncome,
            String duplicateOf,
            Map<ClaimType, ClaimOutcome> claims) {

        public Candidate {
            claims = Map.copyOf(claims);
        }

        public ClaimOutcome outcomeOf(ClaimType claim) {
            return claims.getOrDefault(claim, ClaimOutcome.UNVERIFIED);
        }

        /** Claims this applicant has made that a verifier would need to look at. */
        public Set<ClaimType> claimsRequiringVerification() {
            Set<ClaimType> required = new java.util.LinkedHashSet<>();
            if (claimedCategory != Category.GEN) {
                required.add(ClaimType.CATEGORY);
            }
            if (claimedCategory == Category.EWS) {
                required.add(ClaimType.INCOME);
            }
            if (claimsLocalResidence) {
                required.add(ClaimType.LOCAL_RESIDENCE);
            }
            if (claimsDisability) {
                required.add(ClaimType.DISABILITY);
            }
            if (claimsExServiceperson) {
                required.add(ClaimType.EX_SERVICE);
            }
            return required;
        }
    }
}
