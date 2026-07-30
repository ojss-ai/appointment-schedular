// TASK: ATOM-KAFKA-008
package com.scheduler.notification.config;

import io.scheduler.events.BookingLifecycleEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Listener container wiring per docs/KAFKA-SPEC.md sections 5–7:
 * <ul>
 *   <li>{@code AckMode.MANUAL_IMMEDIATE} — the consumer commits the offset
 *       only after the processed_events row is saved (NFR-2.1).</li>
 *   <li>3 retries with 1s backoff, then the record is published to
 *       {@code <topic>.DLQ} by the {@link DeadLetterPublishingRecoverer} —
 *       never auto-retried from there.</li>
 * </ul>
 */
@Configuration
@Slf4j
public class KafkaConsumerConfig {

    static final long RETRY_INTERVAL_MS = 1000L;
    static final long MAX_RETRIES = 3L;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BookingLifecycleEvent>
            bookingEventListenerContainerFactory(
                ConsumerFactory<Object, Object> consumerFactory,
                KafkaTemplate<Object, Object> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, BookingLifecycleEvent> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        // Boot auto-configures ConsumerFactory<Object, Object>; the Avro
        // deserializer (specific.avro.reader=true) yields the typed record.
        @SuppressWarnings("unchecked")
        ConsumerFactory<String, BookingLifecycleEvent> typedFactory =
            (ConsumerFactory<String, BookingLifecycleEvent>) (ConsumerFactory<?, ?>) consumerFactory;
        factory.setConsumerFactory(typedFactory);

        // Manual offset commit — required for the idempotency pattern (NFR-2.1)
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // KAFKA-SPEC section 6: 3 attempts, then dead-letter to <topic>.DLQ
        // (negative partition lets the broker pick the DLQ partition).
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, ex) -> {
                log.error("Dead-lettering record topic={} partition={} offset={} after {} attempts: {}",
                    record.topic(), record.partition(), record.offset(), MAX_RETRIES + 1,
                    ex.getMessage());
                return new TopicPartition(record.topic() + ".DLQ", -1);
            });
        factory.setCommonErrorHandler(
            new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES)));

        return factory;
    }
}
