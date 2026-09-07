package com.zenalyst.housing.intake;

/**
 * The HTTP result of an intake attempt, already serialised.
 *
 * <p>The body is a string rather than an object because a replayed request must return the
 * <em>original</em> response byte for byte. Re-serialising the current state would risk telling
 * a retrying client something subtly different from what the first attempt told them, which
 * defeats the purpose of having an idempotency key at all.
 */
public record IntakeOutcome(int status, String body, boolean replayed) {
}
