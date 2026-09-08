package com.zenalyst.housing.identity;

import jakarta.validation.constraints.NotNull;

/**
 * An operator's judgement on a fuzzy match.
 *
 * @param outcome {@code CONFIRMED_DUPLICATE} or {@code NOT_DUPLICATE}
 * @param note    why. Optional in the type system and expected in practice: this is the sentence
 *                that gets read back if the applicant disputes the decision, and "the operator
 *                thought so" is not an answer anyone can defend.
 */
public record ReviewDecisionRequest(
        @NotNull ReviewStatus outcome,
        String note) {
}
