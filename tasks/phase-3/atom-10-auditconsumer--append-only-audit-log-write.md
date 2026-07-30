---
description: AuditConsumer @KafkaListener that maps BookingLifecycleEvent to AuditLogRecord with all HIPAA fields and writes append-only via audit_writer role, with processed_events idempotency check
---

# ATOM-KAFKA-010: AuditConsumer — Append-Only Audit Log Write

**Status**: ✅ Complete
**Feature**: kafka-event-mesh
**Phase**: 3 (Kafka)
**Tags**: [KAFKA]
**Complexity**: High
**Agent**: coder
**Dependencies**: ATOM-KAFKA-009 (audit-service scaffold), ATOM-KAFKA-006 (BookingService emits events)
**Blocks**: ATOM-KAFKA-012
**PR**: TBD

---

## Overview

This atom implements `AuditConsumer` — a `@KafkaListener` on `tenant.bookings.lifecycle` (consumer group `audit-consumers`) that writes an immutable `AuditLogRecord` to the `audit_log` table for every booking lifecycle event. The consumer uses the same `processed_events` idempotency pattern as `NotificationConsumer`: check before writing, save dedup record, then commit offset. The `audit_writer` PostgreSQL role (INSERT-only, no UPDATE/DELETE) enforces append-only at the DB layer regardless of application logic. Every HIPAA-required field is explicitly mapped from the `BookingLifecycleEvent` Avro schema.

---

## User Story

```
As a System
I want every booking lifecycle event written as an immutable HIPAA audit record
So that a complete, tamper-proof trail of who did what, when, and from where exists for every booking state change
```

---

## Acceptance Criteria

- [ ] **AC-01**: Every `BookingLifecycleEvent` message consumed produces exactly one `audit_log` row — verified by count assertion after publishing N events
- [ ] **AC-02 (Idempotency)**: Duplicate message delivery (same message key, consumer group `audit-consumers`) → exactly 1 `audit_log` row; `processed_events` has exactly 1 row for `(audit-consumers, messageKey)`
- [ ] **AC-03**: `audit_log` rows are never updated — `UPDATE audit_log` executed in the audit-service JPA context throws `DataAccessException` (permission denied from `audit_writer` role RLS)
- [ ] **AC-04**: All HIPAA fields populated in every audit row: `tenantId`, `who` (userId), `what` (eventType), `when_` (occurredAt), `bookingId`, `resourceId`, `ipAddress` (nullable), `metadata` (previousStatus, newStatus)
- [ ] **AC-05**: Kafka offset committed only after both `audit_log` INSERT and `processed_events` INSERT succeed — crash before `acknowledgment.acknowledge()` causes safe redelivery caught by idempotency check
- [ ] **AC-06**: Consumer crash before ack → message redelivered on restart → idempotency check prevents second audit row (only 1 row total)
- [ ] **AC-07 (Tenant isolation)**: `auditLog.tenantId` = `event.tenantId` for every record — no null or wrong-tenant values ever written; all new JPA queries include `tenant_id` in WHERE clause
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) in consumer class, field mappings, or log messages

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `AuditConsumerIT.java` — `shouldWriteOneAuditRow_perEvent` | `AuditConsumer.onBookingEvent()` | 🔜 Planned |
| AC-02 | `AuditConsumerIT.java` — `shouldSkipDuplicate_whenAlreadyProcessed` | `AuditConsumer.onBookingEvent()` | 🔜 Planned |
| AC-03 | `AuditConsumerIT.java` — `shouldRejectUpdate_viaAuditWriterRole` | PostgreSQL RLS + audit_writer role | 🔜 Planned |
| AC-04 | `AuditConsumerIT.java` — `shouldPopulateAllHipaaFields` | `AuditConsumer.mapToAuditRecord()` | 🔜 Planned |
| AC-05 | `AuditConsumerIT.java` — `shouldCommitOffset_afterBothInserts` | `AuditConsumer.onBookingEvent()` | 🔜 Planned |
| AC-06 | `ConsumerIdempotencyIT.java` (ATOM-KAFKA-012) — `shouldWriteOnce_afterCrashAndRedelivery` | `processed_events` check | 🔜 Planned |
| AC-07 | `AuditConsumerIT.java` — `shouldSetTenantId_fromEvent` | `AuditConsumer.mapToAuditRecord()` | 🔜 Planned |
| AC-08 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 0 criteria rewritten, 0 marked TBD -->

