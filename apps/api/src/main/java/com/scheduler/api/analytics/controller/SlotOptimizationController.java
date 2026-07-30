// TASK: ATOM-ANALYTICS-003
package com.scheduler.api.analytics.controller;

import com.scheduler.api.analytics.record.SlotOptimizationResponse;
import com.scheduler.api.analytics.service.SlotOptimizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Tenant analytics — AI slot optimization suggestions (API-SPEC section 8).
 * Admin-only: tenant guard plus the {@code admin} authority (codebase role
 * convention — lowercase authorities, see LocationController).
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/analytics")
@RequiredArgsConstructor
public class SlotOptimizationController {

    private final SlotOptimizationService optimizationService;

    /** GET /tenants/{tenantId}/analytics/slot-optimization — ADMIN only. */
    @GetMapping("/slot-optimization")
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasAuthority('admin')")
    public ResponseEntity<SlotOptimizationResponse> getOptimizationSuggestions(
            @PathVariable UUID tenantId) {
        return ResponseEntity.ok(optimizationService.getSuggestions(tenantId));
    }
}
