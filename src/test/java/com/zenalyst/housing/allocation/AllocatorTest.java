package com.zenalyst.housing.allocation;

import static com.zenalyst.housing.allocation.AllocationFixtures.SEED;
import static com.zenalyst.housing.allocation.AllocationFixtures.applicationNumbers;
import static com.zenalyst.housing.allocation.AllocationFixtures.awardedIn;
import static com.zenalyst.housing.allocation.AllocationFixtures.candidate;
import static com.zenalyst.housing.allocation.AllocationFixtures.horizontal;
import static com.zenalyst.housing.allocation.AllocationFixtures.meritOrder;
import static com.zenalyst.housing.allocation.AllocationFixtures.poolOf;
import static com.zenalyst.housing.allocation.AllocationFixtures.rules;
import static com.zenalyst.housing.allocation.AllocationFixtures.seats;
import static com.zenalyst.housing.allocation.AllocationFixtures.with;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zenalyst.housing.intake.Category;
import com.zenalyst.housing.intake.Gender;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class AllocatorTest {

    @Nested
    @DisplayName("the migration rule")
    class Migration {

        @Test
        @DisplayName("a reserved candidate who wins on open merit takes an OPEN seat")
        void reservedCandidateOnMeritTakesAnOpenSeat() {
            List<String> numbers = applicationNumbers(20);
            List<String> order = meritOrder(numbers, SEED);

            // Everyone is SC, so whoever tops the draw is necessarily a reserved candidate.
            List<AllocationCandidate> candidates = numbers.stream()
                    .map(no -> candidate(no, Category.SC)).toList();

            AllocationResult result = Allocator.allocate(
                    candidates, rules(seats(3, 2, 0, 0, 0)), SEED);

            assertThat(awardedIn(result, SeatPool.OPEN))
                    .containsExactlyElementsOf(order.subList(0, 3));
        }

        @Test
        @DisplayName("and their category's reserved seats remain fully available to others")
        void reservedSeatsAreNotConsumedByOpenWinners() {
            List<String> numbers = applicationNumbers(20);
            List<String> order = meritOrder(numbers, SEED);
            List<AllocationCandidate> candidates = numbers.stream()
                    .map(no -> candidate(no, Category.SC)).toList();

            AllocationResult result = Allocator.allocate(
                    candidates, rules(seats(3, 2, 0, 0, 0)), SEED);

            // Five flats to SC applicants from five seats, not three. Without the migration rule
            // the top three would have consumed SC seats and only three SC applicants would be
            // housed — a reservation turned into a ceiling.
            assertThat(result.awards()).hasSize(5);
            assertThat(awardedIn(result, SeatPool.SC))
                    .containsExactlyElementsOf(order.subList(3, 5))
                    .doesNotContainAnyElementsOf(awardedIn(result, SeatPool.OPEN));
        }

        @Test
        @DisplayName("nobody is awarded twice")
        void nobodyIsAwardedTwice() {
            List<String> numbers = applicationNumbers(40);
            List<AllocationCandidate> candidates = new ArrayList<>();
            Category[] categories = {Category.GEN, Category.SC, Category.ST, Category.OBC, Category.EWS};
            for (int i = 0; i < numbers.size(); i++) {
                candidates.add(candidate(numbers.get(i), categories[i % categories.length]));
            }

            AllocationResult result = Allocator.allocate(
                    candidates, rules(seats(10, 4, 3, 5, 2)), SEED);

            assertThat(result.awards()).extracting(SeatAward::applicationNo).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("a reserved pool only ever admits its own category")
        void reservedPoolsAdmitOnlyTheirCategory() {
            List<String> numbers = applicationNumbers(40);
            List<AllocationCandidate> candidates = new ArrayList<>();
            Category[] categories = {Category.GEN, Category.SC, Category.ST, Category.OBC, Category.EWS};
            for (int i = 0; i < numbers.size(); i++) {
                candidates.add(candidate(numbers.get(i), categories[i % categories.length]));
            }
            Map<String, Category> categoryOf = candidates.stream().collect(
                    java.util.stream.Collectors.toMap(AllocationCandidate::applicationNo,
                            AllocationCandidate::category));

            AllocationResult result = Allocator.allocate(
                    candidates, rules(seats(10, 4, 3, 5, 2)), SEED);

            result.awards().stream()
                    .filter(award -> award.pool().isReserved())
                    .forEach(award -> assertThat(categoryOf.get(award.applicationNo()))
                            .as("%s in pool %s", award.applicationNo(), award.pool())
                            .isEqualTo(award.pool().category()));
        }
    }

    @Nested
    @DisplayName("horizontal reservations are carved out of a pool, not added to it")
    class Horizontal {

        /** Ten open seats, twenty candidates, and the three lowest-ranked are the only PwD ones. */
        private List<AllocationCandidate> populationWithLowRankedPwd() {
            List<String> numbers = applicationNumbers(20);
            List<String> order = meritOrder(numbers, SEED);
            List<String> lowestThree = order.subList(17, 20);

            return numbers.stream()
                    .map(no -> {
                        AllocationCandidate base = candidate(no, Category.GEN);
                        return lowestThree.contains(no)
                                ? with(base, Gender.MALE, true, false, false)
                                : base;
                    })
                    .toList();
        }

        @Test
        @DisplayName("the pool still awards exactly its own number of seats")
        void poolSizeIsUnchanged() {
            AllocationResult result = Allocator.allocate(
                    populationWithLowRankedPwd(),
                    rules(seats(10, 0, 0, 0, 0), horizontal(HorizontalCategory.PWD, "0.20")),
                    SEED);

            // Twenty per cent of ten is two seats set aside, not two seats added.
            assertThat(awardedIn(result, SeatPool.OPEN)).hasSize(10);
        }

        @Test
        @DisplayName("the reservation is met by the highest-ranked qualifying candidates")
        void shortfallIsToppedUpByMerit() {
            List<String> order = meritOrder(applicationNumbers(20), SEED);
            AllocationResult result = Allocator.allocate(
                    populationWithLowRankedPwd(),
                    rules(seats(10, 0, 0, 0, 0), horizontal(HorizontalCategory.PWD, "0.20")),
                    SEED);

            // Ranks 18 and 19 are the two best of the three PwD candidates; rank 20 misses out.
            assertThat(awardedIn(result, SeatPool.OPEN))
                    .contains(order.get(17), order.get(18))
                    .doesNotContain(order.get(19));
        }

        @Test
        @DisplayName("the displaced candidates are the lowest-ranked of those who would have qualified")
        void theLowestRankedOnMeritAreDisplaced() {
            List<String> order = meritOrder(applicationNumbers(20), SEED);
            AllocationResult result = Allocator.allocate(
                    populationWithLowRankedPwd(),
                    rules(seats(10, 0, 0, 0, 0), horizontal(HorizontalCategory.PWD, "0.20")),
                    SEED);

            // Ranks 9 and 10 lose their seats — and are named, because they have the strongest
            // reason of anyone to ask what happened.
            assertThat(poolOf(result, SeatPool.OPEN).displaced())
                    .containsExactlyInAnyOrder(order.get(8), order.get(9));
        }

        @Test
        @DisplayName("a candidate who would have won anyway is credited to merit, not to the reservation")
        void provenanceIsNotMiscredited() {
            List<String> numbers = applicationNumbers(20);
            List<String> order = meritOrder(numbers, SEED);
            String topRanked = order.get(0);

            // The best candidate in the draw also happens to have a disability.
            List<AllocationCandidate> candidates = numbers.stream()
                    .map(no -> no.equals(topRanked)
                            ? with(candidate(no, Category.GEN), Gender.MALE, true, false, false)
                            : candidate(no, Category.GEN))
                    .toList();

            AllocationResult result = Allocator.allocate(
                    candidates, rules(seats(10, 0, 0, 0, 0), horizontal(HorizontalCategory.PWD, "0.10")),
                    SEED);

            // Recording her as a reservation appointment would be both false and insulting.
            assertThat(result.awards()).filteredOn(award -> award.applicationNo().equals(topRanked))
                    .singleElement()
                    .extracting(SeatAward::basis).isEqualTo(SeatAward.Basis.MERIT);
        }

        @Test
        @DisplayName("a candidate counts toward every reservation they qualify for")
        void oneCandidateCanSatisfyTwoReservations() {
            List<String> numbers = applicationNumbers(20);
            List<AllocationCandidate> candidates = numbers.stream()
                    .map(no -> with(candidate(no, Category.GEN), Gender.FEMALE, true, false, false))
                    .toList();

            AllocationResult result = Allocator.allocate(
                    candidates,
                    rules(seats(10, 0, 0, 0, 0),
                            horizontal(HorizontalCategory.WOMEN, "0.30"),
                            horizontal(HorizontalCategory.PWD, "0.20")),
                    SEED);

            // Everyone is both, so both reservations are satisfied by the same ten people and no
            // extra seats are consumed.
            assertThat(awardedIn(result, SeatPool.OPEN)).hasSize(10);
            assertThat(poolOf(result, SeatPool.OPEN).horizontal())
                    .allSatisfy(outcome -> assertThat(outcome.unfilled()).isFalse());
        }

        @Test
        @DisplayName("a reservation with too few qualifying candidates is reported, not hidden")
        void unfilledReservationsAreReported() {
            List<String> numbers = applicationNumbers(20);
            List<AllocationCandidate> candidates = numbers.stream()
                    .map(no -> candidate(no, Category.GEN)).toList();   // nobody qualifies

            AllocationResult result = Allocator.allocate(
                    candidates, rules(seats(10, 0, 0, 0, 0), horizontal(HorizontalCategory.PWD, "0.20")),
                    SEED);

            HorizontalOutcome pwd = poolOf(result, SeatPool.OPEN).horizontal().get(0);
            assertThat(pwd.required()).isEqualTo(2);
            assertThat(pwd.awarded()).isZero();
            assertThat(pwd.unfilled()).isTrue();
            // The seats are still allotted — to others — rather than left empty.
            assertThat(awardedIn(result, SeatPool.OPEN)).hasSize(10);
        }

        @Test
        @DisplayName("shares are rounded half-up, so a small pool keeps its reservation")
        void roundingIsHalfUp() {
            AllocationRules rules = rules(seats(0, 0, 0, 0, 6),
                    horizontal(HorizontalCategory.WOMEN, "0.30"));

            // Thirty per cent of six is 1.8. Rounding down would quietly cut the reservation to
            // one seat in every small pool.
            assertThat(rules.horizontalRequirement(SeatPool.EWS,
                    horizontal(HorizontalCategory.WOMEN, "0.30"))).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("determinism")
    class Determinism {

        @Test
        @DisplayName("the same inputs in any order produce byte-identical results")
        void inputOrderDoesNotMatter() {
            List<AllocationCandidate> candidates = new ArrayList<>();
            Category[] categories = {Category.GEN, Category.SC, Category.ST, Category.OBC, Category.EWS};
            List<String> numbers = applicationNumbers(200);
            for (int i = 0; i < numbers.size(); i++) {
                AllocationCandidate base = candidate(numbers.get(i), categories[i % categories.length]);
                candidates.add(with(base, i % 3 == 0 ? Gender.FEMALE : Gender.MALE,
                        i % 7 == 0, i % 11 == 0, i % 2 == 0));
            }
            AllocationRules rules = rules(seats(30, 10, 5, 15, 3),
                    horizontal(HorizontalCategory.WOMEN, "0.30"),
                    horizontal(HorizontalCategory.PWD, "0.05"),
                    horizontal(HorizontalCategory.LOCAL_RESIDENT, "0.20"));

            AllocationResult reference = Allocator.allocate(candidates, rules, SEED);

            // A draw that depended on the order rows came back from a database would be
            // impossible for anybody else to reproduce.
            java.util.Random shuffler = new java.util.Random(42);
            for (int attempt = 0; attempt < 25; attempt++) {
                List<AllocationCandidate> shuffled = new ArrayList<>(candidates);
                Collections.shuffle(shuffled, shuffler);
                assertThat(Allocator.allocate(shuffled, rules, SEED)).isEqualTo(reference);
            }
        }

        @Test
        @DisplayName("a different seed produces a different draw")
        void seedDecidesTheOutcome() {
            List<AllocationCandidate> candidates = applicationNumbers(200).stream()
                    .map(no -> candidate(no, Category.GEN)).toList();
            AllocationRules rules = rules(seats(20, 0, 0, 0, 0));

            assertThat(awardedIn(Allocator.allocate(candidates, rules, "seed-one"), SeatPool.OPEN))
                    .isNotEqualTo(awardedIn(Allocator.allocate(candidates, rules, "seed-two"), SeatPool.OPEN));
        }
    }

    @Nested
    @DisplayName("invariants over randomly generated populations")
    class Invariants {

        @Test
        @DisplayName("no draw ever breaks the rules it was given")
        void invariantsHoldAcrossManyPopulations() {
            java.util.Random random = new java.util.Random(20260908L);
            Category[] categories = Category.values();
            Gender[] genders = Gender.values();

            for (int run = 0; run < 60; run++) {
                int population = 20 + random.nextInt(400);
                List<AllocationCandidate> candidates = new ArrayList<>(population);
                for (int i = 1; i <= population; i++) {
                    candidates.add(new AllocationCandidate(
                            "R%d-A-%06d".formatted(run, i),
                            categories[random.nextInt(categories.length)],
                            genders[random.nextInt(genders.length)],
                            random.nextInt(10) == 0,
                            random.nextInt(20) == 0,
                            random.nextBoolean(),
                            random.nextInt(10) > 0));
                }
                AllocationRules rules = rules(
                        seats(random.nextInt(40), random.nextInt(20), random.nextInt(10),
                                random.nextInt(25), random.nextInt(6)),
                        horizontal(HorizontalCategory.WOMEN, "0.30"),
                        horizontal(HorizontalCategory.PWD, "0.05"),
                        horizontal(HorizontalCategory.EX_SERVICE, "0.03"));

                AllocationResult result = Allocator.allocate(candidates, rules, "run-" + run);
                assertInvariants(candidates, rules, result, run);
            }
        }

        private void assertInvariants(
                List<AllocationCandidate> candidates, AllocationRules rules,
                AllocationResult result, int run) {

            Map<String, AllocationCandidate> byNumber = candidates.stream().collect(
                    java.util.stream.Collectors.toMap(AllocationCandidate::applicationNo, c -> c));

            assertThat(result.awards()).as("run %d: nobody twice", run)
                    .extracting(SeatAward::applicationNo).doesNotHaveDuplicates();

            assertThat(result.awards().size()).as("run %d: never more flats than seats", run)
                    .isLessThanOrEqualTo(rules.totalSeats());

            for (PoolOutcome pool : result.pools()) {
                assertThat(pool.awarded()).as("run %d: %s within its seats", run, pool.pool())
                        .isLessThanOrEqualTo(pool.seats());
            }

            result.awards().forEach(award -> {
                AllocationCandidate candidate = byNumber.get(award.applicationNo());
                assertThat(candidate.eligible())
                        .as("run %d: %s was ineligible", run, award.applicationNo()).isTrue();
                if (award.pool().isReserved()) {
                    assertThat(candidate.category())
                            .as("run %d: %s in the wrong pool", run, award.applicationNo())
                            .isEqualTo(award.pool().category());
                }
            });

            // A pool with unfilled seats must have run out of competitors, never out of nerve.
            for (PoolOutcome pool : result.pools()) {
                if (pool.awarded() < pool.seats()) {
                    assertThat(pool.waitlist()).as("run %d: %s left seats empty with people waiting",
                            run, pool.pool()).isEmpty();
                }
            }
        }
    }

    @Nested
    @DisplayName("edges")
    class Edges {

        @Test
        void noCandidatesAllocatesNothing() {
            AllocationResult result = Allocator.allocate(List.of(), rules(seats(10, 5, 5, 5, 5)), SEED);

            assertThat(result.awards()).isEmpty();
            assertThat(result.eligibleCandidates()).isZero();
        }

        @Test
        @DisplayName("ineligible candidates never enter the draw at all")
        void ineligibleCandidatesAreExcluded() {
            List<AllocationCandidate> candidates = applicationNumbers(10).stream()
                    .map(no -> new AllocationCandidate(no, Category.GEN, Gender.MALE,
                            false, false, false, false))
                    .toList();

            AllocationResult result = Allocator.allocate(candidates, rules(seats(5, 0, 0, 0, 0)), SEED);

            assertThat(result.awards()).isEmpty();
            assertThat(result.meritOrder()).isEmpty();
        }

        @Test
        @DisplayName("more seats than applicants leaves seats empty rather than inventing winners")
        void moreSeatsThanApplicants() {
            List<AllocationCandidate> candidates = applicationNumbers(3).stream()
                    .map(no -> candidate(no, Category.GEN)).toList();

            AllocationResult result = Allocator.allocate(candidates, rules(seats(10, 0, 0, 0, 0)), SEED);

            assertThat(result.awards()).hasSize(3);
            assertThat(poolOf(result, SeatPool.OPEN).waitlist()).isEmpty();
        }

        @Test
        @DisplayName("everyone who missed out is ranked, so a surrender has a successor")
        void waitlistsAreComplete() {
            List<AllocationCandidate> candidates = applicationNumbers(20).stream()
                    .map(no -> candidate(no, Category.GEN)).toList();
            List<String> order = meritOrder(applicationNumbers(20), SEED);

            AllocationResult result = Allocator.allocate(candidates, rules(seats(5, 0, 0, 0, 0)), SEED);

            assertThat(poolOf(result, SeatPool.OPEN).waitlist())
                    .extracting(PoolOutcome.WaitlistEntry::applicationNo)
                    .containsExactlyElementsOf(order.subList(5, 20));

            // Each entry carries where it actually came, not merely its place in the queue. With
            // no reservation in play here the two happen to differ by exactly the five seats.
            assertThat(poolOf(result, SeatPool.OPEN).waitlist())
                    .extracting(PoolOutcome.WaitlistEntry::poolRank)
                    .containsExactly(6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20);
        }
    }

    @Nested
    @DisplayName("rule validation happens before the draw, not during it")
    class RuleValidation {

        @Test
        void horizontalSharesCannotExceedThePool() {
            assertThatThrownBy(() -> rules(seats(10, 0, 0, 0, 0),
                    horizontal(HorizontalCategory.WOMEN, "0.60"),
                    horizontal(HorizontalCategory.PWD, "0.50")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be satisfied");
        }

        @Test
        void aHorizontalCategoryCannotBeListedTwice() {
            assertThatThrownBy(() -> rules(seats(10, 0, 0, 0, 0),
                    horizontal(HorizontalCategory.WOMEN, "0.10"),
                    horizontal(HorizontalCategory.WOMEN, "0.20")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("only once");
        }

        @Test
        void seatCountsCannotBeNegative() {
            assertThatThrownBy(() -> rules(seats(-1, 0, 0, 0, 0)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("the rules hash changes when any part of the quota matrix changes")
        void rulesHashCoversEverything() {
            String base = rules(seats(300, 90, 42, 162, 6),
                    horizontal(HorizontalCategory.WOMEN, "0.30")).hash();

            assertThat(rules(seats(300, 90, 42, 162, 7),
                    horizontal(HorizontalCategory.WOMEN, "0.30")).hash()).isNotEqualTo(base);
            assertThat(rules(seats(300, 90, 42, 162, 6),
                    horizontal(HorizontalCategory.WOMEN, "0.31")).hash()).isNotEqualTo(base);
            assertThat(rules(seats(300, 90, 42, 162, 6),
                    horizontal(HorizontalCategory.PWD, "0.30")).hash()).isNotEqualTo(base);
            assertThat(base).matches("^[0-9a-f]{64}$");
        }

        @Test
        @DisplayName("the order of horizontal reservations is part of the published rules")
        void reservationOrderIsPartOfTheRules() {
            // Shortfalls are topped up in sequence, and a different sequence can select different
            // people. Publishing the order is what makes the draw reproducible.
            String womenFirst = rules(seats(10, 0, 0, 0, 0),
                    horizontal(HorizontalCategory.WOMEN, "0.30"),
                    horizontal(HorizontalCategory.PWD, "0.05")).hash();
            String pwdFirst = rules(seats(10, 0, 0, 0, 0),
                    horizontal(HorizontalCategory.PWD, "0.05"),
                    horizontal(HorizontalCategory.WOMEN, "0.30")).hash();

            assertThat(womenFirst).isNotEqualTo(pwdFirst);
        }
    }

    @Test
    @DisplayName("a full-scale draw: 4,000 candidates for 600 flats")
    void fullScaleDraw() {
        java.util.Random random = new java.util.Random(600L);
        Category[] categories = Category.values();
        List<AllocationCandidate> candidates = new ArrayList<>(4000);
        for (int i = 1; i <= 4000; i++) {
            candidates.add(new AllocationCandidate(
                    "MHS-2026-%06d".formatted(i),
                    categories[random.nextInt(categories.length)],
                    random.nextBoolean() ? Gender.FEMALE : Gender.MALE,
                    random.nextInt(12) == 0,
                    random.nextInt(30) == 0,
                    random.nextInt(10) < 4,
                    true));
        }

        AllocationRules rules = rules(seats(300, 90, 42, 162, 6),
                horizontal(HorizontalCategory.WOMEN, "0.30"),
                horizontal(HorizontalCategory.PWD, "0.05"),
                horizontal(HorizontalCategory.EX_SERVICE, "0.03"));

        AllocationResult result = Allocator.allocate(candidates, rules, "full-scale-seed");

        assertThat(rules.totalSeats()).isEqualTo(600);
        assertThat(result.awards()).hasSize(600);
        assertThat(result.awards()).extracting(SeatAward::applicationNo).doesNotHaveDuplicates();
        assertThat(result.meritOrder()).hasSize(4000);

        // Every reserved pool filled, and the open pool's women's reservation met.
        assertThat(poolOf(result, SeatPool.SC).awarded()).isEqualTo(90);
        assertThat(poolOf(result, SeatPool.OPEN).horizontal())
                .filteredOn(outcome -> outcome.category() == HorizontalCategory.WOMEN)
                .singleElement()
                .satisfies(outcome -> assertThat(outcome.awarded()).isGreaterThanOrEqualTo(outcome.required()));
    }

    @Test
    @DisplayName("a ticket depends only on the seed and the application number")
    void ticketsAreIndependentOfEverythingElse() {
        assertThat(LotteryTicket.forApplication("s", "A-000001"))
                .isEqualTo(LotteryTicket.forApplication("s", "A-000001"))
                .matches("^[0-9a-f]{64}$")
                .isNotEqualTo(LotteryTicket.forApplication("s", "A-000002"))
                .isNotEqualTo(LotteryTicket.forApplication("t", "A-000001"));
    }

    @Test
    @DisplayName("seats and shares are copied, so a caller cannot mutate the rules afterwards")
    void rulesAreDefensivelyCopied() {
        Map<SeatPool, Integer> mutable = seats(10, 0, 0, 0, 0);
        AllocationRules rules = new AllocationRules(mutable,
                List.of(new AllocationRules.HorizontalReservation(
                        HorizontalCategory.WOMEN, new BigDecimal("0.30"))));

        mutable.put(SeatPool.OPEN, 9999);

        assertThat(rules.seatsIn(SeatPool.OPEN)).isEqualTo(10);
    }
}
