# ATOM-SLOT-007: Slot Availability REST Endpoint

**Status**: ✅ Complete
**Feature**: slot-calculator
**Phase**: 2 (Core)
**Tags**: [SLOT]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-SLOT-005, ATOM-SLOT-006
**Blocks**: ATOM-SLOT-008, ATOM-UI-014
**PR**: TBD

---

## Overview

Exposes `SlotCalculatorService.computeAvailableSlots` through `SlotController`, the performance-critical public endpoint. Clients may request a single date or a rolling window of up to 7 days; the endpoint returns a map of date → slot list. NFR-1.2 requires p99 < 300ms for a single-day query — this is the hard gate. Cross-tenant access is blocked by `@PreAuthorize` and resource/service-type ownership checks.

---

## User Story

```
As a Booking User
I want to query available slots for a resource and service type on one or more dates
So that I can choose a time slot for my booking
```

---

## Acceptance Criteria

- [ ] **AC-01**: `GET /api/v1/tenants/{tenantId}/slots?resourceId=&serviceTypeId=&locationId=&date=YYYY-MM-DD` returns a `200` with the correct available slot list for a resource with no bookings
- [ ] **AC-02**: The response excludes slots that are blocked by `CONFIRMED` or `PENDING_HOLD` bookings
- [ ] **AC-03**: A resource belonging to Tenant B returns `404 RESOURCE_NOT_FOUND` when queried with a Tenant A JWT
- [ ] **AC-04**: Response time p99 < 300ms for a single-day query measured against a warm instance (MockMvc timing assert or k6)
- [ ] **AC-05**: A 7-day range request (`date` + `rangeEndDate` = 6 days later) returns slots for all 7 dates in a single response
- [ ] **AC-06**: `rangeEndDate` more than 6 days after `date` returns `422 DATE_RANGE_TOO_LARGE`
- [ ] **AC-07**: A `date` in the past returns `422 DATE_IN_PAST`
- [ ] **AC-08 (Tenant isolation)**: Resource and service-type ownership validated against `tenantId` — zero cross-tenant data in response
- [ ] **AC-09 (Domain abstraction)**: No industry-specific terms in any class name, method name, or API path in this package

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

`SlotController` is a thin REST layer — it validates inputs, checks resource and service-type ownership, and delegates to `SlotCalculatorService`. No business logic lives in the controller. The endpoint iterates over each date in the requested range and calls `computeAvailableSlots` per date, collecting results into a `LinkedHashMap` (preserving date order).

### Data Flow / Sequence

```
GET /api/v1/tenants/{tenantId}/slots?...
  → @PreAuthorize tenantGuard.check()
  → validate: date not in past
  → validate: rangeEndDate - date ≤ 6
  → ResourceRepository.findByIdAndTenantId()     [ownership check]
  → ServiceTypeRepository.findByIdAndTenantId()  [ownership check]
  → for each date in [date .. rangeEndDate]:
      slotCalculator.computeAvailableSlots()
  → return SlotAvailabilityResponse
```

### File Structure

```
apps/api/src/main/java/com/scheduler/
├── controller/
│   └── SlotController.java              ← REST endpoint
└── dto/
    └── SlotAvailabilityResponse.java    ← Java 21 record

apps/api/src/test/java/com/scheduler/slot/
└── SlotControllerIT.java               ← integration + timing tests
```

### Interface Contracts

```java
// Response DTO — Java 21 record
public record SlotAvailabilityResponse(
    UUID resourceId,
    UUID serviceTypeId,
    Map<LocalDate, List<AvailableSlot>> slots
) {}

// Controller method signature only
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/slots")
public class SlotController {

    @GetMapping
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public SlotAvailabilityResponse getSlots(
        @PathVariable UUID tenantId,
        @RequestParam UUID locationId,
        @RequestParam UUID resourceId,
        @RequestParam UUID serviceTypeId,
        @RequestParam @DateTimeFormat(iso = DATE) LocalDate date,
        @RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate rangeEndDate
    );
}
```

**Error codes**:

| Condition | HTTP | Code |
|-----------|------|------|
| Resource not found for tenant | 404 | `RESOURCE_NOT_FOUND` |
| Service type not found for tenant | 404 | `SERVICE_TYPE_NOT_FOUND` |
| Date in the past | 422 | `DATE_IN_PAST` |
| Range > 7 days | 422 | `DATE_RANGE_TOO_LARGE` |

### Design Rationale

- **ADR-001**: Slots are computed on-demand by `SlotCalculatorService`; `SlotController` is a pure pass-through to that service.
- **NFR-1.2 (p99 < 300ms)**: Redis caching of schedules and holidays (ATOM-SLOT-008) is the primary lever to meet this gate. The controller itself adds no significant overhead — validation and ownership checks are constant-time.
- **7-day range cap**: Prevents unbounded computation loops and keeps response payloads predictable. Single-day queries are the common case; the range is provided for calendar-view UIs.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL) + API (MockMvc)

```
- shouldReturnAvailableSlots_forResourceWithNoBookings:
    Given: resource with Monday 09:00–17:00 schedule; no bookings; date = next Monday
    Assert: response contains ≥ 1 slot starting at 09:00; status 200

- shouldExcludeBookedSlot_fromResponse:
    Given: confirmed booking at 10:00 on target date
    Assert: response for that date does not include a slot at 10:00

- shouldReturnSevenDays_forRangeRequest:
    Given: rangeEndDate = date + 6
    Assert: response.slots has exactly 7 keys; all dates between date and rangeEndDate inclusive

- shouldReturn404_forCrossTenantResource:
    Given: resource belongs to tenantB; JWT is tenantA
    Assert: response is 404 RESOURCE_NOT_FOUND

- shouldReturn422_forDateRangeOver7Days:
    Given: rangeEndDate = date + 7
    Assert: response is 422 DATE_RANGE_TOO_LARGE
```

