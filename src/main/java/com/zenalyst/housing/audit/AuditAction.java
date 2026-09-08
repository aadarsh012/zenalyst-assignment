package com.zenalyst.housing.audit;

/**
 * The catalogue of acts worth recording.
 *
 * <p>An enum rather than free text, because the audit log is queried and reasoned about, and a
 * log containing both {@code APPLICATION_RECEIVED} and {@code application received} is a log
 * nobody can answer questions from. Names are permanent: they appear in stored events that can
 * never be rewritten.
 *
 * <p>Only acts that change what the system will decide belong here. A malformed submission that
 * was rejected at the door, or a retried request that replayed an earlier response, changed
 * nothing about anyone's chances and is not recorded — filling the chain with those would bury
 * the events a reviewer actually needs to read.
 */
public enum AuditAction {

    /** An application was accepted into the system and now competes for a flat. */
    APPLICATION_RECEIVED,

    /** A batch of paper applications was imported; the payload carries the per-row tally. */
    APPLICATION_BATCH_IMPORTED,

    /** A deduplication pass completed; the payload carries what it concluded. */
    DEDUPLICATION_COMPLETED,

    /** An operator examined a document and recorded what they concluded about a declared claim. */
    CLAIM_VERIFIED,

    /** The candidate register was frozen; the payload carries the published root. */
    REGISTRY_FROZEN,

    /**
     * An operator judged a fuzzy match. Recorded per decision rather than per pass, because this
     * is a person deciding something about another person's application and the chain should name
     * who, when, and which way.
     */
    DUPLICATE_REVIEW_DECIDED
}
