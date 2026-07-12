# ATOM-SLOT-005: SlotCalculatorService — Operating Matrix

**Status**: 🟡 Planned
**Feature**: slot-calculator
**Phase**: 2 (Core)
**Tags**: [SLOT]
**Complexity**: High
**Agent**: coder (domain-abstraction guard active)
**Dependencies**: ATOM-RESOURCE-002, ATOM-HOLIDAY-004
**Blocks**: ATOM-SLOT-006
**PR**: TBD

---

## Overview

Implements the first half of `SlotCalculatorService`: computing the operating matrix — the set of available time windows for a resource on a given date before any bookings are subtracted. The algorithm loads the resource's weekly shift schedule, applies the location's IANA timezone to convert local times to UTC, then subtracts break windows (which may split a shift window into two). The service is pure — no writes, no Kafka, no side effects. Domain abstraction is strictly enforced; no industry-specific terms may appear anywhere in this package.

---

## User Story

```
As a System
I want to compute the raw operating time windows for a resource on any date
So that downstream slot calculation can subtract confirmed bookings and produce accurate availability
```

---

## Acceptance Criteria

- [ ] **AC-01**: Single shift 09:00–17:00 with no breaks produces exactly one `TimeWindow` [09:00, 17:00] in UTC
- [ ] **AC-02**: Single shift 09:00–17:00 with a lunch break 12:00–13:00 produces exactly two `TimeWindow` entries: [09:00, 12:00] and [13:00, 17:00]
- [ ] **AC-03**: A holiday date returns an empty list without loading any schedule data
- [ ] **AC-04**: Multiple shifts (split shift: 09:00–12:00 and 14:00–18:00) produce two `TimeWindow` entries
- [ ] **AC-05**: A break wider than the containing shift window eliminates that window entirely
- [ ] **AC-06**: Operating matrix correctly converts local shift times to UTC using the location's IANA timezone
- [ ] **AC-07**: `computeOperatingMatrix` has no side effects — no writes, no Kafka events, no state mutation
- [ ] **AC-08 (Tenant isolation)**: All repository calls in this service include `tenant_id` — no cross-tenant schedule data is loaded
- [ ] **AC-09 (Domain abstraction)**: Zero industry-specific terms (`doctor`, `patient`, `vehicle`, `appointment`, `mechanic`) in any class name, method name, field name, or comment in the `com.scheduler.slot` package

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

`SlotCalculatorService` is a pure `@Service` — no controller, no direct DB writes. It reads from `ResourceScheduleRepository`, `ResourceBreakRepository`, `BranchHolidayRepository`, and `LocationRepository` (for timezone). All time arithmetic is performed in UTC after converting local shift times via the location's `ZoneId`. Break subtraction is an in-memory set-difference algorithm that may split windows.

### Data Flow / Sequence

```
computeOperatingMatrix(resourceId, locationId, date, tenantId)
  → BranchHolidayRepository.existsByLocationIdAndDate()    [holiday check]
  → BranchHolidayRepository.existsByLocationIdAndMonthDay() [recurring check]
  → LocationRepository.findByIdAndTenantId()               [load timezone]
  → ResourceScheduleRepository.findByResourceIdAndDayOfWeek() [load shifts]
  → ResourceBreakRepository.findByResourceIdAndDayOfWeek()    [load breaks]
  → toUtcWindow() × N                                      [local→UTC conversion]
  → subtractBreaks()                                       [in-memory set diff]
  → return List<TimeWindow>                                [no writes]
```

### File Structure

```
apps/api/src/main/java/com/scheduler/slot/
├── SlotCalculatorService.java           ← pure @Service
└── model/
    ├── TimeWindow.java                  ← record
    ├── AvailableSlot.java               ← record (used in ATOM-SLOT-006)
    └── OperatingMatrix.java             ← record wrapping List<TimeWindow>

apps/api/src/test/java/com/scheduler/slot/
└── SlotCalculatorServiceTest.java       ← unit tests (mocked repositories)
```

### Interface Contracts

```java
// Model records — no method bodies
public record TimeWindow(Instant start, Instant end) {
    public boolean overlaps(TimeWindow other);
    public Duration duration();
}

public record OperatingMatrix(List<TimeWindow> windows) {}

// Service — method signatures only
public class SlotCalculatorService {
    public List<TimeWindow> computeOperatingMatrix(
        UUID resourceId,
        UUID locationId,
        LocalDate date,
        UUID tenantId
    );

    // Package-private for unit testing
    List<TimeWindow> subtractBreaks(List<TimeWindow> shifts, List<TimeWindow> breaks);
}
```

### Design Rationale

