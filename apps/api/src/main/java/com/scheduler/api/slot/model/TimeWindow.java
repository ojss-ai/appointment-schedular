// TASK: ATOM-SLOT-005
package com.scheduler.api.slot.model;

import java.time.Duration;
import java.time.Instant;

/**
 * A half-open UTC interval [start, end). All slot arithmetic happens in UTC
 * — local shift times are converted via the location timezone before any
 * set-difference logic (DST safety).
 */
public record TimeWindow(Instant start, Instant end) {

    public TimeWindow {
        if (start == null || end == null || !end.isAfter(start)) {
            throw new IllegalArgumentException("TimeWindow requires end > start");
        }
    }

    public boolean overlaps(TimeWindow other) {
        return start.isBefore(other.end) && end.isAfter(other.start);
    }

    /** True when {@code other} lies entirely inside this window. */
    public boolean contains(TimeWindow other) {
        return !other.start.isBefore(start) && !other.end.isAfter(end);
    }

    public Duration duration() {
        return Duration.between(start, end);
    }
}
