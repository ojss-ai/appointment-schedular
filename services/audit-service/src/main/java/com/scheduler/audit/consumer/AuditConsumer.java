// TASK: ATOM-KAFKA-010
package com.scheduler.audit.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.audit.domain.AuditLogEntry;
import com.scheduler.audit.domain.ProcessedEvent;
import com.scheduler.audit.repository.AuditLogRepository;
import com.scheduler.audit.repository.ProcessedEventRepository;
import io.scheduler.events.BookingLifecycleEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes one immutable audit row per booking lifecycle event (HIPAA ledger).
 *
 * <p>Idempotency (NFR-2.1): dedup check first; the audit INSERT and the
 * processed_events INSERT share one transaction, and the offset commit is the
 * last statement. If the dedup insert hits the unique constraint the whole
 * transaction rolls back — no orphan audit row, safe redelivery.
 *
 * <p>Mapping is explicit field-by-field (never reflective) so a schema
 * evolution that dropped a HIPAA field would fail compilation, not silently
 * lose data. Dedup key is the payload eventId (the record key is the
 * bookingId, shared by all lifecycle events of a booking).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AuditConsumer {

    public static final String CONSUMER_GROUP = "audit-consumers";

    private final AuditLogRepository auditLogRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final ObjectMapper objectMapper;

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
            log.info("Duplicate audit event skipped consumerGroup={} eventId={}",
                CONSUMER_GROUP, messageKey);
            acknowledgment.acknowledge();
            return;
        }

        // 2. Append-only audit INSERT via audit_writer role
        auditLogRepository.save(mapToAuditEntry(event));

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
        log.info("Audit record written eventType={} bookingId={} tenantId={}",
            event.getEventType(), event.getBookingId(), event.getTenantId());
    }

    /**
     * Explicit HIPAA field mapping (docs/SECURITY-SPEC.md 5.1):
     * who = userId, what = eventType, when = occurredAt, plus tenantId,
     * bookingId, resourceId, ipAddress and the status transition metadata.
     */
    private AuditLogEntry mapToAuditEntry(BookingLifecycleEvent event) {
        Map<String, String> metadata = new HashMap<>(event.getMetadata() == null
            ? Map.of() : event.getMetadata());
        metadata.put("eventId", event.getEventId());
        metadata.put("eventVersion", String.valueOf(event.getEventVersion()));

        return AuditLogEntry.builder()
            .tenantId(UUID.fromString(event.getTenantId()))
            .bookingId(UUID.fromString(event.getBookingId()))
            .resourceId(UUID.fromString(event.getResourceId()))
            .userId(UUID.fromString(event.getUserId()))
            .eventType(event.getEventType())
            .previousStatus(event.getPreviousStatus())
            .newStatus(event.getNewStatus())
            .ipAddress(event.getIpAddress())
            .metadata(serialize(metadata))
            .occurredAt(Instant.parse(event.getOccurredAt()))
            .build();
    }

    private String serialize(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize audit metadata", e);
        }
    }
}