---

## Technical Design

### Architecture

`AuditConsumer` follows the same structural pattern as `NotificationConsumer` but with a critical difference: every action it takes is append-only. The consumer maps `BookingLifecycleEvent` → `AuditLogRecord` via an explicit field-by-field mapping method (`mapToAuditRecord()`). The `audit_writer` PostgreSQL role prevents UPDATE and DELETE at the DB layer — verified in the integration test suite. The consumer is `@Transactional`: if the `audit_log` INSERT succeeds but the `processed_events` INSERT fails (duplicate key), the whole transaction rolls back and the offset is not committed, causing a safe redelivery.

### Data Flow / Sequence

```
Kafka delivers ConsumerRecord to AuditConsumer.onBookingEvent()
  1. Check processed_events: existsByConsumerGroupAndMessageKey("audit-consumers", record.key())
     → if true: log "duplicate skipped"; acknowledgment.acknowledge(); return
  2. Map BookingLifecycleEvent → AuditLogRecord (all HIPAA fields explicitly)
  3. auditLogRepository.save(auditRecord)     [INSERT via audit_writer role]
  4. processedEventsRepository.save(ProcessedEvent{consumerGroup="audit-consumers", messageKey})
  5. acknowledgment.acknowledge()              [manual offset commit — last statement]

On DB failure (step 3 or 4):
  → Transaction rolls back
  → Offset not committed → Kafka redelivers
  → If step 4 fails with unique constraint (duplicate) → step 3 also rolls back safely
```

### File Structure

```
services/audit-service/src/main/java/com/scheduler/audit/
├── consumer/
│   └── AuditConsumer.java                  ← @KafkaListener, idempotency, HIPAA mapping
├── config/
│   └── KafkaConsumerConfig.java            ← AckMode.MANUAL_IMMEDIATE
└── domain/
    ├── AuditLogRecord.java                 ← (from ATOM-KAFKA-009)
    └── ProcessedEvent.java                 ← (from ATOM-KAFKA-009)

services/audit-service/src/test/java/com/scheduler/audit/
└── consumer/AuditConsumerIT.java           ← integration tests
```

### Interface Contracts

```java
// AuditConsumer — method signatures only
@Component
public class AuditConsumer {

    @KafkaListener(
        topics = "tenant.bookings.lifecycle",
        groupId = "audit-consumers",
        containerFactory = "bookingEventListenerContainerFactory"
    )
    @Transactional
    public void onBookingEvent(
        ConsumerRecord<String, BookingLifecycleEvent> record,
        Acknowledgment acknowledgment
    );

    // Private mapping method — explicitly maps all HIPAA fields
    // See docs/SECURITY-SPEC.md §5.1 for field requirements
    private AuditLogRecord mapToAuditRecord(BookingLifecycleEvent event);
}

// AuditLogRepository — no update or delete query methods
public interface AuditLogRepository extends JpaRepository<AuditLogRecord, Long> {
    // Only save() is used in production code
    // findByTenantId() available for test verification only
    List<AuditLogRecord> findByTenantId(UUID tenantId);
}

// KafkaConsumerConfig — same AckMode pattern as notification-service
@Configuration
public class KafkaConsumerConfig {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, BookingLifecycleEvent>
        bookingEventListenerContainerFactory(
            ConsumerFactory<String, BookingLifecycleEvent> consumerFactory
        );
}
```

### Design Rationale

