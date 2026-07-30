// TASK: P1-T09
package com.scheduler.api.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VerifyOtpRequest(
    @NotBlank @Size(max = 255) String identifier,
    @NotBlank @Size(max = 100) String tenantSlug,
    @NotBlank @Size(min = 6, max = 6) String otp
) {
}
