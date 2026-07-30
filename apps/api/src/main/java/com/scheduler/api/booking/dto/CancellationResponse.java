// TASK: ATOM-BOOKING-011
package com.scheduler.api.booking.dto;

import java.time.Instant;
import java.util.UUID;

/** 200 payload for POST /bookings/{id}/cancel (API-SPEC section 6). */
public record CancellationResponse(
        UUID bookingId,
        String status,
        Instant cancelledAt
) {
}
