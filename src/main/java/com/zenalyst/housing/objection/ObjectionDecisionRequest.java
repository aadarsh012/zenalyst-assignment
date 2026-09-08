package com.zenalyst.housing.objection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An adjudicator's decision.
 *
 * @param reason why. Required either way — a rejection without a reason is not an answer, and an
 *               acceptance without one leaves the correction unexplained.
 * @param remedy what upholding it requires. Only meaningful for {@code UPHELD}.
 */
public record ObjectionDecisionRequest(
        @NotNull ObjectionStatus outcome,
        @NotBlank @Size(min = 10, max = 4000) String reason,
        String remedy) {
}
