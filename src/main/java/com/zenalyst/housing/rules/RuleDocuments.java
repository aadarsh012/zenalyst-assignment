package com.zenalyst.housing.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenalyst.housing.allocation.AllocationRules;
import com.zenalyst.housing.allocation.HorizontalCategory;
import com.zenalyst.housing.allocation.SeatPool;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a stored rule document back into the allocator's rules.
 *
 * <p>Parsing the <em>stored canonical bytes</em> rather than re-deriving the rules from an entity's
 * fields is deliberate. The stored document is what was hashed and published; anything else is a
 * reconstruction that might differ from it in some way nobody notices until a verifier's hash
 * disagrees with ours.
 */
final class RuleDocuments {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleDocuments() {
    }

    static AllocationRules parse(String canonicalDocument) {
        try {
            JsonNode root = MAPPER.readTree(canonicalDocument);

            Map<SeatPool, Integer> seats = new EnumMap<>(SeatPool.class);
            root.path("seats").fields().forEachRemaining(entry ->
                    seats.put(SeatPool.valueOf(entry.getKey()), entry.getValue().asInt()));

            List<AllocationRules.HorizontalReservation> horizontal = new ArrayList<>();
            for (JsonNode entry : root.path("horizontalReservations")) {
                horizontal.add(new AllocationRules.HorizontalReservation(
                        HorizontalCategory.valueOf(entry.path("category").asText()),
                        entry.path("share").decimalValue()));
            }
            return new AllocationRules(seats, horizontal);

        } catch (Exception e) {
            throw new IllegalStateException(
                    "stored rule document could not be read: " + canonicalDocument, e);
        }
    }
}
