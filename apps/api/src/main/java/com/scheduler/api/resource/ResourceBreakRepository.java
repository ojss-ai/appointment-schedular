// TASK: ATOM-RESOURCE-002 / ATOM-SLOT-008
package com.scheduler.api.resource;

import com.scheduler.api.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-filtered break queries. The hot slot-calculation lookup is
 * Redis-cached with a tenant-scoped key (ATOM-SLOT-008); break replacement
 * in {@link ResourceService} evicts the cache.
 */
public interface ResourceBreakRepository extends JpaRepository<ResourceBreak, UUID> {

    @Cacheable(value = CacheConfig.CACHE_RESOURCE_BREAKS,
        key = "#tenantId + ':' + #resourceId + ':' + #dayOfWeek")
    List<ResourceBreak> findByTenantIdAndResourceIdAndDayOfWeek(
        UUID tenantId, UUID resourceId, int dayOfWeek);

    List<ResourceBreak> findByTenantIdAndResourceId(UUID tenantId, UUID resourceId);

    @Modifying
    @Query("DELETE FROM ResourceBreak b WHERE b.tenantId = :tenantId AND b.resourceId = :resourceId")
    void deleteByTenantIdAndResourceId(
        @Param("tenantId") UUID tenantId, @Param("resourceId") UUID resourceId);
}
