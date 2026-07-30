// TASK: ATOM-HOLIDAY-004
package com.scheduler.api.holiday.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Create payload for a branch holiday (API-SPEC section 7). */
public record CreateHolidayRequest(
        @NotNull LocalDate holidayDate,
        @Size(max = 255) String name,
        boolean isRecurring
) {
}
