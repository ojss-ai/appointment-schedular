// TASK: ATOM-BOOKING-009
package com.scheduler.api.booking.dto;

import java.time.Instant;
import java.util.UUID;

/** 201 payload for POST /bookings/hold (API-SPEC section 6). */
public record HoldResponse(
        UUID bookingId,
        String status,
        Instant slotStart,
        Instant slotEnd,
        Instant holdExpiresAt
) {
}
