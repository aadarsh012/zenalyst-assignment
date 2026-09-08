package com.zenalyst.housing.allocation;

import com.zenalyst.housing.intake.Category;
import com.zenalyst.housing.intake.Gender;

/**
 * One candidate, as the allocator sees them.
 *
 * <p>These are the frozen registry's rows and nothing more: no name, no address, no contact
 * details, no identity token. The allocator cannot favour or disfavour anyone on any basis it
 * cannot see, and it can see only what the published register contains.
 *
 * <p>Every attribute here is already <em>effective</em> rather than claimed — eligibility resolved
 * unverified certificates before the freeze. The allocator does not know what anyone declared, only
 * what was established.
 */
public record AllocationCandidate(
        String applicationNo,
        Category category,
        Gender gender,
        boolean disability,
        boolean exServiceperson,
        boolean localResident,
        boolean eligible) {

    public boolean satisfies(HorizontalCategory horizontal) {
        return switch (horizontal) {
            case WOMEN -> gender == Gender.FEMALE;
            case PWD -> disability;
            case EX_SERVICE -> exServiceperson;
            case LOCAL_RESIDENT -> localResident;
        };
    }
}
