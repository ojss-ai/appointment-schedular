// TASK: ATOM-RESOURCE-002
package com.scheduler.api.resource.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/** Full resource representation with nested schedule and breaks. */
public record ResourceResponse(
        UUID id,
        UUID locationId,
        String name,
        String resourceType,
        String status,
        JsonNode extension,
        List<ScheduleEntry> schedule,
        List<BreakEntry> breaks
) {
}
