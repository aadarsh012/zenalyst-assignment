package com.zenalyst.housing.eligibility;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.platform.hash.CanonicalJson;
import com.zenalyst.housing.platform.hash.Hashing;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The published thresholds an application is judged against.
 *
 * <p>Two properties matter more than the numbers themselves.
 *
 * <p><strong>Everyone is judged at the same instant.</strong> {@code ageReferenceDate} is the
 * scheme's closing date, not the date the check happens to run. Otherwise an applicant's
 * eligibility would depend on when an operator pressed a button, and someone who turned eighteen
 * in the interval would be eligible or not according to processing order.
 *
 * <p><strong>The rules are hashable.</strong> {@link #hash()} goes into every frozen registry, so
 * a published result carries proof of which thresholds produced it. "You raised the income limit
 * afterwards" stops being an unanswerable accusation.
 *
 * <p>These live in configuration for now. Phase 4 replaces them with versioned rule documents that
 * carry the seat inventory and quota matrix as well; the hashing contract here is what that will
 * extend.
 */
public record EligibilityRules(
        int minimumAge,
        BigDecimal ewsAnnualIncomeLimit,
        LocalDate ageReferenceDate) {

    /** Canonical JSON of the rules — the exact bytes that get hashed and published. */
    public String document() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("ageReferenceDate", ageReferenceDate.toString());
        node.put("ewsAnnualIncomeLimit", ewsAnnualIncomeLimit);
        node.put("minimumAge", minimumAge);
        return CanonicalJson.render(node);
    }

    public String hash() {
        return Hashing.sha256Hex(document());
    }
}
