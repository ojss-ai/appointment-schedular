// TASK: ATOM-SERVICE-003
package com.scheduler.api.servicetype;

import com.scheduler.api.common.PageResponse;
import com.scheduler.api.servicetype.dto.ServiceTypeRequest;
import com.scheduler.api.servicetype.dto.ServiceTypeResponse;
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
 * Service type admin API (API-SPEC section 4). Reads: any authenticated
 * tenant member. Writes: ADMIN role only.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/service-types")
@RequiredArgsConstructor
public class ServiceTypeController {

    private final ServiceTypeService serviceTypeService;

    @GetMapping
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public PageResponse<ServiceTypeResponse> list(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(
            serviceTypeService.list(tenantId, includeInactive, PageRequest.of(page, size)),
            r -> r);
    }

    @GetMapping("/{serviceTypeId}")
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public ServiceTypeResponse get(
            @PathVariable UUID tenantId,
            @PathVariable UUID serviceTypeId) {
        return serviceTypeService.get(tenantId, serviceTypeId);
    }

    @PostMapping
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasAuthority('admin')")
    public ResponseEntity<ServiceTypeResponse> create(
            @PathVariable UUID tenantId,
            @Valid @RequestBody ServiceTypeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(serviceTypeService.create(tenantId, request));
    }

    @PutMapping("/{serviceTypeId}")
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasAuthority('admin')")
    public ServiceTypeResponse update(
            @PathVariable UUID tenantId,
            @PathVariable UUID serviceTypeId,
            @Valid @RequestBody ServiceTypeRequest request) {
        return serviceTypeService.update(tenantId, serviceTypeId, request);
    }

    @DeleteMapping("/{serviceTypeId}")
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasAuthority('admin')")
    public ResponseEntity<Void> softDelete(
            @PathVariable UUID tenantId,
            @PathVariable UUID serviceTypeId) {
        serviceTypeService.softDelete(tenantId, serviceTypeId);
        return ResponseEntity.noContent().build();
    }
}
