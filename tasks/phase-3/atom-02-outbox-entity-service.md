---
description: OutboxEntity JPA mapping and OutboxService with Propagation.MANDATORY — the write side of the transactional outbox pattern
---

# ATOM-KAFKA-002: Outbox Entity and OutboxService

**Status**: 🟡 Planned
**Feature**: kafka-event-mesh
**Phase**: 3 (Kafka)
**Tags**: [KAFKA]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-KAFKA-001 (outbox table must exist)
**Blocks**: ATOM-KAFKA-006
**PR**: TBD

---

## Overview

This atom implements the write side of the transactional outbox pattern (ADR-003): a JPA entity mapping the `outbox` table and an `OutboxService` that inserts an outbox row within the caller's existing transaction. `OutboxService.writeBookingEvent()` uses `Propagation.MANDATORY` — it must never open its own transaction, ensuring the outbox row and the booking state change are always committed together or rolled back together. Any call outside an active transaction throws `IllegalTransactionStateException` immediately.

---

## User Story

```
As a System
I want outbox rows written atomically within the caller's @Transactional scope
So that booking state and the matching Kafka event payload are never split across a transaction boundary
```

---

## Acceptance Criteria

- [ ] **AC-01**: `OutboxService.writeBookingEvent()` inserts exactly one `outbox` row within the same transaction as the caller — verified by rollback test (rolled-back caller transaction → zero outbox rows)
- [ ] **AC-02**: Calling `writeBookingEvent()` outside an active transaction throws `IllegalTransactionStateException`
- [ ] **AC-03**: Payload JSON field names match `BookingLifecycleEvent` Avro schema field names exactly (see ATOM-KAFKA-005)
- [ ] **AC-04**: `outbox.id` is assigned via `UUID.randomUUID()` — `@GeneratedValue` is absent
- [ ] **AC-05**: `outbox.partition_key` equals `booking.getId().toString()`
- [ ] **AC-06**: `outbox.topic` is always `"tenant.bookings.lifecycle"`
- [ ] **AC-07 (Idempotency)**: Two consecutive calls with the same booking and different `eventType` values produce two separate outbox rows with distinct `id` values
- [ ] **AC-08 (Tenant isolation)**: `outbox.aggregate_id` equals the booking's `id`; `payload` includes `tenantId` field matching the booking's `tenant_id`
- [ ] **AC-09 (Domain abstraction)**: No industry-specific terms in class names, field names, or payload keys

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `OutboxServiceIT.java` — `shouldInsertOutboxRow_withinCallerTransaction` | `OutboxService.writeBookingEvent()` | 🔜 Planned |
| AC-02 | `OutboxServiceIT.java` — `shouldThrow_whenCalledOutsideTransaction` | `OutboxService.writeBookingEvent()` | 🔜 Planned |
| AC-03 | `OutboxServiceTest.java` — `shouldBuildPayload_matchingAvroSchema` | `OutboxService.buildPayload()` | 🔜 Planned |
| AC-04 | `OutboxServiceTest.java` — `shouldAssignRandomUuid_notGeneratedValue` | `OutboxEntity` | 🔜 Planned |
| AC-05 | `OutboxServiceTest.java` — `shouldSetPartitionKey_toBookingId` | `OutboxService.writeBookingEvent()` | 🔜 Planned |
| AC-07 | `OutboxServiceIT.java` — `shouldProduceTwoRows_forTwoDifferentEventTypes` | `OutboxService.writeBookingEvent()` | 🔜 Planned |

<!-- AC validation passed: TBD, 9 criteria written, 6 mapped -->

---

## Technical Design

### Architecture

`OutboxService` is a thin persistence helper — it has no business logic and no own `@Transactional` annotation. It uses Spring's `Propagation.MANDATORY` to enforce that a caller-owned transaction is always active. The `OutboxEntity` maps directly to the `outbox` table schema from ATOM-KAFKA-001. The UUID `id` is set by the service before calling `outboxRepository.save()` so the value is known to the caller before the transaction commits (useful for tracing).

### Data Flow / Sequence

