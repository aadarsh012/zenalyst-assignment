package com.zenalyst.housing.intake;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    Optional<Application> findByApplicationNo(String applicationNo);

    long countBySchemeId(UUID schemeId);
}