- **ADR-001**: Slots are never stored — the operating matrix is computed on demand from shift and break records. This eliminates stale-slot race conditions; the only source of truth is live booking records and schedule configuration.
- **UTC-first arithmetic**: All `TimeWindow` instances use `Instant` (UTC). Local times from the DB are converted immediately using the location timezone before any set-difference logic. This prevents DST-transition bugs in slot arithmetic.
- **Pure service**: No `@Transactional` annotation — this service reads only (no writes), so no transaction boundary is needed. Reads can be called from within an outer `@Transactional` scope (e.g., `BookingService.createHold`) without any isolation impact.

---

## Test Strategy

**Test type**: Unit (JUnit 5, mocked repositories — no DB required)

```
- shouldReturnSingleWindow_noBreaks:
    Given: shift 09:00–17:00 on Monday; no breaks; no holiday
    Assert: computeOperatingMatrix returns exactly 1 TimeWindow [09:00Z, 17:00Z]

- shouldSplitWindow_onLunchBreak:
    Given: shift 09:00–17:00; break 12:00–13:00
    Assert: result is 2 windows: [09:00, 12:00] and [13:00, 17:00]

- shouldReturnEmpty_onHolidayDate:
    Given: BranchHolidayRepository.existsByLocationIdAndDate returns true
    Assert: computeOperatingMatrix returns empty list; no schedule queries made

- shouldReturnTwoWindows_splitShift:
    Given: shifts 09:00–12:00 and 14:00–18:00; no breaks
    Assert: result is 2 windows exactly matching the two shifts

- shouldEliminateWindow_whenBreakExceedsShift:
    Given: shift 09:00–10:00; break 08:00–11:00 (wider than shift)
    Assert: result is empty list
```

**Coverage requirements**:
- Line coverage ≥ 80% on `SlotCalculatorService`
- `subtractBreaks` must have dedicated unit tests for: no-overlap, partial-left, partial-right, full-cover, and split cases

---

## Implementation Constraints

- Every repository call must include `tenant_id` in the query parameters
- `computeOperatingMatrix` must have zero side effects — no writes, no Kafka, no state mutation
- All time values in `TimeWindow` must be `Instant` (UTC) — no `LocalTime` or `LocalDateTime` in model records
- No industry-specific terms in any identifier within `com.scheduler.slot.*`
- Service must not store or cache results — caching is handled externally in ATOM-SLOT-008
- No `@Transactional` annotation on `computeOperatingMatrix` — pure reads only
- `subtractBreaks` must be package-private (not `private`) to allow unit testing without reflection
- No `System.out.println` — use SLF4J structured logging

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/com/scheduler/slot/SlotCalculatorServiceTest.java`
2. Write all 5 unit test scenarios with mocked repositories — assert they fail (class does not exist yet)
3. Specifically assert `shouldReturnEmpty_onHolidayDate` fails with `ClassNotFoundException`

### GREEN — Minimum code to pass

1. Create `TimeWindow.java` record with `overlaps()` and `duration()` methods
2. Create `SlotCalculatorService.java` with `computeOperatingMatrix` and `subtractBreaks`
3. Inject mocked repositories; pass all 5 unit tests

### REFACTOR — Quality pass

1. Extract `toUtcWindow()` as a named private method with clear Javadoc
2. Add SLF4J debug logging for holiday-hit and empty-schedule cases
3. Run static analysis for any industry-specific term leak (`grep -r "doctor\|patient\|vehicle" src/main/java/com/scheduler/slot/`)
4. Add Javadoc to `computeOperatingMatrix` describing the algorithm steps

---

## Implementation Reference

### TimeWindow Record

**File**: `apps/api/src/main/java/com/scheduler/slot/model/TimeWindow.java`

```java
// [TASK: ATOM-SLOT-005]
public record TimeWindow(Instant start, Instant end) {
    public boolean overlaps(TimeWindow other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }
    public Duration duration() { return Duration.between(start, end); }
}
```

### Operating Matrix Algorithm

**File**: `apps/api/src/main/java/com/scheduler/slot/SlotCalculatorService.java`

```java
// [TASK: ATOM-SLOT-005]
@Service
@RequiredArgsConstructor
public class SlotCalculatorService {

    private final ResourceScheduleRepository scheduleRepo;
    private final ResourceBreakRepository    breakRepo;
    private final BranchHolidayRepository    holidayRepo;
    private final LocationRepository         locationRepo;

