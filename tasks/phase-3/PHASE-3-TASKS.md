# Phase 3 — Kafka Event Mesh
**Duration:** Weeks 8–10
**Milestone:** Full Kafka pipeline live; notifications sent; immutable audit trail captured

---

## P3-T01 — Bookings and Event-Related Flyway Migrations (V010–V013)
**Tags:** [MIGRATION]
**Priority:** P1
**Estimate:** 1 day
**Agent:** Migrations agent
**Depends on:** P2-T09

### Specification
Add the remaining migrations: bookings, outbox, processed_events, and audit_log tables.

**Migration files:**
- `V010__create_bookings.sql` — full bookings table with all indexes (see `docs/DATABASE-SCHEMA.md` §2.10)
- `V011__create_outbox.sql`
- `V012__create_processed_events.sql`
- `V013__create_audit_log.sql` + Row-Level Security policy

**Critical index (NFR-1.3):**
```sql
CREATE INDEX idx_bookings_tenant_location_start
    ON bookings(tenant_id, location_id, slot_start)
    WHERE status IN ('PENDING_HOLD', 'CONFIRMED');
```
This index must exist before any slot query code runs.

**Audit log RLS:**
```sql
CREATE ROLE audit_writer;
GRANT INSERT ON audit_log TO audit_writer;
-- No UPDATE or DELETE grant
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
```

**Acceptance criteria:**
- [ ] `mvn flyway:migrate` from V009 to V013 succeeds cleanly
- [ ] All 5 booking-related indexes exist and verified by `\d bookings` in psql
- [ ] `audit_writer` role can INSERT but not UPDATE/DELETE audit_log
- [ ] Migrations agent dry-run result documented

---

## P3-T02 — Outbox Entity and OutboxService
**Tags:** [KAFKA]
**Priority:** P1
**Estimate:** 1 day
**Agent:** Coder agent
**Depends on:** P3-T01

### Specification
Implement the outbox persistence layer.

**OutboxEntity (JPA):**
- Maps to `outbox` table
- Fields per `docs/DATABASE-SCHEMA.md` §2.11
- No `@GeneratedValue` on `id` — use `UUID.randomUUID()` explicitly (ensures ID is known before insert)

**OutboxService:**
- `writeBookingEvent(booking, eventType)` — constructs `OutboxEntity` with:
  - `aggregateType = "Booking"`
  - `aggregateId = booking.getId()`
  - `topic = "tenant.bookings.lifecycle"`
  - `partitionKey = booking.getId().toString()`
  - `payload = buildPayload(booking, eventType)` — JSONB representation of `BookingLifecycleEvent` Avro schema fields
  - `status = PENDING`
- Method has **no `@Transactional` annotation** — relies on caller's transaction (propagation MANDATORY)
- If called outside a transaction → throws `IllegalTransactionStateException`

**Acceptance criteria:**
- [ ] `OutboxService.writeBookingEvent()` inserts row in same transaction as caller
- [ ] Calling outside a transaction throws exception (test this explicitly)
- [ ] Payload JSON matches `BookingLifecycleEvent` Avro schema field names exactly
- [ ] `partitionKey` is booking UUID string

---

## P3-T03 — Debezium Connector Configuration
**Tags:** [KAFKA] [INFRA]
**Priority:** P1
**Estimate:** 1 day
**Agent:** Coder agent (DevOps mode)
**Depends on:** P3-T01, P1-T03

### Specification
Configure the Debezium PostgreSQL connector to relay outbox rows to Kafka.

**Connector config file:** `infra/kafka/debezium-outbox-connector.json`

**Key configuration (per `docs/KAFKA-SPEC.md` §4.2):**
- `connector.class: io.debezium.connector.postgresql.PostgresConnector`
- `table.include.list: public.outbox`
- `transforms: outbox` — Debezium EventRouter transform
- Routes to topic specified in `outbox.topic` column
- Uses `outbox.partition_key` as Kafka message key
- Value serialized via Avro + Schema Registry

**Deployment:**
- `POST http://localhost:8083/connectors` with connector config (via Docker Compose startup script)
- `infra/kafka/register-connector.sh` — script to register connector after `docker compose up`