```
BookingService.confirmBooking()        [owns @Transactional]
  → UPDATE bookings SET status=CONFIRMED
  → outboxService.writeBookingEvent(booking, "BookingConfirmed")
      → builds payload Map matching BookingLifecycleEvent Avro fields
      → outboxRepository.save(OutboxEntity{id=random, status=PENDING, ...})
  → DB commit (bookings row + outbox row committed atomically)
  → Debezium CDC detects outbox INSERT
  → Kafka topic: tenant.bookings.lifecycle
```

### File Structure

```
apps/api/src/main/java/com/scheduler/
├── domain/
│   └── entity/
│       └── OutboxEntity.java           ← JPA entity for outbox table
├── repository/
│   └── OutboxRepository.java           ← JpaRepository<OutboxEntity, UUID>
└── service/
    └── OutboxService.java              ← writeBookingEvent() — Propagation.MANDATORY

apps/api/src/test/java/com/scheduler/
├── service/OutboxServiceTest.java      ← Unit tests (payload shape, UUID assignment)
└── service/OutboxServiceIT.java        ← Integration tests (Testcontainers, rollback)
```

### Interface Contracts

```java
// JPA Entity
@Entity
@Table(name = "outbox")
public class OutboxEntity {
    @Id
    private UUID id;                        // UUID.randomUUID() — no @GeneratedValue

    @Column(nullable = false)
    private String aggregateType;           // "Booking"

    @Column(nullable = false)
    private UUID aggregateId;               // booking.getId()

    @Column(nullable = false)
    private String eventType;               // e.g. "BookingConfirmed"

    @Column(nullable = false)
    private String topic;                   // "tenant.bookings.lifecycle"

    @Column(nullable = false)
    private String partitionKey;            // booking.getId().toString()

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;    // matches BookingLifecycleEvent Avro fields

    @Column(nullable = false)
    private String status;                  // "PENDING"

    @Column(nullable = false)
    private Instant createdAt;
}

// Repository — no custom queries needed; save() is sufficient
public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {}

// Service interface
public interface OutboxService {
    // Propagation.MANDATORY — must be called within an active @Transactional
    void writeBookingEvent(Booking booking, String eventType);
}
```

### Design Rationale

- **ADR-003 (Transactional Outbox)**: Writing the outbox row in the same transaction as the booking state mutation prevents the dual-write problem — if Kafka is unavailable, the DB transaction still commits and Debezium relays the event when Kafka recovers.
- **Why `Propagation.MANDATORY` (not `REQUIRED`)**: `REQUIRED` would silently create a new transaction if none exists, allowing callers to accidentally bypass atomicity. `MANDATORY` makes misuse a runtime exception caught in tests before production.
- **Why `UUID.randomUUID()` (not `@GeneratedValue`)**: The ID must be known to the caller before the transaction commits for distributed tracing. `@GeneratedValue` defers assignment to flush time, making the ID unavailable earlier.
- **Why no `@Transactional` on `OutboxService`**: Adding `@Transactional` at the method level with `MANDATORY` is redundant — the annotation serves only as documentation of the propagation contract. The implementation uses `@Transactional(propagation = Propagation.MANDATORY)` to make the contract explicit and enforceable by Spring.

---

## Test Strategy

**Test type**: Unit (JUnit 5 + Mockito) and Integration (Testcontainers + PostgreSQL)

```
- shouldInsertOutboxRow_withinCallerTransaction:
    Given: active @Transactional scope; a confirmed Booking entity
    Assert: after writeBookingEvent(), outboxRepository.findAll() returns exactly 1 row
            with status=PENDING, partitionKey=booking.getId().toString()

- shouldRollbackOutboxRow_whenCallerRollsBack:
    Given: active @Transactional scope; writeBookingEvent() called successfully
    When: caller transaction is rolled back (runtime exception thrown)
    Assert: outbox table contains zero rows (both booking and outbox rolled back)

- shouldThrow_whenCalledOutsideTransaction:
    Given: no active transaction (no @Transactional annotation on test method)
    Assert: writeBookingEvent() throws IllegalTransactionStateException

- shouldBuildPayload_matchingAvroSchema:
    Given: a fully populated Booking entity
    Assert: payload map contains keys: eventId, eventType, occurredAt, tenantId,
            bookingId, resourceId, serviceTypeId, userId, slotStart, slotEnd,
            previousStatus, newStatus — all non-null

- shouldAssignRandomUuid_notGeneratedValue:
    Given: writeBookingEvent() called
    Assert: saved OutboxEntity.getId() is non-null; calling again produces a different UUID

- shouldBeIdempotent_onDuplicateMessage:
    Given: same Kafka message key delivered twice to a consumer
    Assert: processed_events has exactly 1 row for that key; outbox write triggered exactly once
```

