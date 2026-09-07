package com.zenalyst.housing.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * One link in the audit chain, as written.
 *
 * @param seq         position in the chain, assigned by the database
 * @param eventId     distinguishes two otherwise identical acts at the same instant
 * @param occurredAt  when the act happened, to microsecond precision
 * @param actor       who did it — an operator id, or {@code system} / {@code public}
 * @param action      what was done
 * @param subjectType the kind of thing it was done to
 * @param subjectId   which one
 * @param payload     canonical JSON of the act's details; this exact string is what was hashed
 * @param prevHash    hash of the preceding event, or 64 zeroes for the first
 * @param hash        this event's hash
 */
public record AuditEvent(
        long seq,
        UUID eventId,
        Instant occurredAt,
        String actor,
        AuditAction action,
        String subjectType,
        String subjectId,
        String payload,
        String prevHash,
        String hash) {
}
