// TASK: ATOM-KAFKA-008
package com.scheduler.notification.dispatch;

import io.scheduler.events.BookingLifecycleEvent;

/** SMS dispatch contract — throws on provider failure (retry/DLQ path). */
public interface SmsDispatchService {

    void sendConfirmation(BookingLifecycleEvent event);
}
