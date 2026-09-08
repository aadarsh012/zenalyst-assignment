package com.zenalyst.housing.registry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FrozenRegistryRepository extends JpaRepository<FrozenRegistry, UUID> {

    Optional<FrozenRegistry> findFirstBySchemeIdOrderByFrozenAtDesc(UUID schemeId);

    Optional<FrozenRegistry> findFirstByRegistryRootOrderByFrozenAtAsc(String registryRoot);

    List<FrozenRegistry> findBySchemeIdOrderByFrozenAtDesc(UUID schemeId);
}
