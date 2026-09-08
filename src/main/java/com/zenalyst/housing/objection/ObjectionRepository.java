package com.zenalyst.housing.objection;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ObjectionRepository extends JpaRepository<Objection, UUID> {

    List<Objection> findBySchemeIdOrderByFiledAtDesc(UUID schemeId);

    List<Objection> findBySchemeIdAndStatusOrderByFiledAtDesc(UUID schemeId, ObjectionStatus status);

    List<Objection> findByDrawIdAndStatus(UUID drawId, ObjectionStatus status);

    long countByDrawIdAndStatus(UUID drawId, ObjectionStatus status);
}
