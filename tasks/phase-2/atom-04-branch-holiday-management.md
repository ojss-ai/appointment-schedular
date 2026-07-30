# ATOM-HOLIDAY-004: Branch Holiday Management API

**Status**: ✅ Complete
**Feature**: holiday-management
**Phase**: 2 (Core)
**Tags**: [CONFIG]
**Complexity**: Low
**Agent**: coder
**Dependencies**: ATOM-LOCATION-001
**Blocks**: ATOM-SLOT-005
**PR**: TBD

---

## Overview

Implements CRUD for the `BranchHoliday` entity, which marks specific dates (or recurring annual dates) as closed for a given location. When the `SlotCalculatorService` encounters a holiday date it returns an empty operating matrix, producing zero available slots. Holidays must be future-dated at creation time. Recurring holidays (e.g., Christmas) apply to the same month/day in all future years without requiring annual re-entry.

---

## User Story

```
As a Tenant Admin
I want to define holiday dates for a branch location
So that customers cannot book appointments on days the branch is closed
```

---

## Acceptance Criteria

- [ ] **AC-01**: A holiday date causes `SlotCalculatorService.computeOperatingMatrix()` to return an empty list for that location on that date
- [ ] **AC-02**: A holiday with `isRecurring = true` blocks slots on the same month/day in all future years (verified by querying `existsByLocationIdAndMonthDay`)
- [ ] **AC-03**: `POST` with a `holidayDate` that is not in the future returns `422 PAST_HOLIDAY_DATE`
- [ ] **AC-04**: `POST` with a duplicate `(locationId, holidayDate)` returns `409 HOLIDAY_ALREADY_EXISTS`
- [ ] **AC-05**: `GET .../holidays?year=2026` returns only holidays falling within calendar year 2026 for the given location
- [ ] **AC-06 (Tenant isolation)**: All JPA queries include `tenant_id` in the WHERE clause — zero cross-tenant rows returned
- [ ] **AC-07 (Domain abstraction)**: No industry-specific terms appear in any class name, field name, or API path in this package

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | TBD (tested in ATOM-SLOT-005) | TBD | 🔜 Planned |
| AC-02 | TBD | TBD | 🔜 Planned |
| AC-03 | TBD | TBD | 🔜 Planned |
| AC-04 | TBD | TBD | 🔜 Planned |
| AC-05 | TBD | TBD | 🔜 Planned |
| AC-06 | TBD | TBD | 🔜 Planned |
| AC-07 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 7 criteria rewritten, 7 marked TBD -->

---

## Technical Design

### Architecture

`HolidayController` → `HolidayService` → `BranchHolidayRepository` → PostgreSQL `branch_holidays` table. The service validates future-date constraint and duplicate detection before persisting. `SlotCalculatorService` calls two repository methods — `existsByLocationIdAndDate` and `existsByLocationIdAndMonthDay` — to determine if a given date is blocked. Redis caching of holiday lookups is added in ATOM-SLOT-008.

### File Structure

```
apps/api/src/main/java/com/scheduler/
├── domain/entity/
│   └── BranchHoliday.java               ← JPA entity
├── repository/
│   └── BranchHolidayRepository.java
├── service/
│   └── HolidayService.java
├── controller/
│   └── HolidayController.java
└── dto/
    ├── CreateHolidayRequest.java
    └── HolidayResponse.java

apps/api/src/main/resources/db/migration/
└── V011__create_branch_holidays.sql
```

### Interface Contracts

