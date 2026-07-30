// TASK: ATOM-SERVICE-003
package com.scheduler.api.servicetype.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Create/update payload for a service type (API-SPEC section 4). */
public record ServiceTypeRequest(
        @NotBlank @Size(max = 255) String name,
        String description,
        @Min(5) @Max(480) int durationMinutes,
        @Min(0) @Max(120) int bufferBeforeMin,
        @Min(0) @Max(120) int bufferAfterMin,
        List<String> allowedResourceTypes,
        JsonNode intakeSchema
) {
}
