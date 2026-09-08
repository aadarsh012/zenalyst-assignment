package com.zenalyst.housing.identity;

import java.util.UUID;

/**
 * Two applications that share a fingerprint, and therefore the same person.
 *
 * @param fingerprint the shared value — kept so the link's evidence can name the exact thing that
 *                    matched, rather than asserting that something did
 */
public record ExactMatch(UUID applicationAId, UUID applicationBId, MatchTier tier, String fingerprint) {
}
