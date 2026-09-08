package com.zenalyst.housing.eligibility;

import com.zenalyst.housing.intake.Category;
import java.util.List;

/**
 * What the system concluded about one application, and why.
 *
 * <p>Never a bare boolean. An applicant told only "you were not eligible" has nothing to check,
 * nothing to correct, and nothing to appeal against; the list of checks is the difference between
 * a decision and an edict.
 *
 * <p>Note the separation between {@code eligible} and the effective attributes. Failing a claim
 * does not remove you from the draw — it moves you to the pool you can actually prove you belong
 * in. Only the disqualifying checks decide {@code eligible}.
 *
 * @param effectiveCategory        the category actually competed in: the claim if verified, else GEN
 * @param checks                   every rule applied, passed or not, in a stable order
 */
public record EligibilityDecision(
        boolean eligible,
        Category effectiveCategory,
        boolean effectiveLocalResident,
        boolean effectiveDisability,
        boolean effectiveExServiceperson,
        List<Check> checks) {

    public EligibilityDecision {
        checks = List.copyOf(checks);
    }

    /** The checks that, having failed, kept this application out of the draw entirely. */
    public List<Check> disqualifyingFailures() {
        return checks.stream()
                .filter(check -> check.outcome() == Outcome.FAIL && check.disqualifying())
                .toList();
    }

    /**
     * One rule, applied.
     *
     * @param code         stable and machine-readable; safe to branch on and safe to quote
     * @param detail       plain English, because this is what the applicant reads
     * @param disqualifying whether failing it removes the applicant from the draw, as opposed to
     *                      merely costing them the benefit they claimed
     */
    public record Check(String code, Outcome outcome, boolean disqualifying, String detail) {

        public static Check pass(String code, String detail) {
            return new Check(code, Outcome.PASS, false, detail);
        }

        public static Check fail(String code, String detail) {
            return new Check(code, Outcome.FAIL, false, detail);
        }

        public static Check disqualify(String code, String detail) {
            return new Check(code, Outcome.FAIL, true, detail);
        }

        public static Check notVerified(String code, String detail) {
            return new Check(code, Outcome.NOT_VERIFIED, false, detail);
        }
    }

    public enum Outcome {
        PASS,
        FAIL,
        /**
         * The claim was neither confirmed nor refused — nobody has looked at the document yet.
         * Distinct from FAIL on purpose: an applicant whose certificate is sitting in a queue has
         * done nothing wrong, and the record should not say they did.
         */
        NOT_VERIFIED
    }
}
