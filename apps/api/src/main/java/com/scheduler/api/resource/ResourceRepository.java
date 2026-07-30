// TASK: ATOM-RESOURCE-002 / ATOM-BOOKING-009
package com.scheduler.api.resource;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/** Every query is tenant-filtered (ADR-004) — zero exceptions. */
public interface ResourceRepository extends JpaRepository<Resource, UUID> {

    Optional<Resource> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<Resource> findByTenantIdAndLocationIdAndStatus(
        UUID tenantId, UUID locationId, String status, Pageable pageable);

    Page<Resource> findByTenantIdAndLocationId(UUID tenantId, UUID locationId, Pageable pageable);

    /**
     * Pessimistic anchor lock for hold creation (ADR-002): locking the
     * resource row serializes all concurrent {@code createHold} calls for
     * one resource, making the subsequent conflict check race-free even
     * when there are no existing booking rows to lock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Resource r WHERE r.id = :id AND r.tenantId = :tenantId")
    Optional<Resource> findByIdAndTenantIdForUpdate(
        @Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
