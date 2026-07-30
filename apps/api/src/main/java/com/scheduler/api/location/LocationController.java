// TASK: ATOM-LOCATION-001
package com.scheduler.api.location;

import com.scheduler.api.common.PageResponse;
import com.scheduler.api.location.dto.LocationRequest;
import com.scheduler.api.location.dto.LocationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Location branch admin API (API-SPEC section 2). Reads: any authenticated
 * tenant member. Writes: ADMIN role only. Tenant guard on every method.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/locations")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    @GetMapping
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public PageResponse<LocationResponse> list(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(
            locationService.list(tenantId, includeInactive, PageRequest.of(page, size)),
            LocationResponse::from);
    }

    @GetMapping("/{locationId}")
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public LocationResponse get(@PathVariable UUID tenantId, @PathVariable UUID locationId) {
        return LocationResponse.from(locationService.get(tenantId, locationId));
    }

    @PostMapping
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasAuthority('admin')")
    public ResponseEntity<LocationResponse> create(
            @PathVariable UUID tenantId,
            @Valid @RequestBody LocationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(LocationResponse.from(locationService.create(tenantId, request)));
    }

    @PutMapping("/{locationId}")
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasAuthority('admin')")
    public LocationResponse update(
            @PathVariable UUID tenantId,
            @PathVariable UUID locationId,
            @Valid @RequestBody LocationRequest request) {
        return LocationResponse.from(locationService.update(tenantId, locationId, request));
    }

    @DeleteMapping("/{locationId}")
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasAuthority('admin')")
    public ResponseEntity<Void> softDelete(
            @PathVariable UUID tenantId,
            @PathVariable UUID locationId) {
        locationService.softDelete(tenantId, locationId);
        return ResponseEntity.noContent().build();
    }
}
