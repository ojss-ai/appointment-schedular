// TASK: ATOM-KAFKA-008
package com.scheduler.notification.dispatch;

import io.scheduler.events.BookingLifecycleEvent;

/**
 * Email dispatch contract. Implementations MUST throw on provider failure so
 * the Kafka error handler can retry and eventually route the record to the
 * DLQ (docs/KAFKA-SPEC.md section 6) — unlike the fire-and-forget OTP
 * adapters in apps/api, a swallowed failure here would silently lose the
 * notification while committing the offset.
 */
public interface EmailDispatchService {

    void sendConfirmation(BookingLifecycleEvent event);

    void sendCancellation(BookingLifecycleEvent event);
}
