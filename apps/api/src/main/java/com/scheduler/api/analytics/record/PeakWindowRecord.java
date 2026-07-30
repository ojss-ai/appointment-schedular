// TASK: ATOM-ANALYTICS-004
package com.scheduler.api.analytics.record;

import java.time.Instant;
import java.util.UUID;

/**
 * A booking window whose demand exceeds 1.5x the 30-day mean — written to
 * {@code docs/memory/booking-patterns/peak-windows.json} nightly.
 *
 * @param dayOfWeek       ISO day of week: 1 = Monday … 7 = Sunday
 * @param hourOfDay       0–23 UTC
 * @param confidenceScore bookingCount / 30-day mean booking count
 */
public record PeakWindowRecord(
    UUID resourceId,
    int dayOfWeek,
    int hourOfDay,
    long bookingCount,
    double confidenceScore,
    Instant detectedAt
) {}
