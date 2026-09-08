package com.zenalyst.housing.objection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A challenge, as filed.
 *
 * @param applicationNo the application it concerns, or null for an objection about the conduct of
 *                      the draw itself — which anybody may raise
 * @param statement     what the objector says went wrong. Required and substantive: an objection
 *                      with no statement cannot be adjudicated, and recording one would create the
 *                      appearance of a process without the substance of one.
 */
public record FileObjectionRequest(
        String applicationNo,
        @NotNull ObjectionGround ground,
        @NotBlank @Size(min = 20, max = 4000) String statement,
        String supportingReference) {
}
