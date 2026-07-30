// TASK: ATOM-BOOKING-009
package com.scheduler.api.booking.dto;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/** Hold creation payload (API-SPEC section 6). */
public record CreateHoldRequest(
        @NotNull UUID resourceId,
        @NotNull UUID serviceTypeId,
        @NotNull Instant slotStart
) {
}
