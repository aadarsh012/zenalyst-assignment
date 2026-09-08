package com.zenalyst.housing.eligibility;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records one verification in its own transaction.
 *
 * <p>A separate bean because {@link Propagation#REQUIRES_NEW} only takes effect across a proxy
 * boundary — and because the boundary is the point. Each row of a batch commits or rolls back
 * alone, so one bad row among several thousand does not discard the rest.
 *
 * <p>Calling {@code EligibilityService.verify} directly from the batch loop was the original
 * implementation and was wrong in a way worth recording: self-invocation bypasses the proxy, so no
 * transaction started, the verification row committed on its own, and the audit append then failed
 * because {@code AuditWriter} demands an existing transaction. Verifications were being written
 * without the events that record them. The {@code MANDATORY} propagation on the audit writer is
 * what turned that into a loud failure rather than a silent hole in the trail.
 */
@Component
public class ClaimVerificationRecorder {

    private final EligibilityService eligibility;

    public ClaimVerificationRecorder(EligibilityService eligibility) {
        this.eligibility = eligibility;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String applicationNo, VerifyClaimRequest request, String verifiedBy) {
        eligibility.verify(applicationNo, request, verifiedBy);
    }
}
