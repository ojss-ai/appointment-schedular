// TASK: ATOM-ANALYTICS-003
package com.scheduler.api.analytics.record;

import java.util.List;

/**
 * Response body for
 * {@code GET /api/v1/tenants/{tenantId}/analytics/slot-optimization}.
 *
 * @param message {@code null} on success;
 *                {@code "Insufficient data (< 7 days)"} when the tenant has
 *                fewer than 7 distinct ingested dates of pattern data
 */
public record SlotOptimizationResponse(
    List<Suggestion> suggestions,
    String message
) {}
