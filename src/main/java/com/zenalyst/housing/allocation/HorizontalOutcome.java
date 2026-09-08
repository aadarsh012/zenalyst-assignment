package com.zenalyst.housing.allocation;

/**
 * How one horizontal reservation turned out in one pool.
 *
 * @param onMerit   how many qualifying candidates would have been selected with no reservation
 * @param toppedUp  how many further ones the reservation brought in
 */
public record HorizontalOutcome(
        HorizontalCategory category,
        int required,
        int onMerit,
        int toppedUp,
        int awarded) {

    /**
     * True when the pool could not find enough qualifying candidates to meet the reservation.
     *
     * <p>Worth reporting rather than hiding: an unfilled reservation means the seats went to
     * others, and a scheme that never mentions it invites the accusation that the reservation was
     * ignored.
     */
    public boolean unfilled() {
        return awarded < required;
    }
}
