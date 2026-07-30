// TASK: ATOM-KAFKA-010
package com.scheduler.audit.config;

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
 * Same wiring contract as notification-service: MANUAL_IMMEDIATE acks for
 * the idempotency pattern (NFR-2.1) and 3-retry-then-DLQ error handling
 * (docs/KAFKA-SPEC.md section 6).
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
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (record, ex) -> {
                log.error("Dead-lettering record topic={} partition={} offset={}: {}",
                    record.topic(), record.partition(), record.offset(), ex.getMessage());
                return new TopicPartition(record.topic() + ".DLQ", -1);
            });
        factory.setCommonErrorHandler(
            new DefaultErrorHandler(recoverer, new FixedBackOff(RETRY_INTERVAL_MS, MAX_RETRIES)));

        return factory;
    }
}
