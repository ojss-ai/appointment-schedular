// TASK: ATOM-KAFKA-002
package com.scheduler.api.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.booking.Booking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Thin persistence helper implementing the write side of the transactional
 * outbox pattern (ADR-003). Writes the event row in the SAME transaction as
 * the caller's business mutation; Debezium relays it to Kafka after commit.
 *
 * <p>{@code Propagation.MANDATORY} (never plain {@code @Transactional}): a
 * call outside an active transaction throws
 * {@code IllegalTransactionStateException} immediately, so atomicity misuse
 * is a test-time failure, not a silent production bug.
 *
 * <p>Payload field names match the {@code BookingLifecycleEvent} Avro schema
 * (docs/KAFKA-SPEC.md 3.1 / infra/kafka/schemas/booking-lifecycle-event.avsc)
 * exactly — verified by unit test.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    public static final String TOPIC_BOOKING_LIFECYCLE = "tenant.bookings.lifecycle";
    public static final String AGGREGATE_BOOKING = "Booking";

    public static final String EVENT_BOOKING_HELD = "BookingHeld";
    public static final String EVENT_BOOKING_CONFIRMED = "BookingConfirmed";
    public static final String EVENT_BOOKING_CANCELLED = "BookingCancelled";
    public static final String EVENT_BOOKING_COMPLETED = "BookingCompleted";
    public static final String EVENT_BOOKING_EXPIRED = "BookingExpired";

    private static final int EVENT_VERSION = 1;

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Stages a booking lifecycle event for CDC relay to Kafka.
     *
     * <p>MUST be called within the caller's active {@code @Transactional}
     * scope; the outbox row and the booking mutation commit (or roll back)
     * atomically (ADR-003).
     *
     * @param booking        the booking whose state just changed — its current
     *                       {@code status} is recorded as {@code newStatus}
     * @param eventType      one of the {@code EVENT_*} constants
     * @param previousStatus the status before this transition (nullable, e.g.
     *                       {@code null} for BookingHeld)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void writeBookingEvent(Booking booking, String eventType, String previousStatus) {
        UUID outboxId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
            .id(outboxId)
            .tenantId(booking.getTenantId())
            .aggregateType(AGGREGATE_BOOKING)
            .aggregateId(booking.getId())
            .eventType(eventType)
            .topic(TOPIC_BOOKING_LIFECYCLE)
            .partitionKey(booking.getId().toString())
            .payload(serialize(buildPayload(booking, eventType, previousStatus)))
            .status(OutboxEvent.STATUS_PENDING)
            .build();
        outboxRepository.save(event);
        log.debug("Outbox row written id={} eventType={} bookingId={} tenantId={}",
            outboxId, eventType, booking.getId(), booking.getTenantId());
    }

    /**
     * Field names/order per BookingLifecycleEvent Avro schema
     * (docs/KAFKA-SPEC.md 3.1). LinkedHashMap because Map.of() rejects the
     * nullable previousStatus/ipAddress values.
     */
    Map<String, Object> buildPayload(Booking booking, String eventType, String previousStatus) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("eventType", eventType);
        payload.put("eventVersion", EVENT_VERSION);
        payload.put("occurredAt", Instant.now().toString());
        payload.put("tenantId", booking.getTenantId().toString());
        payload.put("bookingId", booking.getId().toString());
        payload.put("userId", booking.getUserId().toString());
        payload.put("resourceId", booking.getResourceId().toString());
        payload.put("locationId", booking.getLocationId().toString());
        payload.put("serviceTypeId", booking.getServiceTypeId().toString());
        payload.put("slotStart", booking.getSlotStart().toString());
        payload.put("slotEnd", booking.getSlotEnd().toString());
        payload.put("previousStatus", previousStatus);
        payload.put("newStatus", booking.getStatus());
        payload.put("ipAddress", extractIpAddress());
        payload.put("metadata", Map.of());
        return payload;
    }

    private String serialize(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Unreachable for a map of strings, but never swallow silently:
            // failing the transaction is the only safe outcome (ADR-003).
            throw new IllegalStateException("Failed to serialize outbox payload", e);
        }
    }

    /**
     * Originating IP from the current HTTP request context for the audit
     * trail. Read via {@code RequestContextHolder} — never a method parameter,
     * so callers cannot inject caller-controlled values. Null outside an HTTP
     * request (e.g. scheduled jobs).
     */
    private String extractIpAddress() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes servletAttrs) {
            String forwarded = servletAttrs.getRequest().getHeader("X-Forwarded-For");
            return forwarded != null
                ? forwarded.split(",")[0].trim()
                : servletAttrs.getRequest().getRemoteAddr();
        }
        return null;
    }
}