    /**
     * Compute available time windows for a resource on a date.
     * Result is BEFORE booking subtraction — see computeAvailableSlots().
     */
    public List<TimeWindow> computeOperatingMatrix(UUID resourceId,
                                                    UUID locationId,
                                                    LocalDate date,
                                                    UUID tenantId) {
        // 1. Holiday check → empty matrix
        if (isHoliday(locationId, date)) return List.of();

        // 2. Load location timezone
        ZoneId zone = locationRepo.findByIdAndTenantId(locationId, tenantId)
            .map(l -> ZoneId.of(l.getTimezone()))
            .orElseThrow(() -> new LocationNotFoundException(locationId));

        // 3. Load shift windows for day_of_week
        int dow = date.getDayOfWeek().getValue();  // 1=Monday … 7=Sunday
        List<TimeWindow> shifts = scheduleRepo
            .findByResourceIdAndDayOfWeek(resourceId, dow).stream()
            .map(s -> toUtcWindow(s.getStartTime(), s.getEndTime(), date, zone))
            .sorted(Comparator.comparing(TimeWindow::start))
            .toList();

        if (shifts.isEmpty()) return List.of();

        // 4. Load breaks and subtract them from shift windows
        List<TimeWindow> breaks = breakRepo
            .findByResourceIdAndDayOfWeek(resourceId, dow).stream()
            .map(b -> toUtcWindow(b.getStartTime(), b.getEndTime(), date, zone))
            .toList();

        return subtractBreaks(shifts, breaks);
    }

    // --- Private helpers ---

    private boolean isHoliday(UUID locationId, LocalDate date) {
        return holidayRepo.existsByLocationIdAndDate(locationId, date)
            || holidayRepo.existsByLocationIdAndMonthDay(
                   locationId, date.getMonthValue(), date.getDayOfMonth(), true);
    }

    private TimeWindow toUtcWindow(LocalTime start, LocalTime end, LocalDate date, ZoneId zone) {
        Instant s = date.atTime(start).atZone(zone).toInstant();
        Instant e = date.atTime(end).atZone(zone).toInstant();
        return new TimeWindow(s, e);
    }

    /**
     * Subtract break windows from shift windows.
     * A break may split one shift window into two.
     */
    List<TimeWindow> subtractBreaks(List<TimeWindow> shifts, List<TimeWindow> breaks) {
        List<TimeWindow> result = new ArrayList<>(shifts);
        for (TimeWindow brk : breaks) {
            List<TimeWindow> next = new ArrayList<>();
            for (TimeWindow window : result) {
                if (!window.overlaps(brk)) {
                    next.add(window);
                } else {
                    // Part before the break
                    if (window.start().isBefore(brk.start())) {
                        next.add(new TimeWindow(window.start(), brk.start()));
                    }
                    // Part after the break
                    if (brk.end().isBefore(window.end())) {
                        next.add(new TimeWindow(brk.end(), window.end()));
                    }
                    // If break completely covers window → window eliminated
                }
            }
            result = next;
        }
        return result;
    }
}
```

### Unit Test Scenarios (behavior reference)

```java
// [TASK: ATOM-SLOT-005]
// Scenario 1: Single shift 09:00–17:00, no breaks → one window [09:00, 17:00]
// Scenario 2: Single shift 09:00–17:00, lunch break 12:00–13:00 → two windows
// Scenario 3: Holiday date → empty list
// Scenario 4: Multiple shifts (split shift) 09:00–12:00 and 14:00–18:00 → two windows
// Scenario 5: Break wider than shift window → window eliminated
```

---

## Integration Points

**Depends on**: ATOM-RESOURCE-002 (`resource_schedules`, `resource_breaks` tables), ATOM-HOLIDAY-004 (`branch_holidays` table and `isHoliday` query methods), ATOM-LOCATION-001 (timezone field on `locations`)

**Enables**: ATOM-SLOT-006 (`computeAvailableSlots` calls `computeOperatingMatrix` as its first step), ATOM-SLOT-007 (REST endpoint delegates to this service)

**Cascading updates required**:
- `docs/ARCHITECTURE.md` — document SlotCalculatorService as pure computation layer
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/com/scheduler/slot/model/TimeWindow.java` | New | Core time window record |
| `apps/api/src/main/java/com/scheduler/slot/model/AvailableSlot.java` | New | Slot record (populated in ATOM-SLOT-006) |
| `apps/api/src/main/java/com/scheduler/slot/model/OperatingMatrix.java` | New | Matrix wrapper record |
| `apps/api/src/main/java/com/scheduler/slot/SlotCalculatorService.java` | New | Operating matrix computation |
| `apps/api/src/test/java/com/scheduler/slot/SlotCalculatorServiceTest.java` | New | Unit tests (mocked repos) |
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

## Unconfirmed Assumptions

⚠️ The following assumptions could not be verified and require confirmation before implementation:

| # | Assumption | Expected source | Risk | Blocker? |
|---|-----------|-----------------|------|----------|
| 1 | `resource_schedules.day_of_week` uses ISO values (1=Monday, 7=Sunday) matching `DayOfWeek.getValue()` | `docs/DATABASE-SCHEMA.md` | MEDIUM | YES |

---

*Last updated: 2026-06-18 | Feature: slot-calculator | Phase: 2*
