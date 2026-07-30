// TASK: ATOM-RESOURCE-002
package com.scheduler.api.resource.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * Registration payload (API-SPEC section 3). {@code extension} is stored
 * verbatim in the JSONB column — never interpreted by core logic (ADR-005).
 */
public record CreateResourceRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 100) String resourceType,
        Map<String, Object> extension,
        @Valid List<ScheduleEntry> schedule,
        @Valid List<BreakEntry> breaks
) {
}
