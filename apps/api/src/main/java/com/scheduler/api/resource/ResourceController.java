// TASK: ATOM-RESOURCE-002
package com.scheduler.api.resource;

import com.scheduler.api.common.PageResponse;
import com.scheduler.api.resource.dto.BreakEntry;
import com.scheduler.api.resource.dto.CreateResourceRequest;
import com.scheduler.api.resource.dto.ResourceResponse;
import com.scheduler.api.resource.dto.ScheduleEntry;
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

import java.util.List;
import java.util.UUID;

/**
 * Resource registration + schedule/break admin API (API-SPEC section 3).
 * Reads: any authenticated tenant member. Writes: ADMIN role only.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/locations/{locationId}/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceService resourceService;

    @GetMapping
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public PageResponse<ResourceSummary> list(
            @PathVariable UUID tenantId,
            @PathVariable UUID locationId,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(
            resourceService.list(tenantId, locationId, includeInactive, PageRequest.of(page, size)),
            r -> new ResourceSummary(r.getId(), r.getName(), r.getResourceType(), r.getStatus()));
    }

    @GetMapping("/{resourceId}")
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public ResourceResponse get(
            @PathVariable UUID tenantId,
            @PathVariable UUID locationId,
            @PathVariable UUID resourceId) {
        return resourceService.get(tenantId, resourceId);
    }

    @PostMapping
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasAuthority('admin')")
    public ResponseEntity<ResourceResponse> create(
            @PathVariable UUID tenantId,
            @PathVariable UUID locationId,
            @Valid @RequestBody CreateResourceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(resourceService.create(tenantId, locationId, request));
    }

    @PutMapping("/{resourceId}/schedule")
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasAuthority('admin')")
    public List<ScheduleEntry> replaceSchedule(
            @PathVariable UUID tenantId,
            @PathVariable UUID locationId,
            @PathVariable UUID resourceId,
            @Valid @RequestBody List<ScheduleEntry> entries) {
        return resourceService.replaceSchedule(tenantId, resourceId, entries);
    }

    @PutMapping("/{resourceId}/breaks")
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasAuthority('admin')")
    public List<BreakEntry> replaceBreaks(
            @PathVariable UUID tenantId,
            @PathVariable UUID locationId,
            @PathVariable UUID resourceId,
            @Valid @RequestBody List<BreakEntry> entries) {
        return resourceService.replaceBreaks(tenantId, resourceId, entries);
    }

    @DeleteMapping("/{resourceId}")
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasAuthority('admin')")
    public ResponseEntity<Void> softDelete(
            @PathVariable UUID tenantId,
            @PathVariable UUID locationId,
            @PathVariable UUID resourceId) {
        resourceService.softDelete(tenantId, resourceId);
        return ResponseEntity.noContent().build();
    }

    /** Lightweight list projection. */
    public record ResourceSummary(UUID id, String name, String resourceType, String status) {
    }
}
