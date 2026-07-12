# Kafka + Avro Skill — Scheduling Framework Patterns

> Reference for Coder and Migrations agents.
> Applies to `apps/api/`, `services/notification-service/`, `services/audit-service/`.

---

## Topology Overview

```
BookingService
    │
    ▼ (ACID transaction)
outbox table
    │
    ▼ (Debezium CDC / Kafka Connect)
Confluent Schema Registry ←→ Avro validation
    │
    ▼
Kafka Topics:
  tenant.bookings.lifecycle      ← booking state changes
  tenant.notifications.outbound  ← notification dispatch requests
  tenant.audit.immutable         ← HIPAA audit log (append-only)
  tenant.slots.invalidation      ← cache invalidation signals
    │
    ├──▶ notification-service (consumer group: notification-workers)
    └──▶ audit-service        (consumer group: audit-writers)
```

**Rule:** Business code NEVER writes directly to Kafka. Outbox → CDC → Kafka only.

---

## Avro Schema Conventions

Schemas live in `apps/api/src/main/avro/`.

```json
// booking-lifecycle-v1.avsc
{
  "type": "record",
  "name": "BookingLifecycleEvent",
  "namespace": "com.scheduling.events.v1",
  "fields": [
    { "name": "eventId",      "type": "string" },
    { "name": "eventType",    "type": "string" },
    { "name": "tenantId",     "type": "string" },
    { "name": "bookingId",    "type": "string" },
    { "name": "resourceId",   "type": "string" },
    { "name": "serviceId",    "type": "string" },
    { "name": "status",       "type": "string" },
    { "name": "startTime",    "type": "string" },
    { "name": "endTime",      "type": "string" },
    { "name": "occurredAt",   "type": "long", "logicalType": "timestamp-millis" },
    { "name": "metadata",     "type": { "type": "map", "values": "string" }, "default": {} }
  ]
}
```

**Rules:**
- Schema compatibility mode: `BACKWARD` (new consumers can read old messages)
- Never remove or rename fields — add new fields with defaults instead
- No PII in event payloads — `metadata` map is for operational tags only
- `tenantId` is **always** the Kafka message key (enables partition locality)

---

## Outbox Event Builder

```java
// Inside a @Transactional service method — same transaction as booking.save()
private OutboxEvent buildOutboxEvent(Booking booking, String eventType) {
    BookingLifecycleEvent avroEvent = BookingLifecycleEvent.newBuilder()
        .setEventId(UUID.randomUUID().toString())
        .setEventType(eventType)
        .setTenantId(booking.getTenantId().toString())
        .setBookingId(booking.getId().toString())
        .setResourceId(booking.getResourceId().toString())
        .setServiceId(booking.getServiceId().toString())
        .setStatus(booking.getStatus().name())
        .setStartTime(booking.getStartTime().toString())
        .setEndTime(booking.getEndTime().toString())
        .setOccurredAt(Instant.now().toEpochMilli())
        .build();

    return OutboxEvent.builder()
        .tenantId(booking.getTenantId())
        .aggregateType("BOOKING")
        .aggregateId(booking.getId())
        .eventType(eventType)
        .payload(avroSerializer.serializeToJson(avroEvent))  // store as JSON string
        .processed(false)
        .build();
}
```

---

## Consumer Pattern (Idempotent)

```java
@Component
@KafkaListener(
    topics = "tenant.bookings.lifecycle",
    groupId = "notification-workers",
    containerFactory = "kafkaListenerContainerFactory"
)
public class BookingLifecycleConsumer {

    private final NotificationService notificationService;
    private final ProcessedEventRepository processedEventRepo;

    @KafkaHandler
    @Transactional
    public void handle(BookingLifecycleEvent event,
                       @Header(KafkaHeaders.RECEIVED_KEY) String messageKey) {
        // IDEMPOTENCY GUARD — check dedup table first
        if (processedEventRepo.existsByEventId(event.getEventId().toString())) {
            log.info("Duplicate event skipped eventId={}", event.getEventId());
            return;
        }

        // Process
        notificationService.dispatchReminder(event);

        // Mark as processed — same transaction
        processedEventRepo.save(ProcessedEvent.builder()
            .eventId(event.getEventId().toString())
            .processedAt(Instant.now())
            .build());
    }

    // Dead-letter handling
    @KafkaHandler(isDefault = true)
    public void handleDead(Object message) {
        log.error("Unhandled Kafka message type={}", message.getClass().getName());
    }
}
```

---

## Kafka Configuration

```yaml
# application.yml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
      acks: all
      retries: 3
    consumer:
      group-id: notification-workers
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: io.confluent.kafka.serializers.KafkaAvroDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false
    properties:
      schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8081}
      specific.avro.reader: true
```

---

## Topic Naming Convention

```
{domain}.{aggregate}.{event-category}

Examples:
  tenant.bookings.lifecycle       ← CONFIRMED, CANCELLED, NO_SHOW, PENDING_HOLD
  tenant.notifications.outbound   ← EMAIL_DISPATCH, SMS_DISPATCH
  tenant.audit.immutable          ← all state changes (HIPAA)
  tenant.slots.invalidation       ← resource schedule changed
```

---

## Processed Events Table (Idempotency)

```sql
-- V10__create_processed_events.sql
CREATE TABLE processed_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id    VARCHAR(100) NOT NULL UNIQUE,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_processed_events_id ON processed_events(event_id);
```

---

## Anti-Patterns (Never Do)

- ❌ `kafkaTemplate.send()` inside a `@Transactional` method — use outbox
- ❌ Non-idempotent consumers — always check `processed_events` first
- ❌ PII (name, email, phone) in Avro event payloads — use opaque IDs only
- ❌ Schema changes that remove or rename fields — add with default values only
- ❌ Consumer `enable-auto-commit: true` — manual offset commit only
- ❌ Missing `tenantId` as message key — all topics keyed by tenantId
