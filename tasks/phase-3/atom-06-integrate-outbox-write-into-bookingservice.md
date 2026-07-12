---
description: Wire OutboxService.writeBookingEvent() into BookingService.confirmBooking(), cancelBooking(), and createHold() within the same @Transactional scope
---

# ATOM-KAFKA-006: Integrate Outbox Write into BookingService

**Status**: 🟡 Planned
**Feature**: kafka-event-mesh
**Phase**: 3 (Kafka)
**Tags**: [KAFKA]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-KAFKA-002 (OutboxService), ATOM-KAFKA-005 (Avro schemas registered), ATOM-PHASE2-010, ATOM-PHASE2-011 (BookingService must exist)
**Blocks**: ATOM-KAFKA-008, ATOM-KAFKA-010, ATOM-KAFKA-011
**PR**: TBD

---

## Overview

This atom closes the loop between the booking engine and the Kafka event mesh by adding `outboxService.writeBookingEvent()` calls inside the three state-mutating methods of `BookingService`: `confirmBooking()`, `cancelBooking()`, and `createHold()`. Each call is made within the method's existing `@Transactional` scope — no new transaction is opened. If the DB operation rolls back, the outbox row is never written. If the outbox write fails, the booking state change also rolls back. This is the ACID atomicity guarantee of ADR-003.

---

## User Story

```
As a System
I want every booking state change to atomically produce an outbox event
So that the Kafka event mesh reflects booking state with no possibility of silent data loss
```

---

## Acceptance Criteria

- [ ] **AC-01**: `confirmBooking()` writes both `bookings` UPDATE (status=CONFIRMED) and one `outbox` INSERT with `eventType=BookingConfirmed` in a single transaction — verified by rollback test
- [ ] **AC-02**: `cancelBooking()` writes both `bookings` UPDATE (status=CANCELLED) and one `outbox` INSERT with `eventType=BookingCancelled` in a single transaction
- [ ] **AC-03**: `createHold()` writes both `bookings` INSERT (status=PENDING_HOLD) and one `outbox` INSERT with `eventType=BookingHeld` in a single transaction
- [ ] **AC-04**: Simulated DB rollback after `UPDATE bookings` but before `outboxService.writeBookingEvent()` — booking status NOT updated; zero outbox rows written (full rollback)
- [ ] **AC-05**: Within 2 seconds of `confirmBooking()` completing, a message appears in `tenant.bookings.lifecycle` Kafka topic with key = `bookingId`
- [ ] **AC-06**: Event payload validates against the registered `BookingLifecycleEvent` Avro schema
- [ ] **AC-07 (Idempotency)**: Same `bookingId` confirmed twice (concurrent hold expiry race) — `processed_events` table has exactly 1 row for that `bookingId` key per consumer group; second consumer invocation is a no-op
- [ ] **AC-08 (Tenant isolation)**: All new `bookings` queries in this atom include `tenant_id` in WHERE clause; event payload `tenantId` field matches `booking.getTenantId()`
- [ ] **AC-09 (Domain abstraction)**: No industry-specific terms in any method name, variable name, or event type string

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `BookingServiceIT.java` — `shouldWriteOutboxRow_onConfirm` | `BookingService.confirmBooking()` | 🔜 Planned |
| AC-02 | `BookingServiceIT.java` — `shouldWriteOutboxRow_onCancel` | `BookingService.cancelBooking()` | 🔜 Planned |
| AC-03 | `BookingServiceIT.java` — `shouldWriteOutboxRow_onHold` | `BookingService.createHold()` | 🔜 Planned |
| AC-04 | `BookingServiceIT.java` — `shouldRollbackOutbox_whenBookingRollsBack` | `BookingService` + `OutboxService` | 🔜 Planned |
| AC-05 | `BookingServiceIT.java` — `shouldProduceKafkaEvent_withinTwoSeconds` | Debezium + Kafka | 🔜 Planned |
| AC-06 | `BookingServiceIT.java` — `shouldValidatePayload_againstAvroSchema` | `OutboxService.buildPayload()` | 🔜 Planned |
| AC-07 | `BookingServiceIT.java` — `shouldBeIdempotent_onDuplicateConfirm` | `processed_events` + consumer | 🔜 Planned |
| AC-08 | `BookingServiceIT.java` — `shouldIncludeTenantId_inPayload` | `BookingService` + `OutboxService` | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 0 criteria rewritten, 0 marked TBD -->

