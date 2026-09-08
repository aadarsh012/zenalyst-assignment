package com.zenalyst.housing.registry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FrozenCandidateRepository
        extends JpaRepository<FrozenCandidate, FrozenCandidate.Key> {

    List<FrozenCandidate> findByRegistryIdOrderByLeafIndexAsc(UUID registryId);

    Optional<FrozenCandidate> findByRegistryIdAndApplicationNo(UUID registryId, String applicationNo);
}
