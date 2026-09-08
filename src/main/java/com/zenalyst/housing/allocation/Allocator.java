package com.zenalyst.housing.allocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Allocates the flats.
 *
 * <p>A pure function of {@code (candidates, rules, seed)}. No clock, no database, no Spring, no
 * randomness beyond the seed — enforced by {@code ArchitectureTest} rather than by convention,
 * because the entire defence of this system is that anyone can re-run it and get the same six
 * hundred names.
 *
 * <h2>The algorithm</h2>
 *
 * <ol>
 *   <li><b>Rank everyone.</b> One merit order over all eligible candidates, by lottery ticket.</li>
 *   <li><b>Fill the open pool first, from everybody.</b> This is the migration rule. A reserved
 *       candidate who wins an open seat consumes an open seat, and their category's reserved seats
 *       stay available. Filling the reserved pools first would let a strong SC candidate use up an
 *       SC seat that a weaker SC candidate needed — turning a reservation into a ceiling.</li>
 *   <li><b>Fill each reserved pool</b> from that category's candidates who did not already win an
 *       open seat.</li>
 *   <li><b>Apply horizontal reservations inside each pool</b>, as an adjustment to who fills the
 *       pool's seats rather than as extra seats.</li>
 *   <li><b>Rank the rest</b> into per-pool waitlists.</li>
 * </ol>
 *
 * <h2>How horizontal reservations are applied</h2>
 *
 * <p>Textbook descriptions phrase this as displacement: take the merit list, count the qualifying
 * candidates, and if there are too few, push the lowest-ranked non-qualifying selectees out to make
 * room. That is correct, and implementing it literally means tracking which selectees are protected
 * by a reservation already satisfied, which becomes delicate as soon as two reservations interact.
 *
 * <p>This implementation instead sets aside each reservation's shortfall first and fills the
 * remaining seats on merit. The two produce the same selection — the seats a reservation claims are
 * exactly the seats displacement would have freed — while the second needs no protected set and no
 * ordering subtleties between reservations.
 *
 * <p>Provenance is then recomputed against the merit baseline, so an applicant who would have been
 * selected anyway is recorded as {@code MERIT} and not miscredited to a reservation they happened
 * to qualify for.
 */
public final class Allocator {

    private Allocator() {
    }

    public static AllocationResult allocate(
            List<AllocationCandidate> candidates, AllocationRules rules, String seed) {

        List<RankedCandidate> meritOrder = rank(candidates, seed);

        List<SeatAward> awards = new ArrayList<>();
        List<PoolOutcome> pools = new ArrayList<>();
        Set<String> alreadyAwarded = new LinkedHashSet<>();

        for (SeatPool pool : SeatPool.values()) {
            List<RankedCandidate> competitors = meritOrder.stream()
                    .filter(ranked -> pool.admits(ranked.candidate()))
                    .filter(ranked -> !alreadyAwarded.contains(ranked.applicationNo()))
                    .toList();

            PoolFill fill = fillPool(pool, competitors, rules);
            awards.addAll(fill.awards());
            pools.add(fill.outcome());
            fill.awards().forEach(award -> alreadyAwarded.add(award.applicationNo()));
        }

        return new AllocationResult(
                seed, rules.hash(), rules.totalSeats(), meritOrder.size(), awards, pools, meritOrder);
    }

    /**
     * The one merit order the whole draw derives from.
     *
     * <p>Ineligible candidates are excluded here and nowhere else, so there is exactly one place
     * where somebody can leave the draw.
     */
    private static List<RankedCandidate> rank(List<AllocationCandidate> candidates, String seed) {
        record Ticketed(AllocationCandidate candidate, String ticket) {
        }

        List<Ticketed> ticketed = candidates.stream()
                .filter(AllocationCandidate::eligible)
                .map(candidate -> new Ticketed(
                        candidate, LotteryTicket.forApplication(seed, candidate.applicationNo())))
                .sorted(Comparator.comparing(Ticketed::ticket)
                        .thenComparing(t -> t.candidate().applicationNo()))
                .toList();

        List<RankedCandidate> ranked = new ArrayList<>(ticketed.size());
        for (int i = 0; i < ticketed.size(); i++) {
            ranked.add(new RankedCandidate(ticketed.get(i).candidate(), ticketed.get(i).ticket(), i + 1));
        }
        return ranked;
    }

