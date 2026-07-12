# Kafka Event Mesh Specification
## Multi-Tenant Omni-Industry Scheduling Framework

**Version:** 1.0.0
**Kafka version:** Apache Kafka 3.x (KRaft mode)
**Schema format:** Apache Avro via Confluent Schema Registry
**Outbox relay:** Debezium PostgreSQL Connector

---

## 1. Topic Topology

| Topic | Partitions | Replication | Retention | Compaction | Purpose |
|---|---|---|---|---|---|
| `tenant.bookings.lifecycle` | 12 | 3 | 7 days | None | All booking state transitions |
| `tenant.bookings.lifecycle.DLQ` | 3 | 3 | 30 days | None | Failed consumer messages |
| `tenant.notifications.outbound` | 6 | 3 | 1 day | None | Notification dispatch commands |
| `tenant.audit.events` | 6 | 3 | 365 days | None | Immutable compliance stream |

**Partition key:** `booking_id` (UUID string) — ensures all events for a booking land on the same partition (ordered delivery per booking).

---

## 2. Consumer Groups

| Consumer Group | Subscribes To | Service | Concurrency |
|---|---|---|---|
| `notification-consumers` | `tenant.bookings.lifecycle` | notification-service | 1 thread per partition |
| `audit-consumers` | `tenant.bookings.lifecycle` | audit-service | 1 thread per partition |
| `outbound-notification-consumers` | `tenant.notifications.outbound` | notification-service | 6 threads |

---

## 3. Avro Schemas

### 3.1 `BookingLifecycleEvent` (topic: `tenant.bookings.lifecycle`)
**Schema subject:** `tenant.bookings.lifecycle-value`
**Compatibility:** BACKWARD

```json
{
  "type": "record",
  "name": "BookingLifecycleEvent",
  "namespace": "io.scheduler.events",
  "fields": [
    { "name": "eventId",        "type": "string",        "doc": "UUID of this event" },
    { "name": "eventType",      "type": "string",        "doc": "BookingHeld | BookingConfirmed | BookingCancelled | BookingCompleted | BookingExpired" },
    { "name": "eventVersion",   "type": "int",           "default": 1 },
    { "name": "occurredAt",     "type": "string",        "doc": "ISO 8601 UTC timestamp" },
    { "name": "tenantId",       "type": "string" },
    { "name": "bookingId",      "type": "string" },
    { "name": "userId",         "type": "string" },
    { "name": "resourceId",     "type": "string" },
    { "name": "locationId",     "type": "string" },
    { "name": "serviceTypeId",  "type": "string" },
    { "name": "slotStart",      "type": "string",        "doc": "ISO 8601 UTC" },
    { "name": "slotEnd",        "type": "string",        "doc": "ISO 8601 UTC" },
    { "name": "previousStatus", "type": ["null","string"], "default": null },
    { "name": "newStatus",      "type": "string" },
    { "name": "ipAddress",      "type": ["null","string"], "default": null },
    { "name": "metadata",       "type": { "type": "map", "values": "string" }, "default": {} }
  ]
}
```

---

### 3.2 `NotificationCommand` (topic: `tenant.notifications.outbound`)
**Schema subject:** `tenant.notifications.outbound-value`
**Compatibility:** BACKWARD

```json
{
  "type": "record",
  "name": "NotificationCommand",
  "namespace": "io.scheduler.events",
  "fields": [
    { "name": "commandId",      "type": "string" },
    { "name": "commandType",    "type": "string",  "doc": "SEND_CONFIRMATION | SEND_CANCELLATION | SEND_REMINDER" },
    { "name": "tenantId",       "type": "string" },
    { "name": "bookingId",      "type": "string" },
    { "name": "recipientEmail", "type": ["null","string"], "default": null },
    { "name": "recipientPhone", "type": ["null","string"], "default": null },
    { "name": "channel",        "type": "string",  "doc": "EMAIL | SMS | BOTH" },
    { "name": "templateData",   "type": { "type": "map", "values": "string" }, "default": {} },
    { "name": "createdAt",      "type": "string" }
  ]
}
```

---

### 3.3 `AuditEvent` (topic: `tenant.audit.events`)
**Schema subject:** `tenant.audit.events-value`
**Compatibility:** FULL (never remove fields from audit records)

