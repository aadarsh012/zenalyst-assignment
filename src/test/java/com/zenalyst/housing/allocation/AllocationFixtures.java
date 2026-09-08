package com.zenalyst.housing.allocation;

import com.zenalyst.housing.intake.Category;
import com.zenalyst.housing.intake.Gender;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Helpers for building draws whose outcome can be reasoned about by hand.
 *
 * <p>The lottery order is an HMAC and therefore not predictable by inspection, which would make
 * "the third-ranked candidate should…" impossible to write. The way round it is
 * {@link #meritOrder}: rank a set of application numbers first, then assign attributes by
 * <em>rank</em>. Attributes do not affect ranking — a ticket depends only on the seed and the
 * application number — so this is not circular, and it lets a test say exactly what it means.
 */
final class AllocationFixtures {

    static final String SEED = "seed-for-tests-only";

    private AllocationFixtures() {
    }

    static List<String> applicationNumbers(int count) {
        List<String> numbers = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            numbers.add("A-%06d".formatted(i));
        }
        return numbers;
    }

    /** Application numbers in the order the draw will rank them, best first. */
    static List<String> meritOrder(List<String> applicationNumbers, String seed) {
        return applicationNumbers.stream()
                .sorted(Comparator
                        .comparing((String no) -> LotteryTicket.forApplication(seed, no))
                        .thenComparing(no -> no))
                .toList();
    }

    static AllocationCandidate candidate(String applicationNo, Category category) {
        return new AllocationCandidate(applicationNo, category, Gender.MALE, false, false, false, true);
    }

    static AllocationCandidate with(
            AllocationCandidate base, Gender gender, boolean disability,
            boolean exService, boolean local) {
        return new AllocationCandidate(base.applicationNo(), base.category(), gender,
                disability, exService, local, base.eligible());
    }

    static AllocationRules rules(Map<SeatPool, Integer> seats, AllocationRules.HorizontalReservation... horizontal) {
        Map<SeatPool, Integer> map = new EnumMap<>(SeatPool.class);
        map.putAll(seats);
        return new AllocationRules(map, List.of(horizontal));
    }

    static Map<SeatPool, Integer> seats(int open, int sc, int st, int obc, int ews) {
        Map<SeatPool, Integer> map = new LinkedHashMap<>();
        map.put(SeatPool.OPEN, open);
        map.put(SeatPool.SC, sc);
        map.put(SeatPool.ST, st);
        map.put(SeatPool.OBC, obc);
        map.put(SeatPool.EWS, ews);
        return map;
    }

    static AllocationRules.HorizontalReservation horizontal(HorizontalCategory category, String share) {
        return new AllocationRules.HorizontalReservation(category, new BigDecimal(share));
    }

    static PoolOutcome poolOf(AllocationResult result, SeatPool pool) {
        return result.pools().stream()
                .filter(outcome -> outcome.pool() == pool)
                .findFirst().orElseThrow();
    }

    static List<String> awardedIn(AllocationResult result, SeatPool pool) {
        return result.awards().stream()
                .filter(award -> award.pool() == pool)
                .map(SeatAward::applicationNo)
                .toList();
    }
}