    private static PoolFill fillPool(SeatPool pool, List<RankedCandidate> competitors, AllocationRules rules) {
        int seats = Math.min(rules.seatsIn(pool), competitors.size());

        // What merit alone would have produced. Kept so that provenance can be honest afterwards.
        Set<String> meritBaseline = competitors.stream()
                .limit(seats)
                .map(RankedCandidate::applicationNo)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

        Map<String, RankedCandidate> selected = new LinkedHashMap<>();
        Map<String, HorizontalCategory> topUpReason = new LinkedHashMap<>();
        List<HorizontalOutcome> horizontalOutcomes = new ArrayList<>();

        for (AllocationRules.HorizontalReservation reservation : rules.horizontalReservations()) {
            int required = Math.min(rules.horizontalRequirement(pool, reservation), seats);
            int alreadyQualifying = (int) selected.values().stream()
                    .filter(ranked -> ranked.candidate().satisfies(reservation.category()))
                    .count();
            int room = seats - selected.size();
            int need = Math.min(required - alreadyQualifying, room);

            List<RankedCandidate> topUps = need <= 0 ? List.of() : competitors.stream()
                    .filter(ranked -> ranked.candidate().satisfies(reservation.category()))
                    .filter(ranked -> !selected.containsKey(ranked.applicationNo()))
                    .limit(need)
                    .toList();

            topUps.forEach(ranked -> {
                selected.put(ranked.applicationNo(), ranked);
                topUpReason.putIfAbsent(ranked.applicationNo(), reservation.category());
            });

            horizontalOutcomes.add(new HorizontalOutcome(
                    reservation.category(), required,
                    (int) meritBaseline.stream()
                            .filter(applicationNo -> qualifies(competitors, applicationNo, reservation.category()))
                            .count(),
                    0, 0));
        }

        // The remaining seats go to the highest-ranked competitors not already selected.
        competitors.stream()
                .filter(ranked -> !selected.containsKey(ranked.applicationNo()))
                .limit(Math.max(0, seats - selected.size()))
                .forEach(ranked -> selected.put(ranked.applicationNo(), ranked));

        return summarise(pool, competitors, rules, seats, meritBaseline, selected, topUpReason, horizontalOutcomes);
    }

    private static PoolFill summarise(
            SeatPool pool, List<RankedCandidate> competitors, AllocationRules rules, int seats,
            Set<String> meritBaseline, Map<String, RankedCandidate> selected,
            Map<String, HorizontalCategory> topUpReason, List<HorizontalOutcome> provisional) {

        Map<String, Integer> poolRanks = new LinkedHashMap<>();
        for (int i = 0; i < competitors.size(); i++) {
            poolRanks.put(competitors.get(i).applicationNo(), i + 1);
        }

        List<SeatAward> awards = new ArrayList<>();
        int cutoffPoolRank = 0;
        for (RankedCandidate ranked : selected.values()) {
            boolean onMerit = meritBaseline.contains(ranked.applicationNo());
            int poolRank = poolRanks.get(ranked.applicationNo());
            if (onMerit) {
                cutoffPoolRank = Math.max(cutoffPoolRank, poolRank);
            }
            awards.add(new SeatAward(
                    ranked.applicationNo(), pool,
                    onMerit ? SeatAward.Basis.MERIT : SeatAward.Basis.HORIZONTAL_TOP_UP,
                    onMerit ? null : topUpReason.get(ranked.applicationNo()),
                    poolRank, ranked.overallRank(), ranked.ticket()));
        }
        awards.sort(Comparator.comparingInt(SeatAward::poolRank));

        // Recount horizontals against the final selection, so the report describes what happened
        // rather than what was intended.
        List<HorizontalOutcome> horizontal = new ArrayList<>();
        for (int i = 0; i < rules.horizontalReservations().size(); i++) {
            AllocationRules.HorizontalReservation reservation = rules.horizontalReservations().get(i);
            int awarded = (int) selected.values().stream()
                    .filter(ranked -> ranked.candidate().satisfies(reservation.category()))
                    .count();
            int onMerit = provisional.get(i).onMerit();
            horizontal.add(new HorizontalOutcome(
                    reservation.category(),
                    Math.min(rules.horizontalRequirement(pool, reservation), seats),
                    onMerit, Math.max(0, awarded - onMerit), awarded));
        }

        List<String> displaced = meritBaseline.stream()
                .filter(applicationNo -> !selected.containsKey(applicationNo))
                .toList();

        List<PoolOutcome.WaitlistEntry> waitlist = competitors.stream()
                .filter(ranked -> !selected.containsKey(ranked.applicationNo()))
                .map(ranked -> new PoolOutcome.WaitlistEntry(
                        ranked.applicationNo(), poolRanks.get(ranked.applicationNo())))
                .toList();

        return new PoolFill(awards, new PoolOutcome(
                pool, rules.seatsIn(pool), awards.size(), competitors.size(),
                cutoffPoolRank, horizontal, displaced, waitlist));
    }

    private static boolean qualifies(
            List<RankedCandidate> competitors, String applicationNo, HorizontalCategory category) {
        return competitors.stream()
                .filter(ranked -> ranked.applicationNo().equals(applicationNo))
                .anyMatch(ranked -> ranked.candidate().satisfies(category));
    }

    private record PoolFill(List<SeatAward> awards, PoolOutcome outcome) {
    }
}
