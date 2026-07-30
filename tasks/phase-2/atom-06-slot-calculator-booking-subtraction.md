# ATOM-SLOT-006: SlotCalculatorService — Booking Subtraction and Buffer

**Status**: ✅ Complete
**Feature**: slot-calculator
**Phase**: 2 (Core)
**Tags**: [SLOT]
**Complexity**: High
**Agent**: coder
**Dependencies**: ATOM-SLOT-005
**Blocks**: ATOM-SLOT-007
**PR**: TBD

---

## Overview

Implements the second half of `SlotCalculatorService`: given the operating matrix from ATOM-SLOT-005, subtract existing confirmed and pending-hold bookings (including their buffer windows) to produce the final list of `AvailableSlot` records. The algorithm walks each operating window, generating candidate slots at `durationMinutes` intervals, and excludes any candidate whose effective buffer window overlaps with an existing booking's buffer window. No data is written; the slot list is entirely transient.

---

## User Story

```
As a System
I want to subtract booked time windows (including buffers) from the operating matrix
So that customers are only offered genuinely available slots
```

---

## Acceptance Criteria

- [ ] **AC-01**: With no existing bookings, all operating windows are returned as candidate slots at `durationMinutes` granularity
- [ ] **AC-02**: One confirmed booking mid-window produces slots before and after the booked period, respecting both the booking's `bufferStart`/`bufferEnd` and the new candidate's buffer requirements
- [ ] **AC-03**: A buffer window on a confirmed booking causes an adjacent candidate slot that overlaps that buffer to be excluded
- [ ] **AC-04**: `PENDING_HOLD` bookings block slots with the same weight as `CONFIRMED` bookings
- [ ] **AC-05**: A holiday date returns an empty slot list (delegated to `computeOperatingMatrix`)
- [ ] **AC-06**: Slot granularity equals `durationMinutes` — a 60-minute service generates candidates only at :00, not at :15 or :30 within a window
- [ ] **AC-07**: All time arithmetic uses UTC — no local time is used inside slot generation
- [ ] **AC-08**: The slot list is transient — no rows are written to any table during computation
- [ ] **AC-09 (Tenant isolation)**: `BookingRepository` query includes both `resourceId` and `tenantId` — no cross-tenant bookings are loaded
- [ ] **AC-10 (Domain abstraction)**: Zero industry-specific terms in any identifier within `com.scheduler.slot.*`

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
| AC-10 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 10 criteria rewritten, 10 marked TBD -->

---

## Technical Design

### Architecture

`computeAvailableSlots` is a new method added to the existing `SlotCalculatorService` (established in ATOM-SLOT-005). It calls `computeOperatingMatrix`, loads the service type for buffer/duration values, fetches existing bookings (fresh from DB — never cached), and walks each operating window generating candidates. Buffer arithmetic is applied to both the new candidate (pre-buffer `bufferBefore`, post-buffer `bufferAfter`) and the existing bookings' stored `bufferStart`/`bufferEnd` fields.

### Data Flow / Sequence

```
computeAvailableSlots(resourceId, serviceTypeId, locationId, date, tenantId)
  → computeOperatingMatrix(resourceId, locationId, date, tenantId)   [ATOM-SLOT-005]
  → ServiceTypeRepository.findByIdAndTenantId()                      [load duration + buffers]
  → BookingRepository.findByResourceIdAndStatusInAndSlotStartBetween() [fresh — never cached]
  → for each operating window:
      cursor = window.start
      while (cursor + duration + bufferAfter) ≤ window.end:
          candidate = TimeWindow(cursor - bufferBefore, cursor + duration + bufferAfter)
          if no existing booking.bufferWindow overlaps candidate → add AvailableSlot
          cursor += duration
  → return List<AvailableSlot>                                        [no writes]
```

### File Structure

```
apps/api/src/main/java/com/scheduler/slot/
├── SlotCalculatorService.java           ← add computeAvailableSlots() to existing class
└── model/
    └── AvailableSlot.java               ← record

apps/api/src/test/java/com/scheduler/slot/
└── SlotCalculatorServiceTest.java       ← extend with booking-subtraction scenarios
```

### Interface Contracts

