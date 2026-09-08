package com.zenalyst.housing.identity;

import java.util.List;

/**
 * One person, and every application they turn out to have made.
 *
 * @param canonical  the application that stands for the person: earliest submission, ties broken
 *                   by the lower application number
 * @param duplicates the rest, in the same order; never empty, never containing {@code canonical}
 */
public record MergeGroup(ApplicationRef canonical, List<ApplicationRef> duplicates) {

    public MergeGroup {
        duplicates = List.copyOf(duplicates);
    }

    public int size() {
        return duplicates.size() + 1;
    }
}