**Acceptance criteria:**
- [ ] Inserting a row into `outbox` results in a message appearing in Kafka UI within 2 seconds
- [ ] Message key matches `partition_key` column value
- [ ] Message topic matches `topic` column value
- [ ] Connector survives PostgreSQL restart (resumes from last WAL position)
- [ ] Connector survives Kafka restart (buffers and replays)

---

## P3-T04 — Kafka Topic Creation and Producer Config
**Tags:** [KAFKA]
**Priority:** P1
**Estimate:** 0.5 days
**Agent:** Coder agent
**Depends on:** P1-T03

### Specification
Create all Kafka topics and configure Spring Kafka producer.

**Topics to create** (per `docs/KAFKA-SPEC.md` §1):
```bash
# infra/kafka/topics.sh
kafka-topics.sh --create --topic tenant.bookings.lifecycle --partitions 12 --replication-factor 1
kafka-topics.sh --create --topic tenant.bookings.lifecycle.DLQ --partitions 3 --replication-factor 1
kafka-topics.sh --create --topic tenant.notifications.outbound --partitions 6 --replication-factor 1
kafka-topics.sh --create --topic tenant.audit.events --partitions 6 --replication-factor 1
```

(Replication factor 1 for local dev; 3 for production.)

**Producer configuration** (`application.yml`):
```yaml
spring.kafka.producer:
  acks: all
  retries: 3
  properties:
    enable.idempotence: true
    max.in.flight.requests.per.connection: 5
  key-serializer: org.apache.kafka.common.serialization.StringSerializer
  value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
```

**Acceptance criteria:**
- [ ] All 4 topics visible in Kafka UI
- [ ] Producer config matches `docs/KAFKA-SPEC.md` §7 exactly
- [ ] Producing a test message to each topic succeeds
- [ ] Topics created automatically on `docker compose up` (topics.sh wired to init service)

---

## P3-T05 — Avro Schemas and Schema Registry Registration
**Tags:** [KAFKA]
**Priority:** P1
**Estimate:** 1 day
**Agent:** Coder agent
**Depends on:** P3-T04

### Specification
Define Avro schemas for all Kafka topics and register them with Schema Registry.

**Schema files** (in `infra/kafka/schemas/`):
- `booking-lifecycle-event.avsc` — `BookingLifecycleEvent` (per `docs/KAFKA-SPEC.md` §3.1)
- `notification-command.avsc` — `NotificationCommand` (per §3.2)
- `audit-event.avsc` — `AuditEvent` (per §3.3)

**Registration script:** `infra/kafka/register-schemas.sh`
```bash
# Register with BACKWARD compatibility
curl -X POST http://localhost:8081/subjects/tenant.bookings.lifecycle-value/versions \
  -H "Content-Type: application/vnd.schemaregistry.v1+json" \
  -d @booking-lifecycle-event-wrapped.json
```

**Java Avro classes:**
- Generate Java classes from `.avsc` files using `avro-maven-plugin`
- Classes generated into `target/generated-sources/avro/`

**Compatibility policy:** BACKWARD for all schemas (per `docs/KAFKA-SPEC.md` §8).

**Acceptance criteria:**
- [ ] All 3 schemas registered in Schema Registry (verify at `http://localhost:8081/subjects`)
- [ ] Java Avro classes generated and compile successfully
- [ ] Schema Registry rejects a test incompatible schema change (e.g., adding required field without default)

---

## P3-T06 — Integrate Outbox Write into BookingService
**Tags:** [KAFKA]
**Priority:** P1
**Estimate:** 1 day
**Agent:** Coder agent
**Depends on:** P3-T02, P3-T05, P2-T10, P2-T11

### Specification
Modify `BookingService.confirmBooking()` and `cancelBooking()` to write outbox events atomically.

**confirmBooking() update:**
```
@Transactional
...existing logic...
4. UPDATE Booking(status=CONFIRMED, ...)  ← existing
5. outboxService.writeBookingEvent(booking, "BookingConfirmed")  ← NEW (same transaction)
```

**cancelBooking() update:**
```
@Transactional
...existing logic...
4. UPDATE Booking(status=CANCELLED, ...)  ← existing
5. outboxService.writeBookingEvent(booking, "BookingCancelled")  ← NEW
```

