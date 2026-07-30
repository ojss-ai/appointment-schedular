// TASK: ATOM-SLOT-007
package com.scheduler.api.slot;

import com.scheduler.api.common.ApiException;
import com.scheduler.api.location.Location;
import com.scheduler.api.location.LocationRepository;
import com.scheduler.api.resource.ResourceRepository;
import com.scheduler.api.servicetype.ServiceTypeRepository;
import com.scheduler.api.slot.dto.SlotAvailabilityResponse;
import com.scheduler.api.slot.model.AvailableSlot;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Performance-critical availability endpoint (NFR-1.2: p99 < 300ms for a
 * single-day query). Thin layer: validate, check tenant ownership, delegate
 * to {@link SlotCalculatorService}. Range queries capped at 7 days.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/slots")
@RequiredArgsConstructor
public class SlotController {

    private static final int MAX_RANGE_DAYS = 6; // date + 6 = 7-day window

    private final SlotCalculatorService slotCalculatorService;
    private final ResourceRepository resourceRepository;
    private final ServiceTypeRepository serviceTypeRepository;
    private final LocationRepository locationRepository;

    @GetMapping
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public SlotAvailabilityResponse getSlots(
            @PathVariable UUID tenantId,
            @RequestParam UUID locationId,
            @RequestParam UUID resourceId,
            @RequestParam UUID serviceTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate rangeEndDate) {

        LocalDate end = rangeEndDate == null ? date : rangeEndDate;
        if (date.isBefore(LocalDate.now())) {
            throw ApiException.unprocessable("DATE_IN_PAST", "date must not be in the past.");
        }
        if (end.isBefore(date)) {
            throw ApiException.unprocessable("DATE_RANGE_TOO_LARGE",
                "rangeEndDate must not be before date.");
        }
        if (ChronoUnit.DAYS.between(date, end) > MAX_RANGE_DAYS) {
            throw ApiException.unprocessable("DATE_RANGE_TOO_LARGE",
                "Date range must not exceed 7 days.");
        }

        // Tenant ownership checks — cross-tenant IDs read as absent (404).
        resourceRepository.findByIdAndTenantId(resourceId, tenantId)
            .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "Resource not found."));
        serviceTypeRepository.findByIdAndTenantId(serviceTypeId, tenantId)
            .orElseThrow(() ->
                ApiException.notFound("SERVICE_TYPE_NOT_FOUND", "Service type not found."));
        Location location = locationRepository.findByIdAndTenantId(locationId, tenantId)
            .orElseThrow(() -> ApiException.notFound("LOCATION_NOT_FOUND", "Location not found."));

        Map<LocalDate, List<AvailableSlot>> slots = new LinkedHashMap<>();
        for (LocalDate d = date; !d.isAfter(end); d = d.plusDays(1)) {
            slots.put(d, slotCalculatorService.computeAvailableSlots(
                resourceId, serviceTypeId, locationId, d, tenantId));
        }

        return new SlotAvailabilityResponse(resourceId, serviceTypeId, locationId,
            location.getTimezone(), slots, Instant.now());
    }
}
