// TASK: ATOM-ANALYTICS-001
package com.scheduler.api.analytics.record;

import java.time.Instant;
import java.util.UUID;

/**
 * Booking pattern memory record — the JSON shape written to
 * {@code docs/memory/booking-patterns/} and consumed by the AI slot
 * optimization service (ATOM-ANALYTICS-003) and the peak/anomaly
 * detectors (ATOM-ANALYTICS-004). Schema documented in
 * {@code docs/memory/booking-patterns/README.md}.
 *
 * @param dayOfWeek   ISO day of week: 1 = Monday … 7 = Sunday
 * @param hourOfDay   0–23 UTC
 * @param utilization confirmed bookings / assumed available slots (0.0–1.0)
 * @param updatedAt   ingestion timestamp of the nightly run that wrote this record
 */
public record BookingPatternRecord(
    UUID resourceId,
    UUID tenantId,
    UUID serviceTypeId,
    int dayOfWeek,
    int hourOfDay,
    long bookingCount,
    double utilization,
    Instant updatedAt
) {}