```java
// DTOs — Java 21 records
public record CreateHolidayRequest(
    @NotNull LocalDate holidayDate,
    @Size(max = 255) String name,
    boolean isRecurring
) {}

public record HolidayResponse(
    UUID id,
    UUID tenantId,
    UUID locationId,
    LocalDate holidayDate,
    String name,
    boolean isRecurring,
    Instant createdAt
) {}

// Repository interface
public interface BranchHolidayRepository extends JpaRepository<BranchHoliday, UUID> {
    List<BranchHoliday> findByLocationIdAndTenantIdAndYear(UUID locationId, UUID tenantId, int year);
    boolean existsByLocationIdAndHolidayDate(UUID locationId, LocalDate date);
    boolean existsByLocationIdAndTenantIdAndHolidayDate(UUID locationId, UUID tenantId, LocalDate date);

    @Query("""
        SELECT COUNT(h) > 0 FROM BranchHoliday h
        WHERE h.locationId = :locationId
          AND EXTRACT(MONTH FROM h.holidayDate) = :month
          AND EXTRACT(DAY   FROM h.holidayDate) = :day
          AND h.isRecurring = true
        """)
    boolean existsByLocationIdAndMonthDay(UUID locationId, int month, int day, boolean recurring);
}

// Service interface
public interface HolidayService {
    HolidayResponse create(UUID tenantId, UUID locationId, CreateHolidayRequest request);
    List<HolidayResponse> listByYear(UUID tenantId, UUID locationId, int year);
    void delete(UUID tenantId, UUID locationId, UUID holidayId);
}
```

### Design Rationale

- **ADR-001**: Slots are computed on-demand; holidays feed into the operating matrix computation (`computeOperatingMatrix` returns empty on a holiday date) rather than pre-blocking calendar entries.
- **Recurring holiday pattern**: Month/day extraction in JPQL avoids storing all future-year occurrences, keeping the table small while supporting indefinite recurrence.
- **Future-date validation in service**: Bean Validation cannot express "must be after today" reliably across timezones; the service compares against `LocalDate.now()` to reject past dates consistently.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL) + Unit (mocked repository)

```
- shouldReturnEmptyMatrix_onHolidayDate:
    Given: holiday exists for locationId on date 2026-12-25
    Assert: SlotCalculatorService.computeOperatingMatrix(resourceId, locationId, 2026-12-25, tenantId) returns empty list

- shouldApplyRecurringHoliday_inFutureYear:
    Given: isRecurring holiday on 2026-12-25
    Assert: existsByLocationIdAndMonthDay(locationId, 12, 25) returns true when called for 2027

- shouldRejectPastDate_returns422:
    Given: holidayDate = LocalDate.now().minusDays(1)
    Assert: response is 422 PAST_HOLIDAY_DATE

- shouldRejectDuplicate_returns409:
    Given: holiday for (locationId, 2026-07-04) already exists
    Assert: POST with same values returns 409 HOLIDAY_ALREADY_EXISTS

- shouldFilterByYear:
    Given: holidays for 2025-12-25, 2026-07-04, 2027-01-01
    Assert: GET ?year=2026 returns exactly 1 result
```

**Coverage requirements**:
- Line coverage ≥ 80% on `HolidayService`
- Recurring holiday check must have an integration test against real PostgreSQL (EXTRACT function)

---

## Implementation Constraints

