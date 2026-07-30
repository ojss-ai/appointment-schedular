// TASK: P1-T08
package com.scheduler.api.notification;

/** Outcome of a dispatch attempt. Failures are values, never exceptions. */
public record DispatchResult(boolean success, String channel, String errorMessage) {

    public static DispatchResult success(String channel) {
        return new DispatchResult(true, channel, null);
    }

    public static DispatchResult failure(String channel, String errorMessage) {
        return new DispatchResult(false, channel, errorMessage);
    }
}
