// TASK: ATOM-BOOKING-010
package com.scheduler.api.booking.dto;

import java.time.Instant;
import java.util.UUID;

/** 200 payload for POST /bookings/{id}/confirm (API-SPEC section 6). */
public record ConfirmationResponse(
        UUID bookingId,
        String status,
        String confirmationCode,
        Instant slotStart,
        Instant slotEnd
) {
}
