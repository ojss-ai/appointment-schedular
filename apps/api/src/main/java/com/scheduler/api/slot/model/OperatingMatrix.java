// TASK: ATOM-SLOT-005
package com.scheduler.api.slot.model;

import java.util.List;

/**
 * Computed time-block grid for one resource/date: base shifts − breaks −
 * holidays (Glossary: Operating Matrix). Never persisted (ADR-001).
 */
public record OperatingMatrix(List<TimeWindow> windows) {

    public boolean isEmpty() {
        return windows == null || windows.isEmpty();
    }
}
