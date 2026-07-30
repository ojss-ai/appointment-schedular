# ATOM-BOOKING-011: BookingService — Cancel Booking

**Status**: ✅ Complete
**Feature**: booking-engine
**Phase**: 2 (Core)
**Tags**: [CONCURRENCY]
**Complexity**: Low
**Agent**: coder
**Dependencies**: ATOM-BOOKING-010
**Blocks**: ATOM-BOOKING-013
**PR**: TBD

---

## Overview

Implements booking cancellation in `BookingService`. Both the booking owner and any `ADMIN` role holder can cancel a confirmed booking. Cancellation is immediate — the slot is freed with no cooldown period. `PENDING_HOLD` bookings cannot be cancelled via this endpoint; they are cleaned up exclusively by the `HoldGcScheduler` (ATOM-BOOKING-012). A Phase 3 outbox write will be added for the `BOOKING_CANCELLED` event.

---

## User Story

```
As a Booking User or Tenant Admin
I want to cancel a confirmed booking
So that the slot is freed for other users and the booking record reflects the cancellation
```

---

## Acceptance Criteria

- [ ] **AC-01**: A booking owner (userId matches) can cancel their own confirmed booking — response is `200 CANCELLED`
- [ ] **AC-02**: A user with `ROLE_ADMIN` can cancel any confirmed booking within the same tenant
- [ ] **AC-03**: A non-owner, non-admin caller attempting cancellation returns `403 INSUFFICIENT_ROLE`
- [ ] **AC-04**: Attempting to cancel a `PENDING_HOLD` booking returns `409 INVALID_STATE_TRANSITION` (use the GC instead)
- [ ] **AC-05**: Attempting to cancel an already-cancelled booking returns `409 ALREADY_CANCELLED`
- [ ] **AC-06**: After cancellation, the slot is immediately available for new holds — a new `createHold` for the same slot succeeds
- [ ] **AC-07**: `cancelledAt`, `cancelledBy`, and `cancellationReason` are persisted correctly
- [ ] **AC-08 (Tenant isolation)**: Booking loaded with `tenant_id` in WHERE clause — cross-tenant cancellation not possible
- [ ] **AC-09 (Domain abstraction)**: No industry-specific terms in any class name, field name, or API path in this package

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | TBD | TBD | 🔜 Planned |
| AC-02 | TBD | TBD | 🔜 Planned |
| AC-03 | TBD | TBD | 🔜 Planned |
| AC-04 | TBD | TBD | 🔜 Planned |
| AC-05 | TBD | TBD | 🔜 Planned |
| AC-06 | TBD | TBD | 🔜 Planned |
| AC-07 | TBD | TBD | 🔜 Planned |
| AC-08 | TBD | TBD | 🔜 Planned |
| AC-09 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 9 criteria rewritten, 9 marked TBD -->

---

## Technical Design

### Architecture

`cancelBooking` is a new method on the existing `BookingService`. The method loads the booking by `(id, tenantId)`, authorizes the caller (owner OR admin), guards the state machine (only `CONFIRMED` is cancellable via this path), and updates the booking status to `CANCELLED` with audit fields. The slot is freed immediately because the conflict query in `createHold` filters on `PENDING_HOLD` and `CONFIRMED` only.

### Data Flow / Sequence

```
DELETE /api/v1/tenants/{tenantId}/bookings/{bookingId}
  → @PreAuthorize tenantGuard.check()
  → BookingService.cancelBooking()
      → BookingRepository.findByIdAndTenantId()      [tenant-scoped load]
      → authz: isOwner OR isAdmin
      → state guard: status != PENDING_HOLD
      → state guard: status != CANCELLED
      → booking.status = CANCELLED
      → booking.cancelledAt = now()
      → booking.cancelledBy = actorUserId
      → booking.cancellationReason = reason
      → bookingRepository.save()
      → DB commit
      // TODO: Phase 3 — write outbox event (BOOKING_CANCELLED)
  → return CancellationResponse
```

### File Structure

```
apps/api/src/main/java/com/scheduler/
├── service/
│   └── BookingService.java              ← add cancelBooking()
├── controller/
│   └── BookingController.java           ← add DELETE .../bookings/{id} endpoint
└── dto/
    └── CancellationResponse.java

apps/api/src/test/java/com/scheduler/booking/
└── BookingCancelIT.java
```

### Interface Contracts

```java
// DTO — Java 21 record
public record CancellationResponse(
    UUID bookingId,
    String status,
    Instant cancelledAt
) {}

// New method on BookingService
public class BookingService {
    @Transactional
    public CancellationResponse cancelBooking(
        UUID bookingId,
        String reason,
        UUID actorUserId,
        UUID tenantId
    );
}
```

### Design Rationale

- **PENDING_HOLD not cancellable here**: Hold cancellation via this endpoint would require the GC to coordinate with an explicit cancel path, creating two competing cleanup mechanisms. Routing all `PENDING_HOLD` cleanup through `HoldGcScheduler` keeps the state machine unambiguous.
- **Slot freed immediately on CANCELLED**: The conflict query in `createHold` uses `status IN ('PENDING_HOLD', 'CONFIRMED')`. A `CANCELLED` row is ignored, so no tombstone or cooldown is needed.
- **Outbox deferred to Phase 3**: Comment placed at the insertion point for Phase 3 implementation.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL)

