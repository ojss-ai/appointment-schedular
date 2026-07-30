// TASK: ATOM-BOOKING-011
package com.scheduler.api.booking.dto;

import jakarta.validation.constraints.Size;

/** Cancellation payload (API-SPEC section 6). */
public record CancelBookingRequest(@Size(max = 2000) String reason) {
}
