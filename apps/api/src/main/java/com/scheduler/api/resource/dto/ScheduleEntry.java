// TASK: ATOM-RESOURCE-002
package com.scheduler.api.resource.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.LocalTime;

/** One weekly shift window (local time). dayOfWeek: 0=Sunday .. 6=Saturday. */
public record ScheduleEntry(
        @Min(0) @Max(6) int dayOfWeek,
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime
) {
}