```
- shouldCancelOwnBooking_asOwner:
    Given: booking owner calls cancelBooking with their userId
    Assert: booking status = CANCELLED; cancelledAt not null; cancelledBy = actorUserId

- shouldCancelAnyBooking_asAdmin:
    Given: user with ROLE_ADMIN who is not the booking owner calls cancelBooking
    Assert: booking status = CANCELLED

- shouldRejectNonOwnerNonAdmin_returns403:
    Given: caller is neither the booking owner nor ROLE_ADMIN
    Assert: response is 403 INSUFFICIENT_ROLE; booking status unchanged

- shouldRejectCancelOnPendingHold_returns409:
    Given: booking status = PENDING_HOLD
    Assert: response is 409 INVALID_STATE_TRANSITION

- shouldFreeSlot_afterCancellation:
    Given: confirmed booking at slot X; cancel it
    Assert: createHold for slot X succeeds (no 409 SLOT_UNAVAILABLE)
```

**Coverage requirements**:
- Line coverage ≥ 80% on `BookingService.cancelBooking`
- State machine tests must cover all 3 rejectable states: PENDING_HOLD, CANCELLED, non-owner non-admin

---

## Implementation Constraints

- Every JPA query must include `tenant_id` in the WHERE clause
- DTOs must be Java 21 records (never classes)
- `cancelBooking` must be `@Transactional`
- All controller endpoints must carry `@PreAuthorize("@tenantGuard.check(#tenantId)")`
- Authorization check: `isOwner = booking.getUserId().equals(actorUserId)` OR `TenantContext.getRoles().contains("ROLE_ADMIN")`
- `PENDING_HOLD` must NOT be cancellable via this method — throw `InvalidStateTransitionException`
- `CANCELLED` bookings must not be cancelled again — throw `AlreadyCancelledException`
- No direct Kafka writes — outbox write deferred to Phase 3 (add `// TODO: Phase 3 — write outbox event` comment)
- No `System.out.println` — use SLF4J structured logging

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/com/scheduler/booking/BookingCancelIT.java`
2. Write `shouldCancelOwnBooking_asOwner` — assert it fails
3. Write `shouldRejectNonOwnerNonAdmin_returns403` — assert it fails

### GREEN — Minimum code to pass

1. Add `cancelBooking()` to `BookingService`
2. Add `CancellationResponse` record
3. Add `DELETE .../bookings/{bookingId}` endpoint to `BookingController`
4. Pass all test scenarios

### REFACTOR — Quality pass

1. Add structured logging: `log.info("Booking cancelled: id={}, by={}, tenantId={}", ...)`
2. Add Javadoc to `cancelBooking` documenting the authorization model
3. Ensure `cancelledAt`, `cancelledBy`, `cancellationReason` are all indexed if queried frequently
4. Run `/security-scan` on the updated controller

---

## Implementation Reference

### BookingService.cancelBooking

**File**: `apps/api/src/main/java/com/scheduler/service/BookingService.java`

```java
// [TASK: ATOM-BOOKING-011]
@Transactional
public CancellationResponse cancelBooking(UUID bookingId, String reason,
                                           UUID actorUserId, UUID tenantId) {
    Booking booking = bookingRepository.findByIdAndTenantId(bookingId, tenantId)
        .orElseThrow(() -> new BookingNotFoundException(bookingId));

    // Authorization: owner OR ADMIN
    boolean isOwner = booking.getUserId().equals(actorUserId);
    boolean isAdmin = TenantContext.getRoles().contains("ROLE_ADMIN");
    if (!isOwner && !isAdmin) {
        throw new InsufficientRoleException("Must be booking owner or ADMIN to cancel");
    }

    // State guard
    if ("PENDING_HOLD".equals(booking.getStatus())) {
        throw new InvalidStateTransitionException("Cannot cancel PENDING_HOLD — let GC handle it");
    }
    if ("CANCELLED".equals(booking.getStatus())) {
        throw new AlreadyCancelledException(bookingId);
    }

    booking.setStatus("CANCELLED");
    booking.setCancelledAt(Instant.now());
    booking.setCancelledBy(actorUserId);
    booking.setCancellationReason(reason);
    bookingRepository.save(booking);
    // TODO: Phase 3 — write outbox event (BOOKING_CANCELLED)

    return new CancellationResponse(bookingId, "CANCELLED", booking.getCancelledAt());
}
```

---

## Integration Points

**Depends on**: ATOM-BOOKING-010 (Booking entity with CONFIRMED state, cancellation fields)

**Enables**: ATOM-BOOKING-013 (cancel-and-rebook scenario in concurrency tests), ATOM-UI-014 (Next.js booking management page calls this endpoint)

**Cascading updates required**:
- `docs/API-SPEC.md` — add booking cancel endpoint
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/com/scheduler/service/BookingService.java` | Modified | Add cancelBooking() |
| `apps/api/src/main/java/com/scheduler/controller/BookingController.java` | Modified | Add cancel endpoint |
| `apps/api/src/main/java/com/scheduler/dto/CancellationResponse.java` | New | Cancel response DTO |
| `apps/api/src/test/java/com/scheduler/booking/BookingCancelIT.java` | New | Cancellation integration tests |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] Zero JPA queries without `tenant_id` in WHERE clause
- [ ] Zero industry-specific terms in any identifier or API path
- [ ] `@PreAuthorize` present on every new `@RestController` method
- [ ] Flyway migration exists for all schema changes
- [ ] Outbox event written within `@Transactional` scope (if booking state mutated)
- [ ] Redis cache keys invalidated (if schedule/holiday cache affected)
- [ ] ADR created or referenced (if architectural decision made)
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: booking-engine | Phase: 2*
