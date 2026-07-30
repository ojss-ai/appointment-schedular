// TASK: ATOM-LOCATION-001
package com.scheduler.api.location;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Every query is tenant-filtered (ADR-004) — zero exceptions. */
public interface LocationRepository extends JpaRepository<Location, UUID> {

    Optional<Location> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<Location> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);

    Page<Location> findByTenantId(UUID tenantId, Pageable pageable);

    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
}
