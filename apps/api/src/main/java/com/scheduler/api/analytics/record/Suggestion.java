// TASK: ATOM-ANALYTICS-003
package com.scheduler.api.analytics.record;

import java.util.UUID;

/**
 * One AI-generated (or heuristic-fallback) slot optimization suggestion.
 *
 * @param suggestion hint code: {@code EXTEND_HOURS}, {@code REDUCE_BUFFER}, …
 * @param details    1–2 sentence human-readable explanation
 * @param confidence 0.0–1.0
 * @param dataPoints number of pattern records behind the suggestion
 */
public record Suggestion(
    UUID resourceId,
    String suggestion,
    String details,
    double confidence,
    int dataPoints
) {}