```java
// New model record
public record AvailableSlot(
    Instant startTime,
    Instant endTime,
    int durationMinutes
) {}

// New method added to SlotCalculatorService
public class SlotCalculatorService {
    // Existing method from ATOM-SLOT-005:
    public List<TimeWindow> computeOperatingMatrix(UUID resourceId, UUID locationId,
                                                    LocalDate date, UUID tenantId);

    // New method:
    public List<AvailableSlot> computeAvailableSlots(
        UUID resourceId,
        UUID serviceTypeId,
        UUID locationId,
        LocalDate date,
        UUID tenantId
    );
}

// Repository query needed from BookingRepository
public interface BookingRepository extends JpaRepository<Booking, UUID> {
    List<Booking> findByResourceIdAndStatusInAndSlotStartBetween(
        UUID resourceId,
        List<String> statuses,
        Instant from,
        Instant to
    );
}
```

### Design Rationale

- **ADR-001**: Slots are computed on-demand. The `AvailableSlot` list is never stored; calling `computeAvailableSlots` twice for the same inputs may return different results if a booking was placed between calls. This is correct behavior.
- **ADR-002**: Booking data is always fetched fresh — never from Redis. The pessimistic lock in `BookingService.createHold` (ATOM-BOOKING-009) is the concurrency guard; the slot calculator is advisory only.
- **Buffer model**: Existing bookings store their effective `bufferStart` and `bufferEnd` at write time. Candidate slots compute their effective window at query time. Overlap between the two is the exclusion criterion.

---

## Test Strategy

**Test type**: Unit (JUnit 5, mocked repositories — no DB required)

```
- shouldReturnAllSlots_whenNoBookings:
    Given: operating window 09:00–17:00 (UTC); 60-min service; no buffers; no existing bookings
    Assert: result contains 8 AvailableSlot entries at :00 intervals

- shouldExcludeSlot_whenOverlapsExistingBookingBuffer:
    Given: confirmed booking 10:00–11:00 with 15-min post-buffer (bufferEnd = 11:15)
    Assert: slot starting at 11:00 is excluded; slot at 11:15 is included

- shouldBlockAdjacentSlot_whenBufferWindowOverlaps:
    Given: 60-min service with 15-min bufferBefore; booking at 10:00
    Assert: candidate at 09:45 is excluded (its bufferStart = 09:30 overlaps booking)

- shouldTreatPendingHold_sameAsConfirmed:
    Given: PENDING_HOLD booking at 14:00; no CONFIRMED bookings
    Assert: slot at 14:00 is excluded from results

- shouldReturnEmpty_onHolidayDate:
    Given: holiday on date
    Assert: computeAvailableSlots returns empty list (delegates to computeOperatingMatrix)
```

**Coverage requirements**:
- Line coverage ≥ 80% on `computeAvailableSlots`
- Buffer overlap edge cases must each have a dedicated unit test (before-only, after-only, straddle)

---

## Implementation Constraints

- Every repository call must include `tenant_id` in query parameters
- `computeAvailableSlots` must have zero side effects — no writes, no Kafka, no state mutation
- Booking data must always be fetched fresh from DB — never from Redis cache
- Slot granularity must equal `durationMinutes` — cursor advances by `duration`, not by a fixed interval
- Buffer arithmetic: `effectiveStart = slotStart - bufferBefore`; `effectiveEnd = slotEnd + bufferAfter`
- Both `PENDING_HOLD` and `CONFIRMED` statuses must be included in the blocking query
- All time values are `Instant` (UTC) — no `LocalTime` or `LocalDateTime` in slot generation logic
- No industry-specific terms in any identifier within `com.scheduler.slot.*`
- No `System.out.println` — use SLF4J structured logging

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Extend `SlotCalculatorServiceTest.java` with the 5 booking-subtraction scenarios
2. Assert all 5 fail (`computeAvailableSlots` does not exist yet)

### GREEN — Minimum code to pass

1. Add `AvailableSlot.java` record to `com.scheduler.slot.model`
2. Add `computeAvailableSlots` method to `SlotCalculatorService`
3. Wire `ServiceTypeRepository` and `BookingRepository` injection
4. Pass all 5 unit tests

### REFACTOR — Quality pass

1. Extract buffer-overlap check into a named helper method `candidateIsBlocked(TimeWindow candidate, List<TimeWindow> blocked)`
2. Add Javadoc to `computeAvailableSlots` documenting the cursor-walk algorithm
3. Run static analysis for any industry-specific term leak in the slot package
4. Verify no Redis `@Cacheable` annotation added to booking query

---

## Implementation Reference

### AvailableSlot Record