**Coverage requirements**:
- Line coverage ≥ 80% on `OutboxService`
- Idempotency test required (duplicate message delivery via `processed_events` check)

---

## Implementation Constraints

- `OutboxService` must use `@Transactional(propagation = Propagation.MANDATORY)` — no plain `@Transactional`
- `OutboxEntity.id` must be set to `UUID.randomUUID()` before calling `save()` — never use `@GeneratedValue`
- Direct Kafka writes from business transactions are BLOCKED — use `outboxService.writeBookingEvent()` only
- Payload field names must match `BookingLifecycleEvent` Avro schema exactly (verified in unit test)
- No `System.out.println` — use SLF4J `log.info()`
- DTOs must be Java 21 records (the `OutboxEntity` itself is a JPA entity class, not a record)
- Consumers must check `processed_events` table before processing (enforced in ATOM-KAFKA-008 and ATOM-KAFKA-010)
- Audit log is INSERT-only — `OutboxService` never touches `audit_log` directly

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `OutboxServiceTest.java` with Mockito setup
2. Write `shouldBuildPayload_matchingAvroSchema` — assert it fails (service doesn't exist yet)
3. Write `shouldAssignRandomUuid_notGeneratedValue` — assert it fails
4. Create `OutboxServiceIT.java` with Testcontainers PostgreSQL + running migrations
5. Write `shouldInsertOutboxRow_withinCallerTransaction` — assert it fails
6. Write `shouldThrow_whenCalledOutsideTransaction` — assert it fails

### GREEN — Minimum code to pass

1. Create `OutboxEntity.java` with all fields, `@PrePersist` for `createdAt`
2. Create `OutboxRepository.java` extending `JpaRepository<OutboxEntity, UUID>`
3. Implement `OutboxService.java` with `writeBookingEvent()` using `Propagation.MANDATORY`
4. Implement `buildPayload()` returning a `Map` matching Avro field names

### REFACTOR — Quality pass

1. Add SLF4J structured logging: `log.debug("outbox row written: id={}, eventType={}, bookingId={}", ...)`
2. Add Javadoc to `writeBookingEvent()` documenting the `Propagation.MANDATORY` contract
3. Extract Avro field name constants to avoid stringly-typed key mismatches
4. Run `/security-scan` on new service

---

## Implementation Reference

### OutboxEntity

**File**: `apps/api/src/main/java/com/scheduler/domain/entity/OutboxEntity.java`

```java
// [TASK: ATOM-KAFKA-002]
package com.scheduler.domain.entity;

import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "outbox")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEntity {

    @Id
    private UUID id;   // set to UUID.randomUUID() before insert — never use @GeneratedValue

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private UUID aggregateId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false)
    private String partitionKey;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload;

    @Column(nullable = false)
    private String status;   // PENDING

    @Column(nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
```

### OutboxRepository

**File**: `apps/api/src/main/java/com/scheduler/repository/OutboxRepository.java`

```java
// [TASK: ATOM-KAFKA-002]
package com.scheduler.repository;

import com.scheduler.domain.entity.OutboxEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxEntity, UUID> {}
```

### OutboxService

**File**: `apps/api/src/main/java/com/scheduler/service/OutboxService.java`

```java
// [TASK: ATOM-KAFKA-002]
package com.scheduler.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.domain.entity.Booking;
import com.scheduler.domain.entity.OutboxEntity;
import com.scheduler.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    /**
     * Writes a booking lifecycle event to the outbox table.
     * <p>
     * MUST be called within an active @Transactional scope (Propagation.MANDATORY).
     * If called outside a transaction, throws IllegalTransactionStateException immediately.
     * The outbox row and the caller's booking state change are committed atomically.
     *
     * @param booking   the booking entity whose state just changed
     * @param eventType the Avro event type string (e.g. "BookingConfirmed")
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void writeBookingEvent(Booking booking, String eventType) {
        UUID outboxId = UUID.randomUUID();
        OutboxEntity outbox = OutboxEntity.builder()
            .id(outboxId)
            .aggregateType("Booking")
            .aggregateId(booking.getId())
            .eventType(eventType)
            .topic("tenant.bookings.lifecycle")
            .partitionKey(booking.getId().toString())
            .payload(buildPayload(booking, eventType))
            .status("PENDING")
            .build();
        outboxRepository.save(outbox);
        log.debug("outbox row written: id={}, eventType={}, bookingId={}, tenantId={}",
            outboxId, eventType, booking.getId(), booking.getTenantId());
    }

    private Map<String, Object> buildPayload(Booking booking, String eventType) {
        // Field names must match BookingLifecycleEvent Avro schema exactly (ATOM-KAFKA-005)
        return Map.of(
            "eventId",        UUID.randomUUID().toString(),
            "eventType",      eventType,
            "occurredAt",     Instant.now().toString(),
            "tenantId",       booking.getTenantId().toString(),
            "bookingId",      booking.getId().toString(),
            "resourceId",     booking.getResourceId().toString(),
            "serviceTypeId",  booking.getServiceTypeId().toString(),
            "userId",         booking.getUserId().toString(),
            "slotStart",      booking.getSlotStart().toString(),
            "slotEnd",        booking.getSlotEnd().toString(),
            "previousStatus", derivePreviousStatus(eventType),
            "newStatus",      deriveNewStatus(eventType)
        );
    }

    private String derivePreviousStatus(String eventType) {
        return switch (eventType) {
            case "BookingConfirmed" -> "PENDING_HOLD";
            case "BookingCancelled" -> "CONFIRMED";
            case "BookingHeld"      -> null;
            default -> null;
        };
    }

    private String deriveNewStatus(String eventType) {
        return switch (eventType) {
            case "BookingConfirmed" -> "CONFIRMED";
            case "BookingCancelled" -> "CANCELLED";
            case "BookingHeld"      -> "PENDING_HOLD";
            default -> null;
        };
    }
}
```

---

## Integration Points

**Depends on**: ATOM-KAFKA-001 (`outbox` table must exist), ATOM-PHASE2-xxx (Booking entity must exist)

**Enables**: ATOM-KAFKA-006 (BookingService can now call `outboxService.writeBookingEvent()`)

**Cascading updates required**:
- `docs/KAFKA-SPEC.md` — confirm payload field mapping matches registered Avro schema
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/com/scheduler/domain/entity/OutboxEntity.java` | New | JPA entity for outbox table |
| `apps/api/src/main/java/com/scheduler/repository/OutboxRepository.java` | New | Data access for outbox |
| `apps/api/src/main/java/com/scheduler/service/OutboxService.java` | New | Propagation.MANDATORY outbox writer |
| `apps/api/src/test/java/com/scheduler/service/OutboxServiceTest.java` | New | Unit tests — payload shape, UUID |
| `apps/api/src/test/java/com/scheduler/service/OutboxServiceIT.java` | New | Integration tests — rollback, MANDATORY |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] `OutboxService` uses `Propagation.MANDATORY` — no plain `@Transactional`
- [ ] `OutboxEntity.id` uses `UUID.randomUUID()` — no `@GeneratedValue`
- [ ] No direct Kafka writes anywhere in this atom
- [ ] Payload field names verified against Avro schema in unit test
- [ ] Rollback test confirms zero outbox rows after caller rollback
- [ ] Outside-transaction test confirms `IllegalTransactionStateException`
- [ ] ADR-003 referenced in Javadoc
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: kafka-event-mesh | Phase: 3*