---

## Technical Design

### Architecture

`BookingService` already owns `@Transactional` on `confirmBooking()`, `cancelBooking()`, and `createHold()`. This atom injects `OutboxService` as a constructor dependency and appends a single `outboxService.writeBookingEvent(booking, eventType)` call at the end of each method body — after the DB mutation, before the method returns. Because `OutboxService` uses `Propagation.MANDATORY`, Spring verifies that an active transaction exists at call time, making misconfiguration a startup-time or test-time error rather than a silent production bug.

### Data Flow / Sequence

```
BookingService.confirmBooking(tenantId, bookingId, ...)  [@Transactional]
  1. SELECT booking ... FOR UPDATE WHERE id=:bookingId AND tenant_id=:tenantId
  2. Validate booking state (must be PENDING_HOLD)
  3. UPDATE bookings SET status='CONFIRMED', confirmation_code=...
  4. outboxService.writeBookingEvent(booking, "BookingConfirmed")
       → INSERT INTO outbox(id, aggregate_type='Booking', aggregate_id=bookingId,
                            event_type='BookingConfirmed',
                            topic='tenant.bookings.lifecycle',
                            partition_key=bookingId, payload={...}, status='PENDING')
  5. DB commit (bookings row + outbox row committed atomically)
  → Debezium CDC picks up outbox INSERT
  → message produced to tenant.bookings.lifecycle within 2 seconds

BookingService.cancelBooking(tenantId, bookingId, ...)  [@Transactional]
  ... same pattern → eventType="BookingCancelled"

BookingService.createHold(tenantId, ...)  [@Transactional]
  ... same pattern → eventType="BookingHeld"
```

### File Structure

```
apps/api/src/main/java/com/scheduler/
└── service/
    └── BookingService.java              ← modified: inject OutboxService, add 3 writeBookingEvent() calls

apps/api/src/test/java/com/scheduler/
└── service/BookingServiceIT.java        ← modified: add outbox + Kafka integration assertions
```

### Interface Contracts

```java
// BookingService — updated signatures only (no bodies):
public interface BookingService {
    // Existing signatures unchanged — only implementations modified:
    BookingResponse confirmBooking(UUID tenantId, UUID bookingId, ConfirmBookingRequest request);
    BookingResponse cancelBooking(UUID tenantId, UUID bookingId, CancelBookingRequest request);
    BookingResponse createHold(UUID tenantId, CreateHoldRequest request);
}

// Constructor injection added to BookingService implementation:
// @RequiredArgsConstructor adds:
//   private final OutboxService outboxService;
```

### Design Rationale

- **ADR-003 (Transactional Outbox)**: The outbox write must be inside the same `@Transactional` method as the booking state mutation — never in a separate transaction or `@Async` method. This atom enforces ADR-003 at the code level.
- **Why after the DB mutation (not before)**: The outbox payload uses the booking's confirmed state (e.g., `confirmationCode` is generated during the UPDATE). Writing before the mutation would capture stale field values.
- **Why `Propagation.MANDATORY` catches errors early**: If a developer extracts `confirmBooking()` into a non-transactional helper and calls `outboxService.writeBookingEvent()` from it, the `MANDATORY` propagation throws at test time — not silently in production.
- **Why no `@Async` on outbox write**: Async execution would run outside the caller's transaction, defeating the atomicity guarantee entirely.
- **`ipAddress` extraction**: `HttpServletRequest` is available via `RequestContextHolder` — extracted in `BookingService` and passed into the booking before the outbox write so the audit trail captures the originating IP.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL + Kafka + Debezium)

```
- shouldWriteOutboxRow_onConfirm:
    Given: booking in PENDING_HOLD state; active transaction
    Assert: after confirmBooking(), outbox table has 1 row with
            eventType='BookingConfirmed', partitionKey=bookingId.toString()

- shouldRollbackOutbox_whenBookingRollsBack:
    Given: exception injected after UPDATE bookings but before writeBookingEvent() returns
    Assert: bookings table — booking status unchanged (still PENDING_HOLD)
            outbox table — zero rows (full rollback)

- shouldProduceKafkaEvent_withinTwoSeconds:
    Given: Debezium running; confirmBooking() called
    Assert: KafkaConsumer.poll() on tenant.bookings.lifecycle receives message
            with key=bookingId within 2000ms

- shouldValidatePayload_againstAvroSchema:
    Given: confirmBooking() completes; outbox row written
    Assert: outbox.payload contains all 13 BookingLifecycleEvent fields (none null except nullable ones)

- shouldBeIdempotent_onDuplicateConfirm:
    Given: same bookingId confirmed twice (simulate duplicate event delivery)
    Assert: processed_events has exactly 1 row for consumer_group='notification-consumers',
            message_key=bookingId; notification sent exactly once

- shouldIncludeTenantId_inPayload:
    Given: booking owned by tenantA
    Assert: outbox.payload['tenantId'] = tenantA.getId().toString()
```

