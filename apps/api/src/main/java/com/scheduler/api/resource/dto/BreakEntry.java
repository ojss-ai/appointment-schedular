// TASK: ATOM-RESOURCE-002
package com.scheduler.api.resource.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;

/** One weekly break window (local time). dayOfWeek: 0=Sunday .. 6=Saturday. */
public record BreakEntry(
        @Min(0) @Max(6) int dayOfWeek,
        @NotNull LocalTime breakStart,
        @NotNull LocalTime breakEnd,
        @Size(max = 100) String label
) {
}