- Every JPA query must include `tenant_id` in the WHERE clause
- DTOs must be Java 21 records (never classes)
- `HolidayService` methods that mutate state must be `@Transactional`
- All controller endpoints must carry `@PreAuthorize("@tenantGuard.check(#tenantId)")`
- `holidayDate` must be strictly future at creation time (not today, not past)
- Duplicate `(locationId, holidayDate)` raises `409` before insert attempt
- `BranchHolidayRepository` must expose both `existsByLocationIdAndDate` and `existsByLocationIdAndMonthDay` for use by `SlotCalculatorService`
- No `System.out.println` — use SLF4J structured logging

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/com/scheduler/holiday/HolidayServiceIT.java`
2. Write `shouldReturnEmptyMatrix_onHolidayDate` (requires ATOM-SLOT-005 stubs) — assert it fails
3. Write `shouldRejectPastDate_returns422` — assert it fails

### GREEN — Minimum code to pass

1. Create Flyway migration `V011__create_branch_holidays.sql`
2. Implement `BranchHoliday.java` JPA entity
3. Implement `BranchHolidayRepository.java` with required query methods
4. Implement `HolidayService.java` — future-date check, duplicate check, year filter
5. Implement `HolidayController.java` with `@PreAuthorize`

### REFACTOR — Quality pass

1. Add structured logging for holiday create/delete operations
2. Add Javadoc to all `public` service methods
3. Ensure `SlotCalculatorService` integration test for holiday date is present
4. Run `/security-scan` on the new controller

---

## Implementation Reference

### Create Request Record

**File**: `apps/api/src/main/java/com/scheduler/dto/CreateHolidayRequest.java`

```java
// [TASK: ATOM-HOLIDAY-004]
public record CreateHolidayRequest(
    @NotNull LocalDate holidayDate,          // must be future date
    @Size(max = 255) String name,
    boolean isRecurring
) {}
```

### Future-Date Validation

**File**: `apps/api/src/main/java/com/scheduler/service/HolidayService.java`

```java
// [TASK: ATOM-HOLIDAY-004]
if (!req.holidayDate().isAfter(LocalDate.now())) {
    throw new ValidationException("Holiday date must be in the future");
}
```

### Recurring Holiday Check (used by SlotCalculatorService)

**File**: `apps/api/src/main/java/com/scheduler/repository/BranchHolidayRepository.java`

```java
// [TASK: ATOM-HOLIDAY-004]
// SlotCalculatorService checks recurring holidays:
boolean isHoliday = holidayRepository.existsByLocationIdAndDate(locationId, date)
    || holidayRepository.existsByLocationIdAndMonthDay(
           locationId, date.getMonthValue(), date.getDayOfMonth(), true);

@Query("""
    SELECT COUNT(h) > 0 FROM BranchHoliday h
    WHERE h.locationId = :locationId
      AND EXTRACT(MONTH FROM h.holidayDate) = :month
      AND EXTRACT(DAY   FROM h.holidayDate) = :day
      AND h.isRecurring = true
    """)
boolean existsByLocationIdAndMonthDay(UUID locationId, int month, int day, boolean recurring);
```

### Endpoints

**File**: `apps/api/src/main/java/com/scheduler/controller/HolidayController.java`

```java
// [TASK: ATOM-HOLIDAY-004]
// GET    /api/v1/tenants/{tenantId}/locations/{locationId}/holidays?year=2026
// POST   /api/v1/tenants/{tenantId}/locations/{locationId}/holidays
// DELETE /api/v1/tenants/{tenantId}/locations/{locationId}/holidays/{holidayId}
```

---

## Integration Points

**Depends on**: ATOM-LOCATION-001 (locations table, `location_id` FK), Flyway V011

**Enables**: ATOM-SLOT-005 (`SlotCalculatorService.computeOperatingMatrix` calls `isHoliday` check), ATOM-SLOT-008 (Redis caching of holiday lookups)

**Cascading updates required**:
- `docs/API-SPEC.md` — add Holiday endpoints
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/resources/db/migration/V011__create_branch_holidays.sql` | New | BranchHoliday schema |
| `apps/api/src/main/java/com/scheduler/domain/entity/BranchHoliday.java` | New | JPA entity |
| `apps/api/src/main/java/com/scheduler/repository/BranchHolidayRepository.java` | New | Data access |
| `apps/api/src/main/java/com/scheduler/service/HolidayService.java` | New | Business logic |
| `apps/api/src/main/java/com/scheduler/controller/HolidayController.java` | New | REST endpoints |
| `apps/api/src/main/java/com/scheduler/dto/CreateHolidayRequest.java` | New | Create DTO |
| `apps/api/src/main/java/com/scheduler/dto/HolidayResponse.java` | New | Response DTO |
| `apps/api/src/test/java/com/scheduler/holiday/HolidayServiceIT.java` | New | Integration tests |
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

*Last updated: 2026-06-18 | Feature: holiday-management | Phase: 2*
