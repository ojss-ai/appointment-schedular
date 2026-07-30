// TASK: ATOM-BOOKING-009..011
package com.scheduler.api.booking.dto;

import com.scheduler.api.booking.Booking;

import java.time.Instant;
import java.util.UUID;

/** Full booking detail (GET endpoints, API-SPEC section 6). */
public record BookingResponse(
        UUID id,
        UUID locationId,
        UUID resourceId,
        UUID serviceTypeId,
        UUID userId,
        String status,
        Instant slotStart,
        Instant slotEnd,
        Instant holdExpiresAt,
        String confirmationCode,
        Instant cancelledAt,
        String cancellationReason,
        Instant createdAt
) {
    public static BookingResponse from(Booking b) {
        return new BookingResponse(b.getId(), b.getLocationId(), b.getResourceId(),
            b.getServiceTypeId(), b.getUserId(), b.getStatus(), b.getSlotStart(), b.getSlotEnd(),
            b.getHoldExpiresAt(), b.getConfirmationCode(), b.getCancelledAt(),
            b.getCancellationReason(), b.getCreatedAt());
    }
}