```json
{
  "type": "record",
  "name": "AuditEvent",
  "namespace": "io.scheduler.events",
  "fields": [
    { "name": "auditId",        "type": "string" },
    { "name": "tenantId",       "type": "string" },
    { "name": "bookingId",      "type": "string" },
    { "name": "resourceId",     "type": ["null","string"], "default": null },
    { "name": "userId",         "type": "string",  "doc": "Who performed the action" },
    { "name": "eventType",      "type": "string" },
    { "name": "previousStatus", "type": ["null","string"], "default": null },
    { "name": "newStatus",      "type": "string" },
    { "name": "ipAddress",      "type": ["null","string"], "default": null },
    { "name": "userAgent",      "type": ["null","string"], "default": null },
    { "name": "occurredAt",     "type": "string" },
    { "name": "metadata",       "type": { "type": "map", "values": "string" }, "default": {} }
  ]
}
```

---

## 4. Outbox Pattern — Implementation Spec

### 4.1 Write side (Spring Boot)
```
BookingService.confirmBooking()
    @Transactional
    1. UPDATE bookings SET status = 'CONFIRMED' WHERE id = :bookingId AND tenant_id = :tenantId
    2. INSERT INTO outbox (
           tenant_id, aggregate_type, aggregate_id, event_type,
           topic, partition_key, payload
       ) VALUES (
           :tenantId, 'Booking', :bookingId, 'BookingConfirmed',
           'tenant.bookings.lifecycle', :bookingId::text,
           :avroSerializedPayloadAsJsonb
       )
    -- Both writes in same ACID transaction; either both commit or both rollback
```

### 4.2 Relay (Debezium CDC)
```yaml
# Debezium PostgreSQL connector config
name: outbox-connector
config:
  connector.class: io.debezium.connector.postgresql.PostgresConnector
  database.hostname: postgres
  database.port: 5432
  database.dbname: scheduler
  table.include.list: public.outbox
  transforms: outbox
  transforms.outbox.type: io.debezium.transforms.outbox.EventRouter
  transforms.outbox.table.field.event.id: id
  transforms.outbox.table.field.event.key: partition_key
  transforms.outbox.table.field.event.type: event_type
  transforms.outbox.table.field.event.payload: payload
  transforms.outbox.route.by.field: topic
  value.converter: io.confluent.connect.avro.AvroConverter
  value.converter.schema.registry.url: http://schema-registry:8081
```

### 4.3 Outbox cleanup
- Debezium marks rows as `PUBLISHED` after relay
- Scheduled job deletes `PUBLISHED` outbox rows older than 24 hours
- `FAILED` rows alert the observability agent after 3 relay attempts

---

## 5. Consumer Idempotency Implementation

Every Kafka consumer MUST follow this pattern before processing any message:

```
onMessage(ConsumerRecord record):
    1. CHECK processed_events WHERE consumer_group = :group AND message_key = :key
       → if EXISTS: log "duplicate detected, skipping" and return (ack)
    2. BEGIN TRANSACTION
       a. Process business logic (send notification / write audit)
       b. INSERT INTO processed_events (consumer_group, message_key, topic, partition, offset_value)
    3. COMMIT
    4. Acknowledge message to Kafka
```

If step 2 fails → do NOT ack → Kafka redelivers → idempotency check in step 1 prevents double-processing.

---

## 6. Dead-Letter Queue Policy

- After 3 failed processing attempts: message moved to `tenant.bookings.lifecycle.DLQ`
- DLQ depth > 0 → alert to observability agent (PagerDuty / Slack)
- DLQ messages are never auto-retried; require manual inspection and replay
- DLQ retention: 30 days

---

## 7. Kafka Configuration Properties

### Producer (Spring Boot / outbox relay)
```properties
spring.kafka.producer.acks=all
spring.kafka.producer.retries=3
spring.kafka.producer.properties.enable.idempotence=true
spring.kafka.producer.properties.max.in.flight.requests.per.connection=5
spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer
spring.kafka.producer.value-serializer=io.confluent.kafka.serializers.KafkaAvroSerializer
```

### Consumer (notification-service, audit-service)
```properties
spring.kafka.consumer.auto-offset-reset=earliest
spring.kafka.consumer.enable-auto-commit=false
spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer
spring.kafka.consumer.value-deserializer=io.confluent.kafka.serializers.KafkaAvroDeserializer
spring.kafka.consumer.properties.specific.avro.reader=true
spring.kafka.listener.ack-mode=MANUAL_IMMEDIATE
```

---

## 8. Schema Evolution Policy

| Change type | Allowed | Notes |
|---|---|---|
| Add optional field with default | ✅ Yes | BACKWARD compatible |
| Add required field without default | ❌ No | Breaks existing consumers |
| Remove field | ❌ No | Breaks existing consumers |
| Change field type | ❌ No | Schema incompatibility |
| Rename field (add alias) | ⚠️ With alias | Use Avro `aliases` — do not rename directly |

All schema changes must be reviewed by the ADR agent before registry update.