**Coverage requirements**:
- Line coverage ≥ 80% on `SlotController`
- Performance test: single-day query against warm Testcontainers instance must complete within 300ms (measured via `StopWatch` in test)

---

## Implementation Constraints

- Every repository ownership check must include `tenant_id`
- DTOs must be Java 21 records (never classes)
- `@PreAuthorize("@tenantGuard.check(#tenantId)")` required on the `getSlots` method
- No business logic in `SlotController` — delegate entirely to `SlotCalculatorService`
- Date range maximum is 6 days difference (inclusive: 7 dates total)
- Past dates rejected with `422 DATE_IN_PAST` before any service call
- `rangeEndDate` defaults to `date` if not supplied (single-day query)
- Response map must be ordered by date (use `LinkedHashMap`)
- No `System.out.println` — use SLF4J structured logging

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/com/scheduler/slot/SlotControllerIT.java`
2. Write `shouldReturnAvailableSlots_forResourceWithNoBookings` — assert it fails (controller does not exist)
3. Write `shouldReturn404_forCrossTenantResource` — assert it fails

### GREEN — Minimum code to pass

1. Create `SlotAvailabilityResponse.java` record
2. Implement `SlotController.java` with validation and `SlotCalculatorService` delegation
3. Wire `ResourceRepository` and `ServiceTypeRepository` for ownership checks
4. Pass all test scenarios

### REFACTOR — Quality pass

1. Add structured logging for date-range queries (`log.debug("Slot query: tenantId={}, resourceId={}, dates={}", ...)`)
2. Add Javadoc to the controller method
3. Add MockMvc `StopWatch` assertion for 300ms p99 gate
4. Run `/security-scan` on the new controller

---

## Implementation Reference

### SlotController

**File**: `apps/api/src/main/java/com/scheduler/controller/SlotController.java`

```java
// [TASK: ATOM-SLOT-007]
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/slots")
@RequiredArgsConstructor
public class SlotController {

    private final SlotCalculatorService slotCalculator;
    private final ResourceRepository    resourceRepository;
    private final ServiceTypeRepository serviceTypeRepository;

    /**
     * GET /api/v1/tenants/{tenantId}/slots
     * ?locationId=&resourceId=&serviceTypeId=&date=YYYY-MM-DD[&rangeEndDate=YYYY-MM-DD]
     */
    @GetMapping
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public SlotAvailabilityResponse getSlots(
            @PathVariable UUID tenantId,
            @RequestParam UUID locationId,
            @RequestParam UUID resourceId,
            @RequestParam UUID serviceTypeId,
            @RequestParam @DateTimeFormat(iso = DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DATE) LocalDate rangeEndDate) {

        LocalDate endDate = rangeEndDate != null ? rangeEndDate : date;

        // Validate date not in past
        if (date.isBefore(LocalDate.now())) {
            throw new ValidationException("DATE_IN_PAST");
        }
        // Validate range ≤ 7 days
        if (ChronoUnit.DAYS.between(date, endDate) > 6) {
            throw new ValidationException("DATE_RANGE_TOO_LARGE");
        }
        // Validate resource belongs to tenant
        resourceRepository.findByIdAndTenantId(resourceId, tenantId)
            .orElseThrow(() -> new ResourceNotFoundException(resourceId));
        // Validate service type belongs to tenant
        serviceTypeRepository.findByIdAndTenantId(serviceTypeId, tenantId)
            .orElseThrow(() -> new ServiceTypeNotFoundException(serviceTypeId));

        Map<LocalDate, List<AvailableSlot>> slotsByDate = new LinkedHashMap<>();
        for (LocalDate d = date; !d.isAfter(endDate); d = d.plusDays(1)) {
            slotsByDate.put(d, slotCalculator.computeAvailableSlots(
                resourceId, serviceTypeId, locationId, d, tenantId));
        }

        return new SlotAvailabilityResponse(resourceId, serviceTypeId, slotsByDate);
    }
}
```

### Response Format Example

```json
{
  "resourceId": "uuid",
  "serviceTypeId": "uuid",
  "slots": {
    "2026-06-20": [
      { "startTime": "2026-06-20T09:00:00Z", "endTime": "2026-06-20T10:00:00Z", "durationMinutes": 60 },
      { "startTime": "2026-06-20T10:00:00Z", "endTime": "2026-06-20T11:00:00Z", "durationMinutes": 60 }
    ],
    "2026-06-21": []
  }
}
```

---

## Integration Points

**Depends on**: ATOM-SLOT-005 and ATOM-SLOT-006 (`SlotCalculatorService` with both methods), ATOM-RESOURCE-002 (`ResourceRepository`), ATOM-SERVICE-003 (`ServiceTypeRepository`)

**Enables**: ATOM-SLOT-008 (Redis caching added to the methods called by this endpoint), ATOM-UI-014 (Next.js slot calendar calls this endpoint)

**Cascading updates required**:
- `docs/API-SPEC.md` — add slot availability endpoint
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/com/scheduler/controller/SlotController.java` | New | Slot availability REST endpoint |
| `apps/api/src/main/java/com/scheduler/dto/SlotAvailabilityResponse.java` | New | Response DTO |
| `apps/api/src/test/java/com/scheduler/slot/SlotControllerIT.java` | New | Integration + timing tests |
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
