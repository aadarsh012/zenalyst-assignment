package com.zenalyst.housing.draw;

import com.zenalyst.housing.allocation.HorizontalOutcome;
import com.zenalyst.housing.allocation.SeatAward;
import com.zenalyst.housing.allocation.SeatPool;
import java.util.List;

/**
 * The outcome of a rehearsal.
 *
 * <p>{@code persisted} is always false and is stated explicitly rather than left to be inferred
 * from the endpoint's name. An operator looking at six hundred names needs to be in no doubt about
 * whether they have just allotted anyone a flat.
 *
 * <p>Waitlists are summarised rather than returned in full: at four thousand candidates the
 * complete lists dwarf everything else in the response. The real draw persists them, and phase 6
 * answers "where am I on the waitlist?" per applicant.
 */
public record DryRunResponse(
        String schemeCode,
        String registryRoot,
        String rulesVersion,
        String rulesHash,
        String seed,
        boolean persisted,
        int candidatesInRegistry,
        int eligibleCandidates,
        int totalSeats,
        int seatsAwarded,
        List<PoolSummary> pools,
        List<SeatAward> awards) {

    public DryRunResponse {
        pools = List.copyOf(pools);
        awards = List.copyOf(awards);
    }

    /**
     * @param cutoffPoolRank the rank of the last candidate selected on merit — the number an
     *                       unsuccessful applicant actually wants to know
     * @param displaced      applicants inside the merit cutoff who lost their seat to a horizontal
     *                       reservation, named rather than counted
     */
    public record PoolSummary(
            SeatPool pool,
            int seats,
            int awarded,
            int competitors,
            int cutoffPoolRank,
            List<HorizontalOutcome> horizontal,
            List<String> displaced,
            int waitlistLength,
            List<String> waitlistHead) {

        public PoolSummary {
            horizontal = List.copyOf(horizontal);
            displaced = List.copyOf(displaced);
            waitlistHead = List.copyOf(waitlistHead);
        }
    }
}
