package com.zenalyst.housing.transparency;

/**
 * The result of walking the audit chain.
 *
 * @param firstBreakAtSeq the sequence number of the first event that failed, or {@code null} if
 *                        none did. Naming the point of failure matters: an auditor told only "the
 *                        chain is broken" has to find it themselves, and the whole value of the
 *                        chain is that it says exactly where.
 * @param breakKind       what was wrong at that point, in words
 */
public record AuditChainReport(
        boolean verified,
        long eventsChecked,
        Long firstBreakAtSeq,
        String breakKind,
        String detail,
        String headHash) {

    static AuditChainReport intact(long events, String headHash) {
        return new AuditChainReport(true, events, null, null,
                "Every event's stored hash matches its contents, and every event links to its "
                        + "predecessor. Nothing has been altered or removed.",
                headHash);
    }

    static AuditChainReport broken(long events, long seq, String kind, String detail) {
        return new AuditChainReport(false, events, seq, kind, detail, null);
    }
}
