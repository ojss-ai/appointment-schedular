// TASK: ATOM-BOOKING-009 / ATOM-BOOKING-010 / ATOM-BOOKING-011
package com.scheduler.api.booking;

import com.scheduler.api.booking.dto.BookingResponse;
import com.scheduler.api.booking.dto.CancelBookingRequest;
import com.scheduler.api.booking.dto.CancellationResponse;
import com.scheduler.api.booking.dto.ConfirmBookingRequest;
import com.scheduler.api.booking.dto.ConfirmationResponse;
import com.scheduler.api.booking.dto.CreateHoldRequest;
import com.scheduler.api.booking.dto.HoldResponse;
import com.scheduler.api.common.PageResponse;
import com.scheduler.api.security.TenantContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Booking lifecycle API (API-SPEC section 6). The acting user always comes
 * from the validated JWT ({@link TenantContext}) — never from the payload.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    /** POST /bookings/hold — reserve a slot as PENDING_HOLD for 10 minutes. */
    @PostMapping("/hold")
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public ResponseEntity<HoldResponse> createHold(
            @PathVariable UUID tenantId,
            @Valid @RequestBody CreateHoldRequest request) {
        HoldResponse response =
            bookingService.createHold(request, tenantId, TenantContext.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** POST /bookings/{id}/confirm — PENDING_HOLD → CONFIRMED. */
    @PostMapping("/{bookingId}/confirm")
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public ConfirmationResponse confirm(
            @PathVariable UUID tenantId,
            @PathVariable UUID bookingId,
            @RequestBody(required = false) ConfirmBookingRequest request) {
        return bookingService.confirmBooking(bookingId,
            request == null ? null : request.extensionData(),
            tenantId, TenantContext.getUserId());
    }

    /** POST /bookings/{id}/cancel — CONFIRMED → CANCELLED (owner or admin). */
    @PostMapping("/{bookingId}/cancel")
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public CancellationResponse cancel(
            @PathVariable UUID tenantId,
            @PathVariable UUID bookingId,
            @RequestBody(required = false) @Valid CancelBookingRequest request) {
        return bookingService.cancelBooking(bookingId,
            request == null ? null : request.reason(),
            TenantContext.getUserId(), TenantContext.getRoles(), tenantId);
    }

    /** GET /bookings — own bookings, or all tenant bookings for ADMIN. */
    @GetMapping
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public PageResponse<BookingResponse> list(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID resourceId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateFrom,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant dateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(
            bookingService.listBookings(tenantId, TenantContext.getUserId(),
                TenantContext.getRoles(), status, resourceId, locationId, dateFrom, dateTo,
                PageRequest.of(page, size)),
            BookingResponse::from);
    }

    /** GET /bookings/{id} — owner or ADMIN. */
    @GetMapping("/{bookingId}")
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public BookingResponse get(
            @PathVariable UUID tenantId,
            @PathVariable UUID bookingId) {
        return BookingResponse.from(bookingService.getBooking(bookingId, tenantId,
            TenantContext.getUserId(), TenantContext.getRoles()));
    }
}
