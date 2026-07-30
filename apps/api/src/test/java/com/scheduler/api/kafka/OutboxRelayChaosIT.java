// TASK: ATOM-KAFKA-011 — formal verification of ADR-003 reliability claims
package com.scheduler.api.kafka;

import com.scheduler.api.booking.Booking;
import com.scheduler.api.booking.BookingStatus;
import com.scheduler.api.outbox.OutboxEvent;
import com.scheduler.api.outbox.OutboxRepository;
import com.scheduler.api.outbox.OutboxService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Outbox chaos suite (ATOM-KAFKA-011): Kafka outage, DB rollback atomicity
 * and Debezium restart deduplication, against real containers.
 *
 * <p>The Debezium connector under test uses JsonConverter (not Avro) so the
 * suite needs no Schema Registry container — routing, keys and delivery
 * semantics are identical; serialization format is covered by
 * KafkaSpecConformanceTest and the consumer-service ITs.
 *
 * <p>Run with: mvn verify -P integration -Dgroups=chaos
 */
@SpringBootTest
@Testcontainers
@Tag("chaos")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OutboxRelayChaosIT {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayChaosIT.class);
    private static final String TOPIC = "tenant.bookings.lifecycle";
    private static final String CONNECTOR = "chaos-outbox-connector";

    static final Network network = Network.newNetwork();

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("scheduler_test")
        .withUsername("scheduler")
        .withPassword("test")
        .withNetwork(network)
        .withNetworkAliases("postgres")
        .withCommand("postgres", "-c", "wal_level=logical",
            "-c", "max_wal_senders=4", "-c", "max_replication_slots=4");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @Container
    static KafkaContainer kafka = new KafkaContainer(
            DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
        .withNetwork(network)
        .withNetworkAliases("kafka");

    @Container
    static GenericContainer<?> debezium = new GenericContainer<>("debezium/connect:2.6")
        .withNetwork(network)
        .withNetworkAliases("debezium")
        .withExposedPorts(8083)
        .withEnv("BOOTSTRAP_SERVERS", "kafka:9092")
        .withEnv("GROUP_ID", "chaos-connect")
        .withEnv("CONFIG_STORAGE_TOPIC", "chaos-configs")
        .withEnv("OFFSET_STORAGE_TOPIC", "chaos-offsets")
        .withEnv("STATUS_STORAGE_TOPIC", "chaos-status")
        .waitingFor(Wait.forHttp("/").forPort(8083).withStartupTimeout(Duration.ofMinutes(3)));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("app.jwt.secret", () -> "integration-test-secret-key-32+bytes-long!!");
        r.add("app.jwt.expiry-hours", () -> 1);
    }

    @Autowired
    private OutboxService outboxService;
    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private PlatformTransactionManager txManager;

    @BeforeEach
    void cleanState() {
        outboxRepository.deleteAll();
    }

    // ------------------------------------------------------------------
    // Scenario 2 (safe first): DB rollback removes both writes atomically
    // ------------------------------------------------------------------
    @Test
    @Order(1)
    void shouldRollbackOutbox_whenDbRollsBack() {
        Booking booking = inMemoryBooking();
        assertThatThrownBy(() ->
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                outboxService.writeBookingEvent(
                    booking, OutboxService.EVENT_BOOKING_CONFIRMED, BookingStatus.PENDING_HOLD);
                throw new RuntimeException("chaos: failure after outbox write");
            })).isInstanceOf(RuntimeException.class);

        assertThat(outboxRepository
            .findByTenantIdAndAggregateId(booking.getTenantId(), booking.getId()))
            .as("outbox must have zero rows after rollback (ADR-003)")
            .isEmpty();
    }

    // ------------------------------------------------------------------
    // Baseline relay: committed outbox row reaches Kafka with bookingId key
    // ------------------------------------------------------------------
    @Test
    @Order(2)
    void shouldRelayCommittedRow_withBookingIdAsMessageKey() {
        ensureConnectorRegistered();
        Booking booking = inMemoryBooking();
        writeEventInNewTransaction(booking);

        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(countMessagesWithKey(booking.getId().toString()))
                .as("relayed message keyed by bookingId")
                .isEqualTo(1L));
    }

    // ------------------------------------------------------------------
    // Scenario 3: Debezium restart — exactly one message, key preserved
    // ------------------------------------------------------------------
    @Test
    @Order(3)
    void shouldNotDuplicate_afterDebeziumRestart() {
        ensureConnectorRegistered();

        log.info("Stopping Debezium before staging the outbox row");
        debezium.stop();

        Booking booking = inMemoryBooking();
        writeEventInNewTransaction(booking);
        assertThat(outboxRepository
            .findByTenantIdAndAggregateId(booking.getTenantId(), booking.getId()))
            .hasSize(1)
            .first()
            .extracting(OutboxEvent::getStatus)
            .isEqualTo(OutboxEvent.STATUS_PENDING);

        log.info("Restarting Debezium — connector config is recovered from Kafka");
        debezium.start();
        ensureConnectorRegistered();

        String key = booking.getId().toString();
        await().atMost(90, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(countMessagesWithKey(key)).isEqualTo(1L));

        // Grace window: verify no late duplicate arrives (AC-03)
        await().during(5, TimeUnit.SECONDS).atMost(30, TimeUnit.SECONDS)
            .untilAsserted(() ->
                assertThat(countMessagesWithKey(key))
                    .as("exactly one message after Debezium restart — key preserved, no duplicate")
                    .isEqualTo(1L));
    }

    // ------------------------------------------------------------------
    // Scenario 1 (most disruptive, last): Kafka outage during commit
    // ------------------------------------------------------------------
    @Test
    @Order(4)
    void shouldDeliverEvent_afterKafkaRecovery() {
        ensureConnectorRegistered();

        log.info("Stopping Kafka — simulating broker outage");
        kafka.stop();

        // Business transaction still commits: bookings + outbox are DB-only
        Booking booking = inMemoryBooking();
        writeEventInNewTransaction(booking);
        assertThat(outboxRepository
            .findByTenantIdAndAggregateId(booking.getTenantId(), booking.getId()))
            .as("outbox row committed while Kafka is down (ADR-003)")
            .hasSize(1)
            .first()
            .extracting(OutboxEvent::getStatus)
            .isEqualTo(OutboxEvent.STATUS_PENDING);

        log.info("Restarting Kafka and Debezium — Debezium resumes from the WAL slot");
        kafka.start();
        // Connect's internal topics were on the restarted broker; bounce it
        // and re-register the connector (idempotent PUT).
        debezium.stop();
        debezium.start();
        ensureConnectorRegistered();

        await().atMost(120, TimeUnit.SECONDS).untilAsserted(() ->
            assertThat(countMessagesWithKey(booking.getId().toString()))
                .as("buffered outbox row delivered after Kafka recovery")
                .isEqualTo(1L));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Booking inMemoryBooking() {
        // The outbox table has no FK to bookings; an unsaved aggregate with
        // pre-assigned ids exercises the identical write path without the
        // full tenant fixture chain.
        return Booking.builder()
            .id(UUID.randomUUID())
            .tenantId(UUID.randomUUID())
            .locationId(UUID.randomUUID())
            .resourceId(UUID.randomUUID())
            .serviceTypeId(UUID.randomUUID())
            .userId(UUID.randomUUID())
            .status(BookingStatus.CONFIRMED)
            .slotStart(Instant.parse("2026-08-01T10:00:00Z"))
            .slotEnd(Instant.parse("2026-08-01T11:00:00Z"))
            .build();
    }

    private void writeEventInNewTransaction(Booking booking) {
        new TransactionTemplate(txManager).executeWithoutResult(status ->
            outboxService.writeBookingEvent(
                booking, OutboxService.EVENT_BOOKING_CONFIRMED, BookingStatus.PENDING_HOLD));
    }

    /** Idempotent connector upsert via Kafka Connect REST (PUT). */
    private static void ensureConnectorRegistered() {
        String config = """
            {
              "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
              "plugin.name": "pgoutput",
              "database.hostname": "postgres",
              "database.port": "5432",
              "database.user": "scheduler",
              "database.password": "test",
              "database.dbname": "scheduler_test",
              "topic.prefix": "chaos",
              "table.include.list": "public.outbox",
              "slot.name": "chaos_outbox_slot",
              "publication.name": "chaos_outbox_pub",
              "publication.autocreate.mode": "filtered",
              "transforms": "outbox",
              "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
              "transforms.outbox.table.field.event.id": "id",
              "transforms.outbox.table.field.event.key": "partition_key",
              "transforms.outbox.table.field.event.type": "event_type",
              "transforms.outbox.table.field.event.payload": "payload",
              "transforms.outbox.route.by.field": "topic",
              "transforms.outbox.route.topic.replacement": "${routedByValue}",
              "key.converter": "org.apache.kafka.connect.storage.StringConverter",
              "value.converter": "org.apache.kafka.connect.json.JsonConverter",
              "value.converter.schemas.enable": "false",
              "tombstones.on.delete": "false",
              "heartbeat.interval.ms": "10000"
            }
            """;
        String url = "http://%s:%d/connectors/%s/config"
            .formatted(debezium.getHost(), debezium.getMappedPort(8083), CONNECTOR);
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(url))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(config))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode())
                .as("connector registration response: %s", response.body())
                .isIn(200, 201);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to register chaos connector", e);
        }
    }

    /** Fresh consumer group each call — counts all messages with the key. */
    private long countMessagesWithKey(String key) {
        Properties props = new Properties();
        props.putAll(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "chaos-verify-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName(),
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName()));
        AtomicLong count = new AtomicLong();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC, Duration.ofSeconds(10))
                .stream().map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (key.equals(record.key())) {
                        count.incrementAndGet();
                    }
                }
            }
        }
        return count.get();
    }
}