**Coverage requirements**:
- Line coverage ≥ 80% on modified `BookingService` methods
- Rollback test (AC-04) is mandatory — no merge without it
- Idempotency test required for all Kafka-tagged atoms

---

## Implementation Constraints

- `outboxService.writeBookingEvent()` must be the last statement before `return` in each mutating method
- Must be called within the existing `@Transactional` scope — no `@Async`, no `new Thread()`
- OutboxService must use `Propagation.MANDATORY` (enforced by ATOM-KAFKA-002)
- Direct Kafka writes from business transactions are BLOCKED — use `outboxService.writeBookingEvent()` only
- Every `bookings` query must include `tenant_id` in the WHERE clause
- `ipAddress` extracted from `RequestContextHolder` — never from a method parameter (avoid leaking IP in API contracts)
- `extension` JSONB column must never be read in this atom's code — core logic only
- No `System.out.println` — use SLF4J
- Consumers must check `processed_events` table before processing (enforced downstream in ATOM-KAFKA-008, ATOM-KAFKA-010)
- Outbox event written within `@Transactional` scope (if booking state mutated)

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Add to existing `BookingServiceIT.java`:
   - `shouldWriteOutboxRow_onConfirm` — assert it fails (OutboxService not yet injected)
   - `shouldRollbackOutbox_whenBookingRollsBack` — assert it fails
2. Add `shouldProduceKafkaEvent_withinTwoSeconds` — assert it fails (no Debezium wiring yet)

### GREEN — Minimum code to pass

1. Add `OutboxService outboxService` to `BookingService` constructor (via `@RequiredArgsConstructor`)
2. Add `outboxService.writeBookingEvent(booking, "BookingConfirmed")` at end of `confirmBooking()`
3. Add `outboxService.writeBookingEvent(booking, "BookingCancelled")` at end of `cancelBooking()`
4. Add `outboxService.writeBookingEvent(booking, "BookingHeld")` at end of `createHold()`
5. Extract `ipAddress` from `RequestContextHolder` and set on booking before outbox write

### REFACTOR — Quality pass

1. Add structured logging: `log.info("booking event queued: eventType={}, bookingId={}, tenantId={}", ...)`
2. Verify all 13 Avro payload fields are populated (none accidentally omitted)
3. Run `/security-scan` on modified `BookingService`
4. Run `/test-gap` to confirm outbox rollback path is covered

---

## Implementation Reference

### BookingService Modifications

**File**: `apps/api/src/main/java/com/scheduler/service/BookingService.java`

