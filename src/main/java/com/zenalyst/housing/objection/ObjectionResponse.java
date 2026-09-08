package com.zenalyst.housing.objection;

import java.time.Instant;
import java.util.UUID;

public record ObjectionResponse(
        String objectionId,
        String schemeCode,
        String drawId,
        String applicationNo,
        ObjectionGround ground,
        String statement,
        String supportingReference,
        String filedBy,
        Instant filedAt,
        ObjectionStatus status,
        Instant decidedAt,
        String decidedBy,
        String decisionReason,
        String remedy,
        String whatHappensNext) {

    static ObjectionResponse of(Objection objection, String schemeCode) {
        return new ObjectionResponse(
                objection.getId().toString(), schemeCode,
                objection.getDrawId() == null ? null : objection.getDrawId().toString(),
                objection.getApplicationNo(), objection.getGround(), objection.getStatement(),
                objection.getSupportingReference(), objection.getFiledBy(), objection.getFiledAt(),
                objection.getStatus(), objection.getDecidedAt(), objection.getDecidedBy(),
                objection.getDecisionReason(), objection.getRemedy(),
                nextStep(objection));
    }

    /** Written for the objector, who is owed a straight answer about what follows. */
    private static String nextStep(Objection objection) {
        return switch (objection.getStatus()) {
            case OPEN -> "This objection is open and awaiting a decision.";
            case UPHELD -> "This objection was upheld. The published draw has not been altered — it "
                    + "cannot be. The record will be corrected and a new draw held that supersedes "
                    + "it; the original remains published and verifiable so that the two can be "
                    + "compared.";
            case REJECTED -> "This objection was considered and refused. The reason is recorded "
                    + "above and the published result stands.";
            case WITHDRAWN -> "This objection was withdrawn.";
        };
    }

    static UUID parse(String id) {
        return UUID.fromString(id);
    }
}
