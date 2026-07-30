// TASK: P1-T09
package com.scheduler.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RequestOtpRequest(
    @NotBlank @Size(max = 255) String identifier,
    @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z0-9-]{3,100}") String tenantSlug
) {
}
