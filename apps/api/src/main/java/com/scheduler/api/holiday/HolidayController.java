// TASK: ATOM-HOLIDAY-004
package com.scheduler.api.holiday;

import com.scheduler.api.holiday.dto.CreateHolidayRequest;
import com.scheduler.api.holiday.dto.HolidayResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Branch holiday admin API (API-SPEC section 7). Writes: ADMIN only. */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/locations/{locationId}/holidays")
@RequiredArgsConstructor
public class HolidayController {

    private final HolidayService holidayService;

    @GetMapping
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public List<HolidayResponse> list(
            @PathVariable UUID tenantId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) Integer year) {
        return holidayService.list(tenantId, locationId, year).stream()
            .map(HolidayResponse::from)
            .toList();
    }

    @PostMapping
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasAuthority('admin')")
    public ResponseEntity<HolidayResponse> create(
            @PathVariable UUID tenantId,
            @PathVariable UUID locationId,
            @Valid @RequestBody CreateHolidayRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(HolidayResponse.from(holidayService.create(tenantId, locationId, request)));
    }

    @DeleteMapping("/{holidayId}")
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasAuthority('admin')")
    public ResponseEntity<Void> delete(
            @PathVariable UUID tenantId,
            @PathVariable UUID locationId,
            @PathVariable UUID holidayId) {
        holidayService.delete(tenantId, locationId, holidayId);
        return ResponseEntity.noContent().build();
    }
}
