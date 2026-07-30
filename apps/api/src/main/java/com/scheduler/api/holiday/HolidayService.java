// TASK: ATOM-HOLIDAY-004 / ATOM-SLOT-008
package com.scheduler.api.holiday;

import com.scheduler.api.common.ApiException;
import com.scheduler.api.config.CacheConfig;
import com.scheduler.api.holiday.dto.CreateHolidayRequest;
import com.scheduler.api.location.LocationRepository;
import com.scheduler.api.tenant.TenantScoped;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Branch holiday management. A holiday date yields an empty operating matrix
 * in {@code SlotCalculatorService} (ATOM-SLOT-005 AC-03). Mutations evict
 * the tenant-scoped Redis holiday cache (ATOM-SLOT-008).
 */
@Service
@TenantScoped
@RequiredArgsConstructor
public class HolidayService {

    private final BranchHolidayRepository holidayRepository;
    private final LocationRepository locationRepository;

    @Transactional(readOnly = true)
    public List<BranchHoliday> list(UUID tenantId, UUID locationId, Integer year) {
        requireLocation(tenantId, locationId);
        if (year == null) {
            return holidayRepository.findByTenantIdAndLocationId(tenantId, locationId);
        }
        return holidayRepository.findByTenantIdAndLocationIdAndHolidayDateBetween(
            tenantId, locationId, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_BRANCH_HOLIDAYS, allEntries = true)
    public BranchHoliday create(UUID tenantId, UUID locationId, CreateHolidayRequest req) {
        requireLocation(tenantId, locationId);
        if (!req.holidayDate().isAfter(LocalDate.now())) {
            throw ApiException.unprocessable("PAST_HOLIDAY_DATE",
                "holidayDate must be in the future.");
        }
        if (holidayRepository.existsByTenantIdAndLocationIdAndHolidayDate(
                tenantId, locationId, req.holidayDate())) {
            throw ApiException.conflict("HOLIDAY_ALREADY_EXISTS",
                "A holiday already exists for this location and date.");
        }
        return holidayRepository.save(BranchHoliday.builder()
            .tenantId(tenantId)
            .locationId(locationId)
            .holidayDate(req.holidayDate())
            .name(req.name())
            .isRecurring(req.isRecurring())
            .build());
    }

    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_BRANCH_HOLIDAYS, allEntries = true)
    public void delete(UUID tenantId, UUID locationId, UUID holidayId) {
        BranchHoliday holiday = holidayRepository.findByIdAndTenantId(holidayId, tenantId)
            .filter(h -> h.getLocationId().equals(locationId))
            .orElseThrow(() -> ApiException.notFound("HOLIDAY_NOT_FOUND", "Holiday not found."));
        holidayRepository.delete(holiday);
    }

    private void requireLocation(UUID tenantId, UUID locationId) {
        locationRepository.findByIdAndTenantId(locationId, tenantId)
            .orElseThrow(() -> ApiException.notFound("LOCATION_NOT_FOUND", "Location not found."));
    }
}
