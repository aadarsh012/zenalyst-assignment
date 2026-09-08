package com.zenalyst.housing.rules;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RuleVersionRepository extends JpaRepository<RuleVersion, UUID> {

    Optional<RuleVersion> findBySchemeIdAndStatus(UUID schemeId, RuleVersion.Status status);

    Optional<RuleVersion> findBySchemeIdAndVersion(UUID schemeId, String version);

    List<RuleVersion> findBySchemeIdOrderByCreatedAtDesc(UUID schemeId);
}
