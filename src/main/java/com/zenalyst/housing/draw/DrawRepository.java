package com.zenalyst.housing.draw;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DrawRepository extends JpaRepository<Draw, UUID> {

    List<Draw> findBySchemeIdOrderByCommittedAtDesc(UUID schemeId);

    Optional<Draw> findBySchemeIdAndStatusIn(UUID schemeId, List<DrawStatus> statuses);

    /**
     * Loads a draw with a row-level write lock.
     *
     * <p>This is what makes {@code execute} exactly-once. Two simultaneous requests both reach the
     * status check; without the lock both read {@code REVEALED}, both proceed, and the draw runs
     * twice. With it, the second waits, then reads {@code RUNNING} and declines. No advisory lock,
     * no application-level mutex — the row being changed is the thing to lock.
     */
    /** The draw that replaced this one, if any. Supersession is recorded on the successor. */
    Optional<Draw> findBySupersedesDrawId(UUID supersededDrawId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Draw d WHERE d.id = :id")
    Optional<Draw> findByIdForUpdate(@Param("id") UUID id);
}
