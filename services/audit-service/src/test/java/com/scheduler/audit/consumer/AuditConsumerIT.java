// TASK: ATOM-KAFKA-012 / ATOM-KAFKA-010
package com.scheduler.audit.consumer;

import com.scheduler.audit.domain.AuditLogEntry;
import com.scheduler.audit.repository.AuditLogRepository;
import com.scheduler.audit.repository.ProcessedEventRepository;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import io.scheduler.events.BookingLifecycleEvent;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * NFR-2.1 for the HIPAA ledger: duplicate deliveries of one lifecycle event
 * yield exactly one audit_log row, with every HIPAA field explicitly mapped.
 *
 * <p>The IT datasource runs as the Testcontainers superuser so it can apply
 * the apps/api Flyway migrations and SELECT audit_log for verification; the
 * audit_writer INSERT-only enforcement itself is verified at the DB layer in
 * apps/api EventMigrationsIT.
 *
 * <p>Run with: mvn verify -Dgroups=idempotency
 */
@SpringBootTest
@Testcontainers
@Tag("idempotency")
class AuditConsumerIT {

    private static final String TOPIC = "tenant.bookings.lifecycle";
    private static final String MOCK_REGISTRY = "mock://audit-it";

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
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.locations",
            () -> "filesystem:../../apps/api/src/main/resources/db/migration");
        r.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        r.add("spring.kafka.consumer.properties.schema.registry.url", () -> MOCK_REGISTRY);
        r.add("spring.kafka.producer.properties.schema.registry.url", () -> MOCK_REGISTRY);
    }

    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;

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
    @DisplayName("duplicate delivery → exactly 1 audit row with all HIPAA fields")
    void shouldWriteOneAuditRow_onDuplicateDelivery() {
        UUID tenantId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-07-20T12:00:00Z");
        BookingLifecycleEvent event = event(tenantId, bookingId, userId, resourceId, occurredAt);

        testProducer.send(TOPIC, bookingId.toString(), event).join();
        testProducer.send(TOPIC, bookingId.toString(), event).join();

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(processedEventRepository.countByConsumerGroupAndMessageKey(
                AuditConsumer.CONSUMER_GROUP, event.getEventId())).isEqualTo(1L));

        await().during(3, TimeUnit.SECONDS).atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(auditLogRepository.countByTenantIdAndBookingId(tenantId, bookingId))
                .as("exactly one audit row despite duplicate delivery")
                .isEqualTo(1L));

        // HIPAA field mapping: who / what / when + tenant + transition
        List<AuditLogEntry> rows =
            auditLogRepository.findByTenantIdAndBookingId(tenantId, bookingId);
        AuditLogEntry row = rows.get(0);
        assertThat(row.getUserId()).isEqualTo(userId);              // who
        assertThat(row.getEventType()).isEqualTo("BookingConfirmed"); // what
        assertThat(row.getOccurredAt()).isEqualTo(occurredAt);      // when
        assertThat(row.getTenantId()).isEqualTo(tenantId);
        assertThat(row.getResourceId()).isEqualTo(resourceId);
        assertThat(row.getPreviousStatus()).isEqualTo("PENDING_HOLD");
        assertThat(row.getNewStatus()).isEqualTo("CONFIRMED");
        assertThat(row.getIpAddress()).isEqualTo("203.0.113.7");
        assertThat(row.getMetadata()).contains(event.getEventId());
    }

    @Test
    @DisplayName("distinct lifecycle events of one booking each get their own audit row")
    void distinctEventsOfSameBooking_areNotDeduplicated() {
        UUID tenantId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();

        // Same Kafka key (bookingId) but distinct eventIds — must NOT collapse
        testProducer.send(TOPIC, bookingId.toString(),
            event(tenantId, bookingId, userId, resourceId, Instant.now())).join();
        testProducer.send(TOPIC, bookingId.toString(),
            event(tenantId, bookingId, userId, resourceId, Instant.now())).join();

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(auditLogRepository.countByTenantIdAndBookingId(tenantId, bookingId))
                .as("dedup key is eventId, not the booking-scoped record key")
                .isEqualTo(2L));
    }

    private BookingLifecycleEvent event(UUID tenantId, UUID bookingId, UUID userId,
                                        UUID resourceId, Instant occurredAt) {
        return BookingLifecycleEvent.newBuilder()
            .setEventId(UUID.randomUUID().toString())
            .setEventType("BookingConfirmed")
            .setEventVersion(1)
            .setOccurredAt(occurredAt.toString())
            .setTenantId(tenantId.toString())
            .setBookingId(bookingId.toString())
            .setUserId(userId.toString())
            .setResourceId(resourceId.toString())
            .setLocationId(UUID.randomUUID().toString())
            .setServiceTypeId(UUID.randomUUID().toString())
            .setSlotStart(occurredAt.toString())
            .setSlotEnd(occurredAt.plusSeconds(3600).toString())
            .setPreviousStatus("PENDING_HOLD")
            .setNewStatus("CONFIRMED")
            .setIpAddress("203.0.113.7")
            .setMetadata(Map.of())
            .build();
    }
}