- **HIPAA compliance**: All required audit fields (who, what, when, tenantId, resourceId, bookingId, ipAddress) are explicitly mapped — no field omission silently succeeds.
- **Why `audit_writer` role enforces append-only better than code convention**: A developer could accidentally add `auditLogRepository.save(existingRecord)` with modified fields. The `audit_writer` role's missing UPDATE grant makes this a DB-level error, not a code review catch.
- **ADR-003**: The `processed_events` deduplication on the consumer side completes the at-least-once → effectively-once guarantee. Debezium delivers at-least-once; `processed_events` makes it exactly-once for audit writes.
- **Why `@Transactional` wraps audit INSERT + processed_events INSERT**: If `audit_log` INSERT succeeds but `processed_events` INSERT fails (unique constraint — message already processed), the transaction rolls back both. The offset is not committed. On redelivery, the idempotency check finds the existing `processed_events` row and skips — no duplicate audit row.
- **Why explicit `mapToAuditRecord()` method (not AutoMapper/ModelMapper)**: Audit field mapping must be explicit and auditable itself. An implicit mapping could silently drop a HIPAA field if the Avro schema evolves. The explicit method fails to compile if a referenced field is renamed.
- **NFR-2.1 compliance**: The `processed_events` deduplication table combined with `AckMode.MANUAL_IMMEDIATE` enforces idempotent consumer behavior.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL + Kafka) — `audit_writer` role used in datasource

```
- shouldWriteOneAuditRow_perEvent:
    Given: 5 distinct BookingLifecycleEvents published to tenant.bookings.lifecycle
    Assert: audit_log.count() = 5 after consumer processes all messages

- shouldSkipDuplicate_whenAlreadyProcessed:
    Given: ProcessedEvent row exists for ("audit-consumers", messageKey)
    When: same message delivered again
    Assert: AuditLogRepository.save() never called (mock verifies 0 invocations)
            audit_log row count unchanged

- shouldRejectUpdate_viaAuditWriterRole:
    Given: 1 audit_log row exists; audit-service datasource (audit_writer role)
    Assert: entityManager.createNativeQuery("UPDATE audit_log SET what='X' WHERE id=:id")
            throws DataAccessException with message containing "permission denied"

- shouldPopulateAllHipaaFields:
    Given: BookingLifecycleEvent with all fields populated (including ipAddress)
    Assert: saved AuditLogRecord has non-null tenantId, who, what, when_, bookingId, resourceId;
            metadata contains "previousStatus" and "newStatus" keys

- shouldSetTenantId_fromEvent:
    Given: event with tenantId = "tenant-uuid-abc"
    Assert: saved AuditLogRecord.tenantId = UUID("tenant-uuid-abc")

- shouldCommitOffset_afterBothInserts:
    Given: successful audit_log INSERT + processed_events INSERT
    Assert: Acknowledgment.acknowledge() called; consumer group lag = 0 for that partition

- shouldBeIdempotent_onDuplicateMessage:
    Given: same Kafka message key delivered twice
    Assert: processed_events has exactly 1 row for ("audit-consumers", messageKey)
            audit_log has exactly 1 row for that bookingId + eventType combination
```

**Coverage requirements**:
- Line coverage ≥ 80% on `AuditConsumer`
- Idempotency test (AC-02) is mandatory — no merge without it
- `audit_writer` UPDATE rejection test (AC-03) is mandatory

---

## Implementation Constraints

- `processed_events` table must be checked BEFORE any audit write — idempotency check is the first statement
- `acknowledgment.acknowledge()` must be the LAST statement in the happy path
- `AckMode.MANUAL_IMMEDIATE` must be set in `KafkaConsumerConfig`
- `audit_log` is INSERT-only — `AuditLogRepository` must never have `update`, `deleteById`, or custom UPDATE queries
- `AuditLogRecord.tenantId` must always be populated from `event.getTenantId()` — never null
- All HIPAA fields must be explicitly mapped — no implicit or reflective mapping
- `audit_writer` credentials in environment variables — never hardcoded
- Consumers must check `processed_events` table before processing (NFR-2.1)
- No `System.out.println` — use SLF4J structured logging
- `@Transactional` wraps both `audit_log` INSERT and `processed_events` INSERT atomically
- Outbox event written within `@Transactional` scope (if booking state mutated)

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `AuditConsumerIT.java` with Testcontainers (PostgreSQL + Kafka) using `audit_writer` role datasource
2. Write `shouldWriteOneAuditRow_perEvent` — assert it fails (no consumer yet)
3. Write `shouldSkipDuplicate_whenAlreadyProcessed` — assert it fails
4. Write `shouldRejectUpdate_viaAuditWriterRole` — assert it fails (datasource not yet `audit_writer`)
5. Write `shouldPopulateAllHipaaFields` — assert it fails

