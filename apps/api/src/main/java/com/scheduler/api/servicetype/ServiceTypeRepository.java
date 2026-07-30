// TASK: ATOM-SERVICE-003
package com.scheduler.api.servicetype;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Every query is tenant-filtered (ADR-004) — zero exceptions. */
public interface ServiceTypeRepository extends JpaRepository<ServiceType, UUID> {

    Optional<ServiceType> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<ServiceType> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);

    Page<ServiceType> findByTenantId(UUID tenantId, Pageable pageable);
}
