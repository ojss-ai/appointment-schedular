// TASK: P1-T09
package com.scheduler.api.tenant;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenant lookup. The tenants table is the tenancy anchor itself, so slug
 * lookup is the single permitted non-tenant-filtered query in the codebase
 * (it resolves which tenant a request belongs to).
 */
public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    Optional<Tenant> findBySlug(String slug);
}
