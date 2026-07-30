// TASK: ATOM-ANALYTICS-003
package com.scheduler.api.analytics.record;

import java.util.UUID;

/**
 * Internal pre-filter hint emitted by the utilization heuristics — the
 * bounded input handed to the Claude API (or returned as-is in the
 * heuristic-only fallback).
 *
 * @param hintCode   e.g. {@code EXTEND_HOURS}, {@code REDUCE_BUFFER}
 * @param dataPoints number of pattern records supporting this hint
 */
public record OptimizationHint(
    UUID resourceId,
    String hintCode,
    String description,
    int dataPoints
) {}