**createHold() update:**
```
@Transactional
...existing logic...
5. INSERT Booking(status=PENDING_HOLD, ...)  ← existing
6. outboxService.writeBookingEvent(booking, "BookingHeld")  ← NEW
```

**Event payload construction** (must match Avro schema):
- All required fields populated
- `ipAddress` extracted from `HttpServletRequest` via `RequestContextHolder`
- `metadata` map includes `tenantSlug`, `serviceTypeName`

**Acceptance criteria:**
- [ ] Confirming a booking writes both DB update and outbox row in single transaction
- [ ] Simulated DB rollback after confirm → no outbox row written (atomic)
- [ ] Within 2 seconds of confirm, event appears in `tenant.bookings.lifecycle` topic
- [ ] Event payload validates against registered Avro schema

---

## P3-T07 — Notification Service Project Scaffold
**Tags:** [KAFKA]
**Priority:** P1
**Estimate:** 0.5 days
**Agent:** Coder agent
**Depends on:** P1-T01

### Specification
Create the `services/notification-service` Spring Boot project.

**Maven dependencies:**
- `spring-boot-starter-web`
- `spring-kafka`
- `spring-boot-starter-data-jpa` (for `processed_events` table)
- `aws-java-sdk-sesv2`
- `twilio`
- `confluent kafka-avro-serializer`

**Application config:**
- Connects to same PostgreSQL instance (for `processed_events` table)
- Connects to Kafka cluster
- Connects to Schema Registry
- Consumer group: `notification-consumers`

**Acceptance criteria:**
- [ ] `notification-service` starts and connects to Kafka without errors
- [ ] Service included in `docker-compose.yml` as a separate container
- [ ] Health endpoint `/health` returns `{"status":"UP"}`

---

## P3-T08 — NotificationConsumer — Idempotency and Dispatch
**Tags:** [KAFKA]
**Priority:** P1
**Estimate:** 1.5 days
**Agent:** Coder agent
**Depends on:** P3-T07, P3-T06

### Specification
Implement the Kafka consumer that dispatches email/SMS notifications.

**NotificationConsumer:**
```java
@KafkaListener(topics = "tenant.bookings.lifecycle", groupId = "notification-consumers")
public void onBookingEvent(ConsumerRecord<String, BookingLifecycleEvent> record) {
    // Idempotency check (per docs/KAFKA-SPEC.md §5)
    if (processedEventsRepository.existsByConsumerGroupAndMessageKey(
            "notification-consumers", record.key())) {
        log.info("Duplicate event {} — skipping", record.key());
        return;
    }

    // Process event
    switch (record.value().getEventType()) {
        case "BookingConfirmed" -> sendConfirmation(record.value());
        case "BookingCancelled" -> sendCancellation(record.value());
    }

    // Mark as processed (within transaction)
    processedEventsRepository.save(new ProcessedEvent(...));
    acknowledgment.acknowledge();
}
```

**Templates:**
- Confirmation email: subject, booking summary, confirmation code, calendar add link (.ics)
- Cancellation email: booking summary, refund policy note (configurable per tenant)
- SMS: condensed 1-line confirmation with confirmation code