```java
// [TASK: ATOM-KAFKA-006] — modifications only; existing code shown for context

package com.scheduler.service;

import com.scheduler.domain.entity.Booking;
import com.scheduler.domain.enums.BookingStatus;
import com.scheduler.dto.request.ConfirmBookingRequest;
import com.scheduler.dto.request.CancelBookingRequest;
import com.scheduler.dto.request.CreateHoldRequest;
import com.scheduler.dto.response.BookingResponse;
import com.scheduler.mapper.BookingMapper;
import com.scheduler.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingServiceImpl implements BookingService {

    private final BookingRepository bookingRepository;
    private final OutboxService outboxService;  // NEW: injected for ATOM-KAFKA-006

    @Transactional
    @Override
    public BookingResponse confirmBooking(UUID tenantId, UUID bookingId, ConfirmBookingRequest request) {
        // Existing: SELECT FOR UPDATE scoped to tenant
        Booking booking = bookingRepository.findByIdAndTenantIdForUpdate(bookingId, tenantId)
            .orElseThrow(() -> new BookingNotFoundException(bookingId, tenantId));

        // Existing: validate state
        if (booking.getStatus() != BookingStatus.PENDING_HOLD) {
            throw new InvalidBookingStateException(booking.getStatus(), BookingStatus.PENDING_HOLD);
        }

        // Existing: update state
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setConfirmationCode(generateConfirmationCode());
        booking.setIpAddress(extractIpAddress());  // capture for audit trail
        bookingRepository.save(booking);

        // NEW: write outbox event atomically (Propagation.MANDATORY enforced by OutboxService)
        // ADR-003: same @Transactional scope — no separate transaction, no @Async
        outboxService.writeBookingEvent(booking, "BookingConfirmed");
        log.info("booking confirmed and outbox event queued: bookingId={}, tenantId={}", bookingId, tenantId);

        return BookingMapper.toResponse(booking);
    }

    @Transactional
    @Override
    public BookingResponse cancelBooking(UUID tenantId, UUID bookingId, CancelBookingRequest request) {
        Booking booking = bookingRepository.findByIdAndTenantIdForUpdate(bookingId, tenantId)
            .orElseThrow(() -> new BookingNotFoundException(bookingId, tenantId));

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancellationReason(request.reason());
        booking.setIpAddress(extractIpAddress());
        bookingRepository.save(booking);

        // NEW: write outbox event atomically
        outboxService.writeBookingEvent(booking, "BookingCancelled");
        log.info("booking cancelled and outbox event queued: bookingId={}, tenantId={}", bookingId, tenantId);

        return BookingMapper.toResponse(booking);
    }

    @Transactional
    @Override
    public BookingResponse createHold(UUID tenantId, CreateHoldRequest request) {
        // Existing: slot availability check, pessimistic lock
        Booking booking = Booking.builder()
            .tenantId(tenantId)
            .resourceId(request.resourceId())
            .serviceTypeId(request.serviceTypeId())
            .userId(request.userId())
            .slotStart(request.slotStart())
            .slotEnd(request.slotEnd())
            .status(BookingStatus.PENDING_HOLD)
            .ipAddress(extractIpAddress())
            .build();
        bookingRepository.save(booking);

        // NEW: write outbox event atomically
        outboxService.writeBookingEvent(booking, "BookingHeld");
        log.info("booking hold created and outbox event queued: bookingId={}, tenantId={}", booking.getId(), tenantId);

        return BookingMapper.toResponse(booking);
    }

    /**
     * Extract originating IP from the current HTTP request context.
     * Uses RequestContextHolder — never accept IP as a method parameter
     * to avoid leaking caller-controlled values into the audit trail.
     */
    private String extractIpAddress() {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            String forwarded = attrs.getRequest().getHeader("X-Forwarded-For");
            return (forwarded != null) ? forwarded.split(",")[0].trim()
                                       : attrs.getRequest().getRemoteAddr();
        } catch (IllegalStateException e) {
            // No HTTP context (e.g., scheduled job) — return null
            return null;
        }
    }

    private String generateConfirmationCode() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
```

---

## Integration Points

**Depends on**: ATOM-KAFKA-002 (OutboxService with Propagation.MANDATORY), ATOM-KAFKA-005 (BookingLifecycleEvent schema registered), ATOM-PHASE2-010, ATOM-PHASE2-011 (BookingService exists)

**Enables**: ATOM-KAFKA-008 (NotificationConsumer receives BookingConfirmed events), ATOM-KAFKA-010 (AuditConsumer receives all booking lifecycle events), ATOM-KAFKA-011 (chaos tests require BookingService to emit events)

**Cascading updates required**:
- `docs/KAFKA-SPEC.md` — document event emission points in BookingService
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/com/scheduler/service/BookingService.java` | Modified | Add outboxService injection + 3 writeBookingEvent() calls |
| `apps/api/src/test/java/com/scheduler/service/BookingServiceIT.java` | Modified | Add outbox + Kafka integration assertions |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] Rollback test (AC-04) present and passing — no merge without it
- [ ] Zero JPA queries without `tenant_id` in WHERE clause
- [ ] Zero industry-specific terms in any identifier or API path
- [ ] `outboxService.writeBookingEvent()` called within existing `@Transactional` scope (not `@Async`)
- [ ] No direct Kafka writes anywhere in `BookingService`
- [ ] All 3 booking state transitions emit correct `eventType` string
- [ ] Payload `tenantId` field verified in test
- [ ] `@PreAuthorize` present on every new `@RestController` method
- [ ] Outbox event written within `@Transactional` scope (if booking state mutated)
- [ ] ADR-003 referenced in `BookingService` Javadoc
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: kafka-event-mesh | Phase: 3*
