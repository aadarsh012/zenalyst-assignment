package com.zenalyst.housing.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DuplicateReviewRepository extends JpaRepository<DuplicateReview, UUID> {

    List<DuplicateReview> findBySchemeIdAndStatusOrderByRaisedAtAsc(UUID schemeId, ReviewStatus status);

    List<DuplicateReview> findBySchemeIdOrderByRaisedAtAsc(UUID schemeId);

    List<DuplicateReview> findBySchemeIdAndStatus(UUID schemeId, ReviewStatus status);

    long countBySchemeIdAndStatus(UUID schemeId, ReviewStatus status);

    /** Reviews in the given state that mention this application on either side of the pair. */
    @Query("""
            SELECT r FROM DuplicateReview r
            WHERE r.status = :status
              AND (r.applicationAId = :applicationId OR r.applicationBId = :applicationId)
            ORDER BY r.raisedAt
            """)
    List<DuplicateReview> findByStatusInvolving(
            @Param("status") ReviewStatus status, @Param("applicationId") UUID applicationId);
}
