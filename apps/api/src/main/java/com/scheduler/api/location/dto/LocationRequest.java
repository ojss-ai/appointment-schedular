// TASK: ATOM-LOCATION-001
package com.scheduler.api.location.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/** Create/update payload for a Location branch (API-SPEC section 2). */
public record LocationRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String addressLine1,
        @Size(max = 255) String addressLine2,
        @NotBlank @Size(max = 100) String city,
        @Size(max = 100) String state,
        @NotBlank @Size(max = 20) String postalCode,
        @NotBlank @Pattern(regexp = "[A-Z]{2}") String countryCode,
        BigDecimal latitude,
        BigDecimal longitude,
        @NotBlank @Size(max = 50) String timezone
) {
}