**File**: `apps/api/src/main/java/com/scheduler/slot/model/AvailableSlot.java`

```java
// [TASK: ATOM-SLOT-006]
public record AvailableSlot(
    Instant startTime,
    Instant endTime,
    int durationMinutes
) {}
```

### computeAvailableSlots Method

**File**: `apps/api/src/main/java/com/scheduler/slot/SlotCalculatorService.java`

```java
// [TASK: ATOM-SLOT-006] — add to SlotCalculatorService
public List<AvailableSlot> computeAvailableSlots(UUID resourceId,
                                                   UUID serviceTypeId,
                                                   UUID locationId,
                                                   LocalDate date,
                                                   UUID tenantId) {
    // 1. Get operating matrix (from ATOM-SLOT-005)
    List<TimeWindow> matrix = computeOperatingMatrix(resourceId, locationId, date, tenantId);
    if (matrix.isEmpty()) return List.of();

    // 2. Load service type (duration, buffer windows)
    ServiceType service = serviceTypeRepository.findByIdAndTenantId(serviceTypeId, tenantId)
        .orElseThrow(() -> new ServiceTypeNotFoundException(serviceTypeId));
    Duration duration     = Duration.ofMinutes(service.getDurationMinutes());
    Duration bufferBefore = Duration.ofMinutes(service.getBufferBeforeMin());
    Duration bufferAfter  = Duration.ofMinutes(service.getBufferAfterMin());

    // 3. Load existing bookings for resource on date (PENDING_HOLD + CONFIRMED)
    Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
    Instant dayEnd   = dayStart.plus(1, ChronoUnit.DAYS);
    List<TimeWindow> blockedWindows = bookingRepository
        .findByResourceIdAndStatusInAndSlotStartBetween(
            resourceId, List.of("PENDING_HOLD", "CONFIRMED"), dayStart, dayEnd)
        .stream()
        .map(b -> new TimeWindow(b.getBufferStart(), b.getBufferEnd()))
        .toList();

    // 4. Walk each operating window, generate candidate slots
    List<AvailableSlot> available = new ArrayList<>();
    for (TimeWindow window : matrix) {
        Instant cursor = window.start();
        while (true) {
            Instant slotEnd      = cursor.plus(duration);
            Instant effectiveEnd = slotEnd.plus(bufferAfter);
            if (effectiveEnd.isAfter(window.end())) break;

            Instant effectiveStart = cursor.minus(bufferBefore);
            TimeWindow candidate = new TimeWindow(effectiveStart, effectiveEnd);

            boolean blocked = blockedWindows.stream().anyMatch(b -> b.overlaps(candidate));
            if (!blocked) {
                available.add(new AvailableSlot(cursor, slotEnd, service.getDurationMinutes()));
            }
            cursor = cursor.plus(duration);   // slot granularity = durationMinutes
        }
    }
    return available;
}
```

### Unit Test Scenarios (behavior reference)

```java
// [TASK: ATOM-SLOT-006]
// Scenario 1: No existing bookings → all windows available as slots
// Scenario 2: One confirmed booking mid-window → slots before and after (not overlapping buffer)
// Scenario 3: Buffer causes adjacent candidate slot to be blocked → that slot excluded
// Scenario 4: PENDING_HOLD blocks same as CONFIRMED
// Scenario 5: Holiday date → empty slot list (delegated to matrix)
```

---

## Integration Points

**Depends on**: ATOM-SLOT-005 (`computeOperatingMatrix`), ATOM-SERVICE-003 (`ServiceTypeRepository`), ATOM-BOOKING-009 (Booking entity with `bufferStart`/`bufferEnd` fields)

**Enables**: ATOM-SLOT-007 (REST endpoint calls `computeAvailableSlots`), ATOM-BOOKING-009 (hold creation calls `computeAvailableSlots` to validate slot is in matrix)

**Cascading updates required**:
- `docs/ARCHITECTURE.md` — document buffer model
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/com/scheduler/slot/model/AvailableSlot.java` | New | Available slot record |
| `apps/api/src/main/java/com/scheduler/slot/SlotCalculatorService.java` | Modified | Add computeAvailableSlots() |
| `apps/api/src/test/java/com/scheduler/slot/SlotCalculatorServiceTest.java` | Modified | Add booking subtraction test scenarios |
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

*Last updated: 2026-06-18 | Feature: slot-calculator | Phase: 2*
