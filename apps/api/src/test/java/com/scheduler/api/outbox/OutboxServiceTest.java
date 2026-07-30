// TASK: ATOM-KAFKA-002 (AC: payload shape, UUID assignment, Avro field match)
package com.scheduler.api.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.booking.Booking;
import com.scheduler.api.booking.BookingStatus;
import org.apache.avro.Schema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the outbox writer (ADR-003). The Avro-schema test parses
 * the shared .avsc file so any drift between OutboxService.buildPayload()
 * and docs/KAFKA-SPEC.md 3.1 fails here, not at consumer deserialization.
 */
@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    private static final String SCHEMA_FILE =
        "../../infra/kafka/schemas/booking-lifecycle-event.avsc";

    @Mock
    private OutboxRepository outboxRepository;

    private OutboxService outboxService;
    private Booking booking;

    @BeforeEach
    void setUp() {
        outboxService = new OutboxService(outboxRepository, new ObjectMapper());
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
    void payloadKeys_matchBookingLifecycleEventAvroSchema_exactly() throws Exception {
        File schemaFile = new File(SCHEMA_FILE);
        assumeTrue(schemaFile.exists(), "shared .avsc not found relative to module dir");
        Schema schema = new Schema.Parser().parse(schemaFile);
        Set<String> avroFields = schema.getFields().stream()
            .map(Schema.Field::name)
            .collect(Collectors.toSet());

        Map<String, Object> payload = outboxService.buildPayload(
            booking, OutboxService.EVENT_BOOKING_CONFIRMED, BookingStatus.PENDING_HOLD);

        assertThat(payload.keySet()).containsExactlyInAnyOrderElementsOf(avroFields);
    }

    @Test
    void writeBookingEvent_stagesRowWithPendingStatusAndBookingKey() {
        outboxService.writeBookingEvent(
            booking, OutboxService.EVENT_BOOKING_CONFIRMED, BookingStatus.PENDING_HOLD);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent row = captor.getValue();

        assertThat(row.getId()).isNotNull();                       // pre-assigned UUID
        assertThat(row.getTenantId()).isEqualTo(booking.getTenantId());
        assertThat(row.getAggregateType()).isEqualTo("Booking");
        assertThat(row.getAggregateId()).isEqualTo(booking.getId());
        assertThat(row.getEventType()).isEqualTo("BookingConfirmed");
        assertThat(row.getTopic()).isEqualTo("tenant.bookings.lifecycle");
        assertThat(row.getPartitionKey()).isEqualTo(booking.getId().toString());
        assertThat(row.getStatus()).isEqualTo(OutboxEvent.STATUS_PENDING);
        assertThat(row.getPayload())
            .contains("\"previousStatus\":\"PENDING_HOLD\"")
            .contains("\"newStatus\":\"CONFIRMED\"");
    }

    @Test
    void writeBookingEvent_declaresMandatoryPropagation() throws Exception {
        Method method = OutboxService.class.getMethod(
            "writeBookingEvent", Booking.class, String.class, String.class);
        Transactional tx = method.getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.propagation()).isEqualTo(Propagation.MANDATORY);
    }
}
