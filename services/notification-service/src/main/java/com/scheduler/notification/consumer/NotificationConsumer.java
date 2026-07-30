// TASK: ATOM-KAFKA-008
package com.scheduler.notification.consumer;

import com.scheduler.notification.dispatch.EmailDispatchService;
import com.scheduler.notification.dispatch.SmsDispatchService;
import com.scheduler.notification.domain.ProcessedEvent;
import com.scheduler.notification.repository.ProcessedEventRepository;
import io.scheduler.events.BookingLifecycleEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes booking lifecycle events and dispatches notifications.
 *
 * <p>Idempotency (NFR-2.1, docs/KAFKA-SPEC.md section 5): the
 * {@code processed_events} check is the FIRST statement; the dedup row is
 * saved in the same transaction as the dispatch and the offset is committed
 * manually as the LAST statement. A crash before acknowledge causes a safe
 * redelivery that the check turns into a no-op.
 *
 * <p>The dedup key is the payload {@code eventId} — the Kafka record key is
 * the bookingId (ordering key, docs/KAFKA-SPEC.md section 1) and is shared by
 * every lifecycle event of one booking, so it would wrongly collapse
 * BookingHeld/BookingConfirmed/BookingCancelled into one.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationConsumer {

    public static final String CONSUMER_GROUP = "notification-consumers";

    private final ProcessedEventRepository processedEventRepository;
    private final EmailDispatchService emailDispatchService;
    private final SmsDispatchService smsDispatchService;

    @KafkaListener(
        topics = "tenant.bookings.lifecycle",
        groupId = CONSUMER_GROUP,
        containerFactory = "bookingEventListenerContainerFactory"
    )
    @Transactional
    public void onBookingEvent(
            ConsumerRecord<String, BookingLifecycleEvent> record,
            Acknowledgment acknowledgment) {

        BookingLifecycleEvent event = record.value();
        String messageKey = event.getEventId();

        // 1. Idempotency check — always first (NFR-2.1)
        if (processedEventRepository.existsByConsumerGroupAndMessageKey(CONSUMER_GROUP, messageKey)) {
            log.info("Duplicate event skipped consumerGroup={} eventId={}", CONSUMER_GROUP, messageKey);
            acknowledgment.acknowledge();
            return;
        }

        log.info("Processing eventType={} bookingId={} tenantId={}",
            event.getEventType(), event.getBookingId(), event.getTenantId());

        // 2. Dispatch — provider failures propagate to the error handler
        //    (retry x3, then DLQ) without committing the offset.
        switch (event.getEventType()) {
            case "BookingConfirmed" -> {
                emailDispatchService.sendConfirmation(event);
                smsDispatchService.sendConfirmation(event);
            }
            case "BookingCancelled" -> emailDispatchService.sendCancellation(event);
            default -> log.debug("No notification for eventType={}", event.getEventType());
        }

        // 3. Dedup record — same transaction, before the offset commit
        processedEventRepository.save(ProcessedEvent.builder()
            .consumerGroup(CONSUMER_GROUP)
            .messageKey(messageKey)
            .topic(record.topic())
            .partition(record.partition())
            .offsetValue(record.offset())
            .build());

        // 4. Manual offset commit — last statement (AckMode.MANUAL_IMMEDIATE)
        acknowledgment.acknowledge();
        log.info("Notification handled and offset committed eventId={}", messageKey);
    }
}
