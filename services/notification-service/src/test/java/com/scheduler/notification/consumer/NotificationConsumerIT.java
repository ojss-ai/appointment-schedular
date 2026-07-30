// TASK: ATOM-KAFKA-012 (with ATOM-KAFKA-011 scenario 4 — consumer lag)
package com.scheduler.notification.consumer;

import com.scheduler.notification.domain.ProcessedEvent;
import com.scheduler.notification.dispatch.EmailDispatchService;
import com.scheduler.notification.dispatch.SmsDispatchService;
import com.scheduler.notification.repository.ProcessedEventRepository;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.scheduler.events.BookingLifecycleEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Formal verification of NFR-2.1: at-least-once Kafka delivery becomes
 * effectively-once dispatch via the processed_events dedup table +
 * MANUAL_IMMEDIATE acks. SES/Twilio are mocked at the dispatch-service seam.
 *
 * <p>Schema flows through an in-JVM mock Schema Registry (mock:// URL), so no
 * registry container is needed. The apps/api Flyway migrations are applied to
 * the Testcontainers database (schema is owned by apps/api).
 *
 * <p>Run with: mvn verify -Dgroups=idempotency
 */
@SpringBootTest
@Testcontainers
@Tag("idempotency")
class NotificationConsumerIT {

    private static final String TOPIC = "tenant.bookings.lifecycle";
    private static final String MOCK_REGISTRY = "mock://notification-it";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("scheduler_test")
        .withUsername("scheduler")
        .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(
        DockerImageName.parse("confluentinc/cp-kafka:7.6.0"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        // Schema owned by apps/api — apply its migrations here (test scope)
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.locations",
            () -> "filesystem:../../apps/api/src/main/resources/db/migration");
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("spring.kafka.consumer.properties.schema.registry.url", () -> MOCK_REGISTRY);
        r.add("spring.kafka.producer.properties.schema.registry.url", () -> MOCK_REGISTRY);
    }

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @MockBean
    private EmailDispatchService emailDispatchService;
    @MockBean
    private SmsDispatchService smsDispatchService;

    private KafkaTemplate<String, BookingLifecycleEvent> testProducer;

    @BeforeEach
    void setUp() {
        processedEventRepository.deleteAll();
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put("schema.registry.url", MOCK_REGISTRY);
        testProducer = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @Test
    @DisplayName("duplicate BookingConfirmed delivery → exactly 1 email/SMS, 1 dedup row")
    void shouldDispatchOnce_onDuplicateDelivery() {
        BookingLifecycleEvent event = confirmedEvent();
        String eventId = event.getEventId();

        testProducer.send(TOPIC, event.getBookingId(), event).join();
        testProducer.send(TOPIC, event.getBookingId(), event).join();

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(processedEventRepository.countByConsumerGroupAndMessageKey(
                NotificationConsumer.CONSUMER_GROUP, eventId))
                .as("exactly one processed_events row for eventId=%s", eventId)
                .isEqualTo(1L));

        // Grace window so a late duplicate dispatch would be caught
        await().during(3, TimeUnit.SECONDS).atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            verify(emailDispatchService, times(1)).sendConfirmation(any());
            verify(smsDispatchService, times(1)).sendConfirmation(any());
        });
    }

    @ParameterizedTest(name = "{0} deliveries → 1 side effect")
    @ValueSource(ints = {2, 5, 10})
    @DisplayName("N deliveries of the same event → exactly 1 processed_events row")
    void shouldProduceSingleSideEffect_forNDeliveries(int deliveryCount) {
        BookingLifecycleEvent event = confirmedEvent();
        for (int i = 0; i < deliveryCount; i++) {
            testProducer.send(TOPIC, event.getBookingId(), event).join();
        }

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(processedEventRepository.countByConsumerGroupAndMessageKey(
                NotificationConsumer.CONSUMER_GROUP, event.getEventId()))
                .isEqualTo(1L));

        await().during(3, TimeUnit.SECONDS).atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            verify(emailDispatchService, times(1)).sendConfirmation(any()));
    }

    @Test
    @DisplayName("100 distinct events under consumer lag → all processed exactly once")
    void shouldProcessAllEvents_underLag_withoutLossOrDuplication() {
        int eventCount = 100;
        for (int i = 0; i < eventCount; i++) {
            BookingLifecycleEvent event = confirmedEvent();
            testProducer.send(TOPIC, event.getBookingId(), event);
        }
        testProducer.flush();

        await().atMost(300, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(processedEventRepository.countByConsumerGroup(
                NotificationConsumer.CONSUMER_GROUP))
                .as("all %d events processed exactly once", eventCount)
                .isEqualTo((long) eventCount));
    }

    @Test
    @DisplayName("processed_events unique constraint rejects duplicate (group, key)")
    void dedupTable_rejectsDuplicateKey() {
        String key = UUID.randomUUID().toString();
        processedEventRepository.saveAndFlush(row(key));
        assertThatThrownBy(() -> processedEventRepository.saveAndFlush(row(key)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ProcessedEvent row(String key) {
        return ProcessedEvent.builder()
            .consumerGroup(NotificationConsumer.CONSUMER_GROUP)
            .messageKey(key)
            .topic(TOPIC)
            .partition(0)
            .offsetValue(0L)
            .build();
    }

    private BookingLifecycleEvent confirmedEvent() {
        return BookingLifecycleEvent.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setEventType("BookingConfirmed")
            .setEventVersion(1)
            .setOccurredAt(Instant.now().toString())
            .setTenantId(UUID.randomUUID().toString())
            .setBookingId(UUID.randomUUID().toString())
            .setUserId(UUID.randomUUID().toString())
            .setResourceId(UUID.randomUUID().toString())
            .setLocationId(UUID.randomUUID().toString())
            .setServiceTypeId(UUID.randomUUID().toString())
            .setSlotStart(Instant.now().toString())
            .setSlotEnd(Instant.now().plusSeconds(3600).toString())
            .setPreviousStatus("PENDING_HOLD")
            .setNewStatus("CONFIRMED")
            .setIpAddress("127.0.0.1")
            .setMetadata(Map.of())
            .build();
    }
}
