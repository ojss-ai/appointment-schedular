// TASK: ATOM-SLOT-006
package com.scheduler.api.slot.model;

import java.time.Instant;

/**
 * One bookable candidate slot (UTC). Entirely transient — computed on
 * demand and never written to any table (ADR-001).
 */
public record AvailableSlot(
        Instant startTime,
        Instant endTime,
        int durationMinutes
) {
}
