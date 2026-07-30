// TASK: ATOM-BOOKING-010
package com.scheduler.api.booking;

import com.scheduler.api.common.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Year;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Confirmation codes: {@code {TENANT_PREFIX}-{YYYY}-{5-digit-seq}}, unique
 * per tenant per calendar year (backed by the partial unique index in
 * V011). Prefix = uppercase initials of the tenant slug segments, e.g.
 * slug {@code metro-branch} → {@code MB-2026-00421}.
 */
@Component
@RequiredArgsConstructor
public class ConfirmationCodeGenerator {

    private static final int MAX_ATTEMPTS = 10;

    private final BookingRepository bookingRepository;

    public String generate(UUID tenantId, String tenantSlug) {
        String prefix = prefixOf(tenantSlug) + "-" + Year.now().getValue() + "-";
        long next = bookingRepository
            .countByTenantIdAndConfirmationCodeStartingWith(tenantId, prefix) + 1;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = prefix + String.format("%05d", next + attempt);
            if (!bookingRepository.existsByTenantIdAndConfirmationCode(tenantId, candidate)) {
                return candidate;
            }
        }
        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
            "Could not allocate a confirmation code.");
    }

    static String prefixOf(String slug) {
        String[] segments = slug.split("-");
        if (segments.length >= 2) {
            return Arrays.stream(segments)
                .filter(s -> !s.isBlank())
                .map(s -> s.substring(0, 1))
                .collect(Collectors.joining())
                .toUpperCase(Locale.ROOT);
        }
        return slug.substring(0, Math.min(2, slug.length())).toUpperCase(Locale.ROOT);
    }
}
