package com.zenalyst.housing.intake;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApplicationRepository extends JpaRepository<Application, UUID> {

    Optional<Application> findByApplicationNo(String applicationNo);

    /**
     * Every application in one scheme, in the order the register publishes them.
     *
     * <p>Scheme-scoped on purpose. Loading the table and filtering in memory makes the cost of
     * answering a question about one scheme grow with the data of every other scheme, which is
     * both wasteful and surprising to whoever eventually measures it.
     */
    List<Application> findBySchemeIdOrderByApplicationNoAsc(UUID schemeId);
}
