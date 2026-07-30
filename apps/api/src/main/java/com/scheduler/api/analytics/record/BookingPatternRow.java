// TASK: ATOM-ANALYTICS-001
package com.scheduler.api.analytics.record;

import java.util.UUID;

/**
 * Native aggregate query projection — one row per
 * (resource, tenant, service type, day-of-week, hour-of-day) bucket.
 * Column aliases in the native query are quoted camelCase so Spring Data
 * maps them to these record components.
 */
public record BookingPatternRow(
    UUID resourceId,
    UUID tenantId,
    UUID serviceTypeId,
    int dayOfWeek,
    int hourOfDay,
    long bookingCount
) {}
