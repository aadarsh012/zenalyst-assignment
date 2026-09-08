package com.zenalyst.housing.identity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Resolves pairwise matches into whole people.
 *
 * <h2>Why this is not just a loop</h2>
 *
 * <p>Matching produces <em>pairs</em>: A matches B, B matches C. Identity, however, is
 * transitive. If A and B share an identity number, and B and C share a name, date of birth and
 * phone number, then all three are one person — even though A and C have nothing in common that
 * any tier would ever match on.
 *
 * <p>An implementation that merged each pair independently would link A→B and B→C and leave two
 * separate applicants standing, both of whom would then compete in the draw. That person would
 * hold two tickets in a lottery that reserved one per household, and the defect would be
 * invisible until someone charted the links by hand. Computing connected components is what makes
 * "the same person" mean the same thing everywhere.
 *
 * <h2>Implementation</h2>
 *
 * <p>Union-find, with the root of each set kept as the set's <em>canonical</em> application —
 * that is, unions attach the later application under the earlier one rather than by tree rank. It
 * costs a little asymptotic tidiness and buys a great deal of clarity: {@code find(x)} returns the
 * application that will represent x, with no separate pass to work out which member wins.
 *
 * <p>The class is pure: no Spring, no database, no clock. The same matches in any order produce
 * the same groups, which is what lets deduplication be re-run and audited.
 */
public final class MergeGraph {

    /** Insertion-ordered so that output ordering does not depend on hash layout. */
    private final Map<UUID, ApplicationRef> members = new LinkedHashMap<>();
    private final Map<UUID, UUID> parent = new HashMap<>();

    /** Registers an application. Applications with no matches simply form groups of one. */
    public void add(ApplicationRef ref) {
        if (members.putIfAbsent(ref.id(), ref) == null) {
            parent.put(ref.id(), ref.id());
        }
    }

    /**
     * Records that two applications are the same person.
     *
     * @throws IllegalArgumentException if either application was never added — a match referring
     *                                  to an application outside the register means the caller
     *                                  built the graph from inconsistent data, which should fail
     *                                  loudly rather than silently drop the link
     */
    public void link(UUID a, UUID b) {
        requireKnown(a);
        requireKnown(b);

        UUID rootA = find(a);
        UUID rootB = find(b);
        if (rootA.equals(rootB)) {
            return;
        }

        // The earlier application becomes the root, so the root is always the canonical one.
        if (members.get(rootA).compareTo(members.get(rootB)) <= 0) {
            parent.put(rootB, rootA);
        } else {
            parent.put(rootA, rootB);
        }
    }

    /**
     * @return every group containing more than one application — that is, every person who
     *         applied more than once. Groups and their members are sorted, so the result is
     *         identical for identical input regardless of the order matches were added.
     */
    public List<MergeGroup> groups() {
        Map<UUID, List<ApplicationRef>> byRoot = new LinkedHashMap<>();
        for (ApplicationRef ref : members.values()) {
            byRoot.computeIfAbsent(find(ref.id()), key -> new ArrayList<>()).add(ref);
        }

        List<MergeGroup> groups = new ArrayList<>();
        for (Map.Entry<UUID, List<ApplicationRef>> entry : byRoot.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            List<ApplicationRef> sorted = new ArrayList<>(entry.getValue());
            Collections.sort(sorted);

            ApplicationRef canonical = sorted.get(0);
            groups.add(new MergeGroup(canonical, sorted.subList(1, sorted.size())));
        }
        groups.sort((left, right) -> left.canonical().compareTo(right.canonical()));
        return groups;
    }

    /** Standard find with path compression. */
    private UUID find(UUID id) {
        UUID root = id;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        UUID cursor = id;
        while (!cursor.equals(root)) {
            UUID next = parent.get(cursor);
            parent.put(cursor, root);
            cursor = next;
        }
        return root;
    }

    private void requireKnown(UUID id) {
        if (!members.containsKey(id)) {
            throw new IllegalArgumentException("application " + id + " is not in the graph");
        }
    }
}
