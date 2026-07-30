// TASK: ATOM-ANALYTICS-004
package com.scheduler.api.analytics.record;

import java.time.Instant;
import java.util.UUID;

/**
 * A resource whose booking volume dropped more than 80% versus its 7-day
 * average — written to {@code docs/memory/booking-patterns/anomalies.json}
 * and counted by the {@code scheduling_booking_anomalies_detected} gauge.
 *
 * @param dropPercent 0.0–100.0
 * @param sevenDayAvg average daily confirmed bookings over the 7-day baseline
 * @param yesterday   confirmed bookings on the most recent ingested day
 */
public record AnomalyRecord(
    UUID resourceId,
    UUID tenantId,
    double dropPercent,
    double sevenDayAvg,
    long yesterday,
    Instant detectedAt
) {}
