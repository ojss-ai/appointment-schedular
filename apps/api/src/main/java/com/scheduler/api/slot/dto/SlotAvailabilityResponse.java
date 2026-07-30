// TASK: ATOM-SLOT-007
package com.scheduler.api.slot.dto;

import com.scheduler.api.slot.model.AvailableSlot;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Slot availability payload (API-SPEC section 5). {@code slots} preserves
 * date order for range queries (LinkedHashMap upstream).
 */
public record SlotAvailabilityResponse(
        UUID resourceId,
        UUID serviceTypeId,
        UUID locationId,
        String timezone,
        Map<LocalDate, List<AvailableSlot>> slots,
        Instant computedAt
) {
}
