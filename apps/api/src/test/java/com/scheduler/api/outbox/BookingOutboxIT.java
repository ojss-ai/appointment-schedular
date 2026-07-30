// TASK: ATOM-KAFKA-006 (AC: every lifecycle transition stages one outbox row)
package com.scheduler.api.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.booking.BookingRepository;
import com.scheduler.api.booking.BookingService;
import com.scheduler.api.booking.dto.HoldResponse;
import com.scheduler.api.location.Location;
import com.scheduler.api.location.LocationRepository;
import com.scheduler.api.resource.Resource;
import com.scheduler.api.resource.ResourceRepository;
import com.scheduler.api.resource.ResourceSchedule;
import com.scheduler.api.resource.ResourceScheduleRepository;
import com.scheduler.api.booking.dto.CreateHoldRequest;
import com.scheduler.api.security.TenantContext;
import com.scheduler.api.servicetype.ServiceType;
import com.scheduler.api.servicetype.ServiceTypeRepository;
import com.scheduler.api.tenant.Tenant;
import com.scheduler.api.tenant.TenantRepository;
import com.scheduler.api.user.User;
import com.scheduler.api.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end assertion that BookingService stages BookingHeld /
 * BookingConfirmed / BookingCancelled outbox rows atomically with the state
 * transition (ADR-003, ATOM-KAFKA-006).
 */
@SpringBootTest
@Testcontainers
class BookingOutboxIT {

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

    @Autowired private BookingService bookingService;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private OutboxRepository outboxRepository;
    @Autowired private TenantRepository tenantRepository;
    @Autowired private LocationRepository locationRepository;
    @Autowired private ResourceRepository resourceRepository;
    @Autowired private ResourceScheduleRepository scheduleRepository;
    @Autowired private ServiceTypeRepository serviceTypeRepository;
    @Autowired private UserRepository userRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID tenantId;
    private UUID resourceId;
    private UUID serviceTypeId;
    private UUID userId;

    @BeforeEach
    void setUpFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        tenantId = tenantRepository.save(Tenant.builder()
            .name("Outbox Tenant " + suffix).slug("ob-" + suffix).build()).getId();
        UUID locationId = locationRepository.save(Location.builder()
            .tenantId(tenantId).name("Outbox Branch").addressLine1("1 Test Way")
            .city("Testville").postalCode("00000").countryCode("US")
            .timezone("UTC").build()).getId();
        resourceId = resourceRepository.save(Resource.builder()
            .tenantId(tenantId).locationId(locationId)
            .name("Outbox Resource").resourceType("GENERAL").build()).getId();
        for (int day = 0; day <= 6; day++) {
            scheduleRepository.save(ResourceSchedule.builder()
                .tenantId(tenantId).resourceId(resourceId).dayOfWeek(day)
                .startTime(LocalTime.of(8, 0)).endTime(LocalTime.of(18, 0))
                .isActive(true).effectiveFrom(LocalDate.of(2020, 1, 1)).build());
        }
        serviceTypeId = serviceTypeRepository.save(ServiceType.builder()
            .tenantId(tenantId).name("Outbox Session").durationMinutes(60)
            .bufferBeforeMin(0).bufferAfterMin(0)
            .allowedResourceTypes(List.of("GENERAL")).build()).getId();
        userId = userRepository.save(User.builder()
            .tenantId(tenantId).identifier("outbox-" + suffix + "@test.local")
            .identifierType("EMAIL").build()).getId();
        TenantContext.set(tenantId, userId, List.of("customer"));
    }

    @AfterEach
    void cleanUp() {
        outboxRepository.deleteAll();
        bookingRepository.deleteAll();
        TenantContext.clear();
    }

    @Test
    void holdConfirmCancel_stageOneOutboxRowEach_withCorrectPayload() throws Exception {
        Instant slotStart = LocalDate.now(ZoneOffset.UTC).plusDays(1)
            .atTime(10, 0).toInstant(ZoneOffset.UTC);

        // 1. Hold → BookingHeld
        HoldResponse hold = bookingService.createHold(
            new CreateHoldRequest(resourceId, serviceTypeId, slotStart), tenantId, userId);
        UUID bookingId = hold.bookingId();
        List<OutboxEvent> rows =
            outboxRepository.findByTenantIdAndAggregateId(tenantId, bookingId);
        assertThat(rows).extracting(OutboxEvent::getEventType)
            .containsExactly("BookingHeld");

        // 2. Confirm → BookingConfirmed with previousStatus=PENDING_HOLD
        bookingService.confirmBooking(bookingId, null, tenantId, userId);
        rows = outboxRepository.findByTenantIdAndAggregateId(tenantId, bookingId);
        assertThat(rows).extracting(OutboxEvent::getEventType)
            .containsExactlyInAnyOrder("BookingHeld", "BookingConfirmed");

        OutboxEvent confirmed = rows.stream()
            .filter(e -> e.getEventType().equals("BookingConfirmed")).findFirst().orElseThrow();
        assertThat(confirmed.getPartitionKey()).isEqualTo(bookingId.toString());
        assertThat(confirmed.getTopic()).isEqualTo("tenant.bookings.lifecycle");
        JsonNode payload = objectMapper.readTree(confirmed.getPayload());
        assertThat(payload.get("eventType").asText()).isEqualTo("BookingConfirmed");
        assertThat(payload.get("tenantId").asText()).isEqualTo(tenantId.toString());
        assertThat(payload.get("bookingId").asText()).isEqualTo(bookingId.toString());
        assertThat(payload.get("previousStatus").asText()).isEqualTo("PENDING_HOLD");
        assertThat(payload.get("newStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(payload.hasNonNull("eventId")).isTrue();

        // 3. Cancel → BookingCancelled with previousStatus=CONFIRMED
        bookingService.cancelBooking(bookingId, "test cancellation",
            userId, List.of("customer"), tenantId);
        rows = outboxRepository.findByTenantIdAndAggregateId(tenantId, bookingId);
        assertThat(rows).extracting(OutboxEvent::getEventType)
            .containsExactlyInAnyOrder("BookingHeld", "BookingConfirmed", "BookingCancelled");
    }
}
