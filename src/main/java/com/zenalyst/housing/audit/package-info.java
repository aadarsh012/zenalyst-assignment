/**
 * The tamper-evident record of everything the system decided.
 *
 * <p>Each event stores the hash of its predecessor, so altering or removing any one of them
 * invalidates every hash after it. {@code UPDATE}, {@code DELETE} and {@code TRUNCATE} are refused
 * by a database trigger. Corrections are new compensating events; history is never edited.
 *
 * <p>{@link com.zenalyst.housing.audit.AuditHash} is deliberately small enough to reimplement in
 * any language from its own documentation — build a JSON object of the event's fields,
 * canonicalise it, take its SHA-256 — because the point of the chain is that somebody who does not
 * trust us can recompute it.
 *
 * <p>Appends require an existing transaction. An audit event is not a log line written near a
 * change; it is part of the change, and the two commit together or not at all.
 */
package com.zenalyst.housing.audit;
