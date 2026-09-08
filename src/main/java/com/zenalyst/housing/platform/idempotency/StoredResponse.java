package com.zenalyst.housing.platform.idempotency;

/** A previously returned response, replayed verbatim for a repeated idempotency key. */
public record StoredResponse(int status, String body) {
}
