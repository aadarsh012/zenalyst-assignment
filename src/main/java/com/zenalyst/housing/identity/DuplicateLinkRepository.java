package com.zenalyst.housing.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface DuplicateLinkRepository extends JpaRepository<DuplicateLink, UUID> {

    Optional<DuplicateLink> findByDuplicateApplicationId(UUID duplicateApplicationId);

    List<DuplicateLink> findByCanonicalApplicationId(UUID canonicalApplicationId);

    List<DuplicateLink> findBySchemeId(UUID schemeId);

    /**
     * Clears the scheme's links so a run can rewrite them. Safe because links are derived: the
     * decisions they encode live in {@link DuplicateReview} and the audit chain, both of which
     * survive.
     */
    @Transactional
    void deleteBySchemeId(UUID schemeId);
}
