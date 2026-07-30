// TASK: ATOM-KAFKA-002 (AC: same-tx atomicity, rollback, MANDATORY enforcement)
package com.scheduler.api.outbox;

import com.scheduler.api.booking.Booking;
import com.scheduler.api.booking.BookingStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-003 verification against real PostgreSQL: outbox row commits with the
 * caller's transaction, disappears on rollback, and calls outside any
 * transaction are rejected by Propagation.MANDATORY.
 */
@SpringBootTest
@Testcontainers
class OutboxServiceIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("scheduler_test")
        .withUsername("scheduler")
        .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

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

    private Booking booking;

    @BeforeEach
    void setUp() {
        outboxRepository.deleteAll();
        booking = Booking.builder()
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

    @Test
    void commits_withCallerTransaction() {
        new TransactionTemplate(txManager).executeWithoutResult(status ->
            outboxService.writeBookingEvent(
                booking, OutboxService.EVENT_BOOKING_CONFIRMED, BookingStatus.PENDING_HOLD));

        List<OutboxEvent> rows =
            outboxRepository.findByTenantIdAndAggregateId(booking.getTenantId(), booking.getId());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        assertThat(rows.get(0).getCreatedAt()).isNotNull();
    }

    @Test
    void rollsBack_withCallerTransaction() {
        assertThatThrownBy(() ->
            new TransactionTemplate(txManager).executeWithoutResult(status -> {
                outboxService.writeBookingEvent(
                    booking, OutboxService.EVENT_BOOKING_CONFIRMED, BookingStatus.PENDING_HOLD);
                throw new RuntimeException("simulated failure after outbox write");
            }))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("simulated failure");

        assertThat(outboxRepository
            .findByTenantIdAndAggregateId(booking.getTenantId(), booking.getId()))
            .as("outbox must have zero rows after caller rollback (ADR-003)")
            .isEmpty();
    }

    @Test
    void throws_whenCalledOutsideTransaction() {
        assertThatThrownBy(() -> outboxService.writeBookingEvent(
                booking, OutboxService.EVENT_BOOKING_HELD, null))
            .isInstanceOf(IllegalTransactionStateException.class);
    }
}
