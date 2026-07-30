// TASK: ATOM-SERVICE-003
package com.scheduler.api.servicetype.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * Service type representation. Always carries the full {@code intakeSchema}
 * so the web form builder can render the dynamic intake form (AC-04).
 */
public record ServiceTypeResponse(
        UUID id,
        String name,
        String description,
        int durationMinutes,
        int bufferBeforeMin,
        int bufferAfterMin,
        List<String> allowedResourceTypes,
        JsonNode intakeSchema,
        String status
) {
}
