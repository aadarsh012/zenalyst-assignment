package com.zenalyst.housing.eligibility;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClaimVerificationRepository extends JpaRepository<ClaimVerification, UUID> {

    List<ClaimVerification> findByApplicationId(UUID applicationId);

    Optional<ClaimVerification> findByApplicationIdAndClaim(UUID applicationId, ClaimType claim);

    /** Every verification for a scheme, fetched once so eligibility can be evaluated in bulk. */
    @org.springframework.data.jpa.repository.Query("""
            SELECT v FROM ClaimVerification v
            WHERE v.applicationId IN (
                SELECT a.id FROM Application a WHERE a.schemeId = :schemeId
            )
            """)
    List<ClaimVerification> findBySchemeId(@org.springframework.data.repository.query.Param("schemeId") UUID schemeId);
}