### GREEN — Minimum code to pass

1. Create `KafkaConsumerConfig.java` with `AckMode.MANUAL_IMMEDIATE`
2. Create `AuditConsumer.java` with `@KafkaListener`, idempotency check, `mapToAuditRecord()`, saves, acknowledge
3. Implement `mapToAuditRecord()` with explicit field-by-field mapping of all HIPAA fields
4. Confirm datasource in `application.yml` uses `audit_writer` role

### REFACTOR — Quality pass

1. Add structured logging: `log.info("audit record written: eventType={}, bookingId={}, tenantId={}", ...)`
2. Add Javadoc to `mapToAuditRecord()` citing HIPAA field requirements and `docs/SECURITY-SPEC.md` §5.1
3. Run `/security-scan` on `AuditConsumer`
4. Verify no query on `AuditLogRepository` allows UPDATE by attempting `auditLogRepository.save(existingEntity)` in test

---

## Implementation Reference

### AuditConsumer

**File**: `services/audit-service/src/main/java/com/scheduler/audit/consumer/AuditConsumer.java`

```java
// [TASK: ATOM-KAFKA-010]
package com.scheduler.audit.consumer;

import com.scheduler.avro.BookingLifecycleEvent;
import com.scheduler.audit.domain.AuditLogRecord;
import com.scheduler.audit.domain.ProcessedEvent;
import com.scheduler.audit.repository.AuditLogRepository;
import com.scheduler.audit.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuditConsumer {

    private static final String CONSUMER_GROUP = "audit-consumers";

    private final AuditLogRepository auditLogRepository;
    private final ProcessedEventRepository processedEventsRepository;

    /**
     * Consumes all booking lifecycle events and writes immutable HIPAA audit records.
     * Idempotency enforced via processed_events table (NFR-2.1, ADR-003).
     * audit_writer PostgreSQL role enforces INSERT-only at the DB layer.
     */
    @KafkaListener(
        topics = "tenant.bookings.lifecycle",
        groupId = CONSUMER_GROUP,
        containerFactory = "bookingEventListenerContainerFactory"
    )
    @Transactional
    public void onBookingEvent(
            ConsumerRecord<String, BookingLifecycleEvent> record,
            Acknowledgment acknowledgment) {

        String messageKey = record.key();

        // Idempotency check — must be first (NFR-2.1, ADR-003)
        if (processedEventsRepository.existsByConsumerGroupAndMessageKey(CONSUMER_GROUP, messageKey)) {
            log.info("duplicate audit event skipped: consumerGroup={}, messageKey={}", CONSUMER_GROUP, messageKey);
            acknowledgment.acknowledge();
            return;
        }

        BookingLifecycleEvent event = record.value();

        // Map all HIPAA fields explicitly (docs/SECURITY-SPEC.md §5.1)
        AuditLogRecord auditRecord = mapToAuditRecord(event);
        auditLogRepository.save(auditRecord);

        // Save deduplication record — must precede acknowledge
        processedEventsRepository.save(ProcessedEvent.builder()
            .consumerGroup(CONSUMER_GROUP)
            .messageKey(messageKey)
            .build());

        // Manual offset commit — last statement (AckMode.MANUAL_IMMEDIATE)
        acknowledgment.acknowledge();
        log.info("audit record written: eventType={}, bookingId={}, tenantId={}",
            event.getEventType(), event.getBookingId(), event.getTenantId());
    }

    /**
     * Maps BookingLifecycleEvent Avro fields to AuditLogRecord with all HIPAA-required fields.
     * Explicit field-by-field mapping — no implicit mapper to prevent silent field omission.
     * See docs/SECURITY-SPEC.md §5.1 for HIPAA field requirements.
     *
     * HIPAA field mapping (per PHASE-3-TASKS.md P3-T10):
     *   auditLog.who         = event.userId
     *   auditLog.what        = event.eventType
     *   auditLog.when_       = event.occurredAt
     *   auditLog.tenantId    = event.tenantId
     *   auditLog.bookingId   = event.bookingId
     *   auditLog.resourceId  = event.resourceId
     *   auditLog.ipAddress   = event.ipAddress
     *   auditLog.metadata    = { "previousStatus": ..., "newStatus": ... }
     */
    private AuditLogRecord mapToAuditRecord(BookingLifecycleEvent event) {
        return AuditLogRecord.builder()
            .tenantId(UUID.fromString(event.getTenantId()))        // required — tenant isolation
            .who(UUID.fromString(event.getUserId()))                // HIPAA: who
            .what(event.getEventType())                             // HIPAA: what
            .when_(Instant.parse(event.getOccurredAt()))            // HIPAA: when
            .bookingId(UUID.fromString(event.getBookingId()))
            .resourceId(UUID.fromString(event.getResourceId()))
            .ipAddress(event.getIpAddress() != null
                ? event.getIpAddress().toString() : null)
            .metadata(Map.of(
                "previousStatus", event.getPreviousStatus() != null
                    ? event.getPreviousStatus().toString() : "",
                "newStatus", event.getNewStatus() != null
                    ? event.getNewStatus().toString() : ""
            ))
            .build();
    }
}
```

