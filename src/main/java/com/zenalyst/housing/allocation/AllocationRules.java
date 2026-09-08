package com.zenalyst.housing.allocation;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.zenalyst.housing.platform.hash.CanonicalJson;
import com.zenalyst.housing.platform.hash.Hashing;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The published rule set: how many seats each pool holds, and what share of each is carved out
 * horizontally.
 *
 * <p>Validated on construction rather than trusted. A rule set whose horizontal shares exceed the
 * pool cannot be satisfied, and discovering that during a live draw — after the seed is public and
 * the outcome is being watched — is not a position to be in.
 *
 * <p>{@link #hash()} is published with every result. It commits the authority to the quota matrix
 * that produced the allotment, so "the reserved share was different when you drew it" becomes
 * checkable.
 */
public record AllocationRules(
        Map<SeatPool, Integer> seats,
        List<HorizontalReservation> horizontalReservations) {

    public AllocationRules {
        seats = new EnumMap<>(seats);
        horizontalReservations = List.copyOf(horizontalReservations);

        seats.forEach((pool, count) -> {
            if (count == null || count < 0) {
                throw new IllegalArgumentException("seats for %s must not be negative".formatted(pool));
            }
        });

        BigDecimal totalShare = BigDecimal.ZERO;
        for (HorizontalReservation reservation : horizontalReservations) {
            totalShare = totalShare.add(reservation.share());
        }
        if (totalShare.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException(
                    "horizontal reservations total %s of every pool, which cannot be satisfied"
                            .formatted(totalShare.toPlainString()));
        }

        long distinct = horizontalReservations.stream()
                .map(HorizontalReservation::category).distinct().count();
        if (distinct != horizontalReservations.size()) {
            throw new IllegalArgumentException("a horizontal category may appear only once");
        }
    }

    public int totalSeats() {
        return seats.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int seatsIn(SeatPool pool) {
        return seats.getOrDefault(pool, 0);
    }

    /**
     * How many seats in a pool a horizontal reservation claims.
     *
     * <p>Rounded half-up. Flats are indivisible, and the alternative — rounding down — quietly
     * erases a small pool's horizontal reservation altogether: thirty per cent of six seats would
     * become one rather than two. Half-up is stated here because the number it produces has to be
     * reproducible by anyone checking, and "we rounded" is not a specification.
     */
    public int horizontalRequirement(SeatPool pool, HorizontalReservation reservation) {
        return BigDecimal.valueOf(seatsIn(pool))
                .multiply(reservation.share())
                .setScale(0, RoundingMode.HALF_UP)
                .intValue();
    }

    /** Canonical JSON — the exact bytes hashed and published. */
    public String document() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();

        ObjectNode seatNode = node.putObject("seats");
        for (SeatPool pool : SeatPool.values()) {
            if (seats.containsKey(pool)) {
                seatNode.put(pool.name(), seats.get(pool));
            }
        }

        // Order is preserved because it is meaningful: horizontal shortfalls are topped up in this
        // sequence, and a different sequence can select different people.
        ArrayNode horizontalNode = node.putArray("horizontalReservations");
        for (HorizontalReservation reservation : horizontalReservations) {
            ObjectNode entry = horizontalNode.addObject();
            entry.put("category", reservation.category().name());
            entry.put("share", reservation.share());
        }
        node.put("totalSeats", totalSeats());
        return CanonicalJson.render(node);
    }

    public String hash() {
        return Hashing.sha256Hex(document());
    }

    /** A share of every pool set aside for candidates with one attribute. */
    public record HorizontalReservation(HorizontalCategory category, BigDecimal share) {

        public HorizontalReservation {
            if (share.signum() < 0 || share.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("share must be between 0 and 1");
            }
        }
    }
}