**Acceptance criteria:**
- [ ] `CONFIRMED` event → email and/or SMS sent (based on user's identifier type)
- [ ] `CANCELLED` event → cancellation notice sent
- [ ] Duplicate message delivery → single notification sent (idempotency verified)
- [ ] If SES/Twilio returns error → consumer marks message for retry (up to 3 attempts)
- [ ] After 3 failures → message routes to DLQ `tenant.bookings.lifecycle.DLQ`

---

## P3-T09 — Audit Service Project Scaffold
**Tags:** [KAFKA]
**Priority:** P1
**Estimate:** 0.5 days
**Agent:** Coder agent
**Depends on:** P1-T01, P3-T01

### Specification
Create the `services/audit-service` Spring Boot project.

**Setup requirements:**
- Connects to PostgreSQL using `audit_writer` role (INSERT only on `audit_log`)
- Consumer group: `audit-consumers`
- Health endpoint exposed

**Acceptance criteria:**
- [ ] `audit-service` starts and connects to Kafka
- [ ] Connects to PostgreSQL with `audit_writer` role (not the main app user)
- [ ] Included in `docker-compose.yml`

---

## P3-T10 — AuditConsumer — Append-Only Audit Log Write
**Tags:** [KAFKA]
**Priority:** P1
**Estimate:** 1 day
**Agent:** Coder agent
**Depends on:** P3-T09, P3-T06

### Specification
Implement the Kafka consumer that writes immutable audit log records.

**AuditConsumer:**
- Consumes `tenant.bookings.lifecycle`; consumer group: `audit-consumers`
- Idempotency check using same `processed_events` pattern (own consumer group entry)
- Maps `BookingLifecycleEvent` → `AuditLogRecord` with all HIPAA fields (per `docs/SECURITY-SPEC.md` §5.1)
- Inserts record using `audit_writer` role (INSERT only — no UPDATE ever)

**HIPAA field mapping:**
```
auditLog.who         = event.userId
auditLog.what        = event.eventType
auditLog.when        = event.occurredAt
auditLog.tenantId    = event.tenantId
auditLog.bookingId   = event.bookingId
auditLog.resourceId  = event.resourceId
auditLog.ipAddress   = event.ipAddress
auditLog.metadata    = { "previousStatus": event.previousStatus, "newStatus": event.newStatus }
```

**Acceptance criteria:**
- [ ] Every `BookingLifecycleEvent` produces exactly one `audit_log` row
- [ ] `audit_log` rows are never updated (test by attempting UPDATE with audit_writer — assert permission denied)
- [ ] All HIPAA fields present in every row
- [ ] Idempotency: duplicate message → single audit row

---

## P3-T11 — Outbox Chaos Test
**Tags:** [TEST] [KAFKA]
**Priority:** P1
**Estimate:** 1 day
**Agent:** Test-gen agent
**Depends on:** P3-T06, P3-T03

### Specification
Verify outbox pattern reliability under failure conditions.

**Test scenarios:**

1. **Kafka down at time of booking confirmation**
   - Stop Kafka container; confirm booking; restart Kafka
   - Assert: booking status = CONFIRMED in DB; event eventually appears in Kafka topic
   - Assert: notification sent after Kafka recovers

2. **Database rollback mid-transaction**
   - Inject exception after `UPDATE bookings` but before `outboxService.writeBookingEvent()`
   - Transaction rolls back; assert: booking status NOT updated; NO outbox row written

3. **Debezium restart mid-relay**
   - Insert outbox row; kill Debezium before it relays; restart Debezium
   - Assert: row relayed to Kafka after restart (no duplicate)

4. **Slow consumer with high lag**
   - Produce 100 booking events; consumer processes 1 per second
   - Assert: all 100 events eventually processed; no data loss

**Acceptance criteria:**
- [ ] All 4 chaos scenarios pass
- [ ] No events lost in any scenario
- [ ] No duplicate notifications in any scenario

---

## P3-T12 — Consumer Idempotency Test
**Tags:** [TEST] [KAFKA]
**Priority:** P1
**Estimate:** 1 day
**Agent:** Test-gen agent
**Depends on:** P3-T08, P3-T10

### Specification
Verify that all Kafka consumers handle duplicate message delivery correctly.

**Test scenarios:**

1. **Duplicate BookingConfirmed delivered to notification-service**
   - Publish same message twice (same message key, same payload)
   - Assert: exactly 1 email/SMS sent; `processed_events` has exactly 1 row

2. **Duplicate BookingConfirmed delivered to audit-service**
   - Publish same message twice
   - Assert: exactly 1 audit_log row written

3. **Consumer restart mid-processing**
   - Consumer receives message; processes email but crashes before acking
   - Restart consumer; message redelivered
   - Assert: only 1 email sent (idempotency check catches it)

4. **Multiple consumer instances (same group)**
   - Two notification-service instances in same consumer group
   - Same message routed to one instance
   - Assert: processed exactly once across both instances

**Acceptance criteria:**
- [ ] All 4 scenarios pass
- [ ] `processed_events` table correctly records one entry per consumer group per message key
- [ ] No duplicate emails or SMS in any scenario
