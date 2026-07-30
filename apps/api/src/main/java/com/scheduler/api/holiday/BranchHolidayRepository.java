// TASK: ATOM-HOLIDAY-004 / ATOM-SLOT-008
package com.scheduler.api.holiday;

import com.scheduler.api.config.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every query is tenant-filtered (ADR-004). The full per-location holiday
 * list is Redis-cached with a tenant-scoped key (ATOM-SLOT-008); mutations
 * in {@link HolidayService} evict the cache.
 */
public interface BranchHolidayRepository extends JpaRepository<BranchHoliday, UUID> {

    /**
     * Hot path for {@code SlotCalculatorService} — cached 5 min. Key is
     * tenant-scoped so no cross-tenant cache bleed is possible.
     */
    @Cacheable(value = CacheConfig.CACHE_BRANCH_HOLIDAYS,
        key = "#tenantId + ':' + #locationId")
    List<BranchHoliday> findByTenantIdAndLocationId(UUID tenantId, UUID locationId);

    Optional<BranchHoliday> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndLocationIdAndHolidayDate(
        UUID tenantId, UUID locationId, LocalDate holidayDate);

    /** Recurring annual check (AC-02): same month/day in any prior year. */
    @Query("""
        SELECT COUNT(h) > 0 FROM BranchHoliday h
        WHERE h.tenantId = :tenantId
          AND h.locationId = :locationId
          AND h.isRecurring = true
          AND MONTH(h.holidayDate) = :month
          AND DAY(h.holidayDate) = :day
        """)
    boolean existsRecurringOnMonthDay(
        @Param("tenantId") UUID tenantId,
        @Param("locationId") UUID locationId,
        @Param("month") int month,
        @Param("day") int day);

    List<BranchHoliday> findByTenantIdAndLocationIdAndHolidayDateBetween(
        UUID tenantId, UUID locationId, LocalDate from, LocalDate to);
}