### KafkaConsumerConfig (audit-service)

**File**: `services/audit-service/src/main/java/com/scheduler/audit/config/KafkaConsumerConfig.java`

```java
// [TASK: ATOM-KAFKA-010]
package com.scheduler.audit.config;

import com.scheduler.avro.BookingLifecycleEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BookingLifecycleEvent>
            bookingEventListenerContainerFactory(
                ConsumerFactory<String, BookingLifecycleEvent> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, BookingLifecycleEvent> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // Manual offset commit — required for idempotency pattern (NFR-2.1)
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        return factory;
    }
}
```

---

## Integration Points

**Depends on**: ATOM-KAFKA-009 (audit-service scaffold, AuditLogRepository, ProcessedEventRepository), ATOM-KAFKA-006 (BookingService emits events to topic)

**Enables**: ATOM-KAFKA-012 (consumer idempotency tests exercise this consumer)

**Cascading updates required**:
- `docs/KAFKA-SPEC.md` — document consumer group `audit-consumers` in §5
- `docs/SECURITY-SPEC.md` — confirm HIPAA field mapping in §5.1
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `services/audit-service/src/main/java/com/scheduler/audit/consumer/AuditConsumer.java` | New | @KafkaListener with idempotency + HIPAA mapping |
| `services/audit-service/src/main/java/com/scheduler/audit/config/KafkaConsumerConfig.java` | New | AckMode.MANUAL_IMMEDIATE |
| `services/audit-service/src/test/java/com/scheduler/audit/consumer/AuditConsumerIT.java` | New | Integration tests |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] Idempotency test (AC-02) present and passing — no merge without it
- [ ] `audit_writer` UPDATE rejection test (AC-03) present and passing — no merge without it
- [ ] All HIPAA fields explicitly mapped in `mapToAuditRecord()` — none omitted
- [ ] `processed_events` checked BEFORE audit write (first statement)
- [ ] `acknowledgment.acknowledge()` is LAST statement in happy path
- [ ] `AckMode.MANUAL_IMMEDIATE` set in `KafkaConsumerConfig`
- [ ] `@Transactional` wraps both `audit_log` INSERT and `processed_events` INSERT
- [ ] `AuditLogRecord.tenantId` never null — verified in test
- [ ] `AuditLogRepository` has no update or delete query methods
- [ ] Zero JPA queries without `tenant_id` in WHERE clause
- [ ] Zero industry-specific terms in any identifier or API path
- [ ] `@PreAuthorize` present on every new `@RestController` method
- [ ] Outbox event written within `@Transactional` scope (if booking state mutated)
- [ ] HIPAA field mapping Javadoc references `docs/SECURITY-SPEC.md` §5.1
- [ ] NFR-2.1 referenced in consumer Javadoc
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: kafka-event-mesh | Phase: 3*
