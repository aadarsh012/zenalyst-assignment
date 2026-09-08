package com.zenalyst.housing.allocation;

/**
 * Reservations that cut <em>across</em> the vertical categories rather than competing with them.
 *
 * <p>A woman with a disability applying under SC is not in three categories. She is in one
 * vertical category — SC — and counts toward two horizontal reservations within whichever pool she
 * is selected in. Vertical categories are exclusive buckets; horizontal reservations are
 * percentages carved out inside each bucket.
 *
 * <p>Local residence is modelled here too. Mechanically it behaves exactly like the others — a
 * share of each pool set aside for candidates with an attribute — and giving it its own machinery
 * would mean two implementations of one idea.
 */
public enum HorizontalCategory {

    WOMEN,
    /** Persons with disabilities. */
    PWD,
    EX_SERVICE,
    /** Existing residents of the area, for whom the scheme expresses a preference. */
    LOCAL_RESIDENT
}
