package com.zenalyst.housing.eligibility;

import static org.assertj.core.api.Assertions.assertThat;

import com.zenalyst.housing.eligibility.EligibilityEvaluator.ClaimOutcome;
import com.zenalyst.housing.intake.Category;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class EligibilityEvaluatorTest {

    private static final LocalDate CLOSING_DATE = LocalDate.of(2026, 12, 31);

    private static final EligibilityRules RULES =
            new EligibilityRules(18, new BigDecimal("300000"), CLOSING_DATE);

    private static Map<ClaimType, ClaimOutcome> verified(ClaimType... claims) {
        Map<ClaimType, ClaimOutcome> map = new EnumMap<>(ClaimType.class);
        for (ClaimType claim : claims) {
            map.put(claim, ClaimOutcome.VERIFIED);
        }
        return map;
    }

    private static EligibilityEvaluator.Candidate candidate(
            LocalDate dateOfBirth, Category category, boolean local, boolean disability,
            boolean exService, BigDecimal income, String duplicateOf,
            Map<ClaimType, ClaimOutcome> claims) {
        return new EligibilityEvaluator.Candidate(
                "A-000001", dateOfBirth, category, local, disability, exService,
                income, duplicateOf, claims);
    }

    private static EligibilityEvaluator.Candidate adult(Category category, Map<ClaimType, ClaimOutcome> claims) {
        return candidate(LocalDate.of(1990, 1, 1), category, false, false, false,
                new BigDecimal("250000"), null, claims);
    }

    private static String detailOf(EligibilityDecision decision, String code) {
        return decision.checks().stream()
                .filter(check -> check.code().equals(code))
                .findFirst().orElseThrow().detail();
    }

    private static EligibilityDecision.Outcome outcomeOf(EligibilityDecision decision, String code) {
        return decision.checks().stream()
                .filter(check -> check.code().equals(code))
                .findFirst().orElseThrow().outcome();
    }

    @Nested
    @DisplayName("disqualifying rules")
    class Disqualifying {

        @Test
        @DisplayName("an applicant under the minimum age does not compete")
        void underAgeIsDisqualified() {
            EligibilityDecision decision = EligibilityEvaluator.evaluate(
                    candidate(LocalDate.of(2010, 1, 1), Category.GEN, false, false, false,
                            null, null, Map.of()),
                    RULES);

            assertThat(decision.eligible()).isFalse();
            assertThat(decision.disqualifyingFailures())
                    .extracting(EligibilityDecision.Check::code)
                    .containsExactly("MINIMUM_AGE");
        }

        @Test
        @DisplayName("age is measured at the closing date, not at the moment of evaluation")
        void ageIsMeasuredAtTheClosingDate() {
            // Someone who turns 18 in November 2026: eligible under a scheme closing in December,
            // not under one that closed in October. Fixing the reference date is what stops
            // eligibility depending on when an operator happened to press a button.
            EligibilityEvaluator.Candidate turnsEighteenInNovember = candidate(
                    LocalDate.of(2008, 11, 15), Category.GEN, false, false, false, null, null, Map.of());

            assertThat(EligibilityEvaluator.evaluate(turnsEighteenInNovember, RULES).eligible()).isTrue();

            EligibilityRules closedInOctober =
                    new EligibilityRules(18, new BigDecimal("300000"), LocalDate.of(2026, 10, 1));
            assertThat(EligibilityEvaluator.evaluate(turnsEighteenInNovember, closedInOctober).eligible())
                    .isFalse();
        }

        @Test
        @DisplayName("a duplicate application does not compete, and says which one does")
        void duplicateIsDisqualified() {
            EligibilityDecision decision = EligibilityEvaluator.evaluate(
                    candidate(LocalDate.of(1990, 1, 1), Category.GEN, false, false, false,
                            null, "A-000042", Map.of()),
                    RULES);

            assertThat(decision.eligible()).isFalse();
            assertThat(detailOf(decision, "DUPLICATE_APPLICATION")).contains("A-000042");
        }
    }

    @Nested
    @DisplayName("claim rules never disqualify")
    class Claims {

        @Test
        @DisplayName("an unverified category claim drops the applicant to GEN but keeps them in the draw")
        void unverifiedCategoryFallsBackToGeneral() {
            EligibilityDecision decision = EligibilityEvaluator.evaluate(adult(Category.SC, Map.of()), RULES);

            // The point of the whole design: an applicant whose certificate is sitting in a queue
            // has done nothing wrong, and must not be thrown out of the scheme for it.
            assertThat(decision.eligible()).isTrue();
            assertThat(decision.effectiveCategory()).isEqualTo(Category.GEN);
            assertThat(outcomeOf(decision, "CATEGORY_CLAIM")).isEqualTo(EligibilityDecision.Outcome.NOT_VERIFIED);
        }

        @Test
        @DisplayName("a verified category claim is honoured")
        void verifiedCategoryIsHonoured() {
            EligibilityDecision decision = EligibilityEvaluator.evaluate(
                    adult(Category.SC, verified(ClaimType.CATEGORY)), RULES);

            assertThat(decision.eligible()).isTrue();
            assertThat(decision.effectiveCategory()).isEqualTo(Category.SC);
        }

        @Test
        @DisplayName("a refused category claim is distinguished from an unexamined one")
        void rejectedIsNotTheSameAsUnverified() {
            Map<ClaimType, ClaimOutcome> rejected = new EnumMap<>(ClaimType.class);
            rejected.put(ClaimType.CATEGORY, ClaimOutcome.REJECTED);

            EligibilityDecision decision = EligibilityEvaluator.evaluate(adult(Category.ST, rejected), RULES);

            assertThat(decision.effectiveCategory()).isEqualTo(Category.GEN);
            assertThat(outcomeOf(decision, "CATEGORY_CLAIM")).isEqualTo(EligibilityDecision.Outcome.FAIL);
            assertThat(detailOf(decision, "CATEGORY_CLAIM")).contains("refused");
        }

        @Test
        @DisplayName("the general category needs no certificate")
        void generalCategoryNeedsNothing() {
            EligibilityDecision decision = EligibilityEvaluator.evaluate(adult(Category.GEN, Map.of()), RULES);

            assertThat(decision.effectiveCategory()).isEqualTo(Category.GEN);
            assertThat(outcomeOf(decision, "CATEGORY_CLAIM")).isEqualTo(EligibilityDecision.Outcome.PASS);
        }

        @Test
        @DisplayName("local preference requires verified proof of residence")
        void localPreferenceRequiresVerification() {
            EligibilityEvaluator.Candidate claims = candidate(
                    LocalDate.of(1990, 1, 1), Category.GEN, true, false, false, null, null, Map.of());

            assertThat(EligibilityEvaluator.evaluate(claims, RULES).effectiveLocalResident()).isFalse();

            EligibilityEvaluator.Candidate proven = candidate(
                    LocalDate.of(1990, 1, 1), Category.GEN, true, false, false, null, null,
                    verified(ClaimType.LOCAL_RESIDENCE));

            assertThat(EligibilityEvaluator.evaluate(proven, RULES).effectiveLocalResident()).isTrue();
        }

        @Test
        @DisplayName("horizontal reservations require verified certificates")
        void horizontalClaimsRequireVerification() {
            EligibilityEvaluator.Candidate proven = candidate(
                    LocalDate.of(1990, 1, 1), Category.GEN, false, true, true, null, null,
                    verified(ClaimType.DISABILITY, ClaimType.EX_SERVICE));

            EligibilityDecision decision = EligibilityEvaluator.evaluate(proven, RULES);
            assertThat(decision.effectiveDisability()).isTrue();
            assertThat(decision.effectiveExServiceperson()).isTrue();

            EligibilityEvaluator.Candidate unproven = candidate(
                    LocalDate.of(1990, 1, 1), Category.GEN, false, true, true, null, null, Map.of());

            EligibilityDecision unverified = EligibilityEvaluator.evaluate(unproven, RULES);
            assertThat(unverified.eligible()).isTrue();
            assertThat(unverified.effectiveDisability()).isFalse();
            assertThat(unverified.effectiveExServiceperson()).isFalse();
        }
    }

    @Nested
    @DisplayName("EWS needs both an income within the limit and a verified certificate")
    class Ews {

        private EligibilityEvaluator.Candidate ewsWith(BigDecimal income, Map<ClaimType, ClaimOutcome> claims) {
            return candidate(LocalDate.of(1990, 1, 1), Category.EWS, false, false, false,
                    income, null, claims);
        }

        @Test
        void bothPresentIsHonoured() {
            EligibilityDecision decision = EligibilityEvaluator.evaluate(
                    ewsWith(new BigDecimal("250000"), verified(ClaimType.CATEGORY, ClaimType.INCOME)), RULES);

            assertThat(decision.effectiveCategory()).isEqualTo(Category.EWS);
        }

        @Test
        @DisplayName("income over the limit drops to GEN — it is not a disqualification")
        void incomeOverLimitFallsBackToGeneral() {
            EligibilityDecision decision = EligibilityEvaluator.evaluate(
                    ewsWith(new BigDecimal("400000"), verified(ClaimType.CATEGORY, ClaimType.INCOME)), RULES);

            assertThat(decision.eligible()).isTrue();
            assertThat(decision.effectiveCategory()).isEqualTo(Category.GEN);
            assertThat(detailOf(decision, "EWS_INCOME")).contains("exceeds");
        }

        @Test
        void unverifiedIncomeFallsBackToGeneral() {
            EligibilityDecision decision = EligibilityEvaluator.evaluate(
                    ewsWith(new BigDecimal("250000"), verified(ClaimType.CATEGORY)), RULES);

            assertThat(decision.effectiveCategory()).isEqualTo(Category.GEN);
            assertThat(outcomeOf(decision, "EWS_INCOME")).isEqualTo(EligibilityDecision.Outcome.NOT_VERIFIED);
        }

        @Test
        void noDeclaredIncomeFallsBackToGeneral() {
            EligibilityDecision decision = EligibilityEvaluator.evaluate(
                    ewsWith(null, verified(ClaimType.CATEGORY, ClaimType.INCOME)), RULES);

            assertThat(decision.effectiveCategory()).isEqualTo(Category.GEN);
        }
    }

    @Test
    @DisplayName("every rule applied is reported, whether it passed or not")
    void allChecksAreReported() {
        EligibilityDecision decision = EligibilityEvaluator.evaluate(
                candidate(LocalDate.of(1990, 1, 1), Category.OBC, true, true, true,
                        new BigDecimal("250000"), null, Map.of()),
                RULES);

        assertThat(decision.checks()).extracting(EligibilityDecision.Check::code)
                .containsExactlyInAnyOrder(
                        "DUPLICATE_APPLICATION", "MINIMUM_AGE", "CATEGORY_CLAIM",
                        "LOCAL_RESIDENCE_CLAIM", "DISABILITY_CLAIM", "EX_SERVICE_CLAIM");
    }

    @Test
    @DisplayName("the applicant is told which documents are still outstanding")
    void outstandingClaimsAreEnumerated() {
        EligibilityEvaluator.Candidate candidate = candidate(
                LocalDate.of(1990, 1, 1), Category.EWS, true, true, false,
                new BigDecimal("250000"), null, Map.of());

        assertThat(candidate.claimsRequiringVerification())
                .containsExactlyInAnyOrder(ClaimType.CATEGORY, ClaimType.INCOME,
                        ClaimType.LOCAL_RESIDENCE, ClaimType.DISABILITY);
    }

    @Test
    @DisplayName("the rules hash changes when any threshold changes")
    void rulesHashCoversEveryThreshold() {
        String base = RULES.hash();

        assertThat(new EligibilityRules(21, new BigDecimal("300000"), CLOSING_DATE).hash()).isNotEqualTo(base);
        assertThat(new EligibilityRules(18, new BigDecimal("500000"), CLOSING_DATE).hash()).isNotEqualTo(base);
        assertThat(new EligibilityRules(18, new BigDecimal("300000"), CLOSING_DATE.plusDays(1)).hash())
                .isNotEqualTo(base);
        assertThat(base).matches("^[0-9a-f]{64}$");
    }
}
