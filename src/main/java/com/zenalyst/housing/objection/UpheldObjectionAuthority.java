package com.zenalyst.housing.objection;

import com.zenalyst.housing.draw.SupersessionAuthority;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Supersession is authorised by an upheld objection, and by nothing else.
 *
 * <p>An authority that discovers its own error files its own objection and upholds it. That is not
 * a loophole — it is the paper trail working as intended: the finding, its reason and its remedy all
 * end up in writing and in the audit chain, exactly as they would for a challenge from outside.
 */
@Component
class UpheldObjectionAuthority implements SupersessionAuthority {

    private final ObjectionRepository objections;

    UpheldObjectionAuthority(ObjectionRepository objections) {
        this.objections = objections;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isSupersessionAuthorised(UUID drawId) {
        return objections.countByDrawIdAndStatus(drawId, ObjectionStatus.UPHELD) > 0;
    }
}
