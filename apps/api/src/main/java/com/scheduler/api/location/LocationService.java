// TASK: ATOM-LOCATION-001
package com.scheduler.api.location;

import com.scheduler.api.common.ApiException;
import com.scheduler.api.location.dto.LocationRequest;
import com.scheduler.api.tenant.TenantScoped;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;

/**
 * CRUD for Location branches. Soft-delete only; the default list view shows
 * active branches. Timezone strings are validated against the IANA ZoneId
 * registry at write time so slot arithmetic can never hit an invalid zone.
 */
@Service
@TenantScoped
@RequiredArgsConstructor
public class LocationService {

    private final LocationRepository locationRepository;

    @Transactional(readOnly = true)
    public Page<Location> list(UUID tenantId, boolean includeInactive, Pageable pageable) {
        return includeInactive
            ? locationRepository.findByTenantId(tenantId, pageable)
            : locationRepository.findByTenantIdAndStatus(tenantId, Location.STATUS_ACTIVE, pageable);
    }

    @Transactional(readOnly = true)
    public Location get(UUID tenantId, UUID locationId) {
        return locationRepository.findByIdAndTenantId(locationId, tenantId)
            .orElseThrow(() -> ApiException.notFound("LOCATION_NOT_FOUND", "Location not found."));
    }

    @Transactional
    public Location create(UUID tenantId, LocationRequest req) {
        validateTimezone(req.timezone());
        if (locationRepository.existsByTenantIdAndNameIgnoreCase(tenantId, req.name())) {
            throw ApiException.conflict("LOCATION_ALREADY_EXISTS",
                "A location with this name already exists for the tenant.");
        }
        Location location = Location.builder()
            .tenantId(tenantId)
            .name(req.name())
            .addressLine1(req.addressLine1())
            .addressLine2(req.addressLine2())
            .city(req.city())
            .state(req.state())
            .postalCode(req.postalCode())
            .countryCode(req.countryCode())
            .latitude(req.latitude())
            .longitude(req.longitude())
            .timezone(req.timezone())
            .status(Location.STATUS_ACTIVE)
            .build();
        return locationRepository.save(location);
    }

    @Transactional
    public Location update(UUID tenantId, UUID locationId, LocationRequest req) {
        validateTimezone(req.timezone());
        Location location = get(tenantId, locationId);
        location.setName(req.name());
        location.setAddressLine1(req.addressLine1());
        location.setAddressLine2(req.addressLine2());
        location.setCity(req.city());
        location.setState(req.state());
        location.setPostalCode(req.postalCode());
        location.setCountryCode(req.countryCode());
        location.setLatitude(req.latitude());
        location.setLongitude(req.longitude());
        location.setTimezone(req.timezone());
        return locationRepository.save(location);
    }

    /** Soft-delete: status flips to inactive, preserving booking history. */
    @Transactional
    public void softDelete(UUID tenantId, UUID locationId) {
        Location location = get(tenantId, locationId);
        location.setStatus(Location.STATUS_INACTIVE);
        locationRepository.save(location);
    }

    private void validateTimezone(String timezone) {
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw ApiException.badRequest("INVALID_TIMEZONE",
                "timezone must be a valid IANA zone identifier.", "timezone");
        }
    }
}
