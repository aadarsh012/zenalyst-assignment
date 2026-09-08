package com.zenalyst.housing.draw;

import java.util.UUID;

/**
 * Answers whether replacing a published draw has been authorised.
 *
 * <p>A port, declared here and implemented in {@code objection}, and the reason is a dependency
 * cycle that is easy to create and unpleasant to live with. Draws need to know whether a
 * supersession was authorised; objections need to know which draw they concern. Wiring both
 * services to each other makes the two packages inseparable — neither can be read, tested or
 * changed without the other.
 *
 * <p>Declaring the question here and letting {@code objection} answer it keeps the dependency
 * pointing one way. {@code ArchitectureTest} enforces that there are no cycles, which is how the
 * problem was found rather than discovered later by whoever tried to untangle it.
 */
public interface SupersessionAuthority {

    /**
     * @return true where a challenge against this draw has been upheld in writing. An authority
     *         able to re-draw at will could draw repeatedly until it liked the answer, and the
     *         commitment ceremony would not catch it — every individual draw would verify perfectly.
     */
    boolean isSupersessionAuthorised(UUID drawId);
}
