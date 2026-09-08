package com.zenalyst.housing.rules;

import com.zenalyst.housing.allocation.HorizontalCategory;
import com.zenalyst.housing.allocation.SeatPool;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A proposed quota matrix.
 *
 * <p>The order of {@code horizontalReservations} is significant and is preserved into the published
 * document. Shortfalls are topped up in sequence, and a different sequence can select different
 * people, so the order is part of the rules rather than an artefact of how they were typed.
 */
public record CreateRuleVersionRequest(
        @NotBlank String version,
        @NotEmpty Map<SeatPool, Integer> seats,
        @NotNull List<HorizontalReservationRequest> horizontalReservations) {

    public record HorizontalReservationRequest(
            @NotNull HorizontalCategory category,
            @NotNull BigDecimal share) {
    }
}
