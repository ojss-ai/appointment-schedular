# ATOM-RESOURCE-002: Resource Registration and Schedule API

**Status**: 🟡 Planned
**Feature**: resource-registration
**Phase**: 2 (Core)
**Tags**: [CONFIG]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-LOCATION-001
**Blocks**: ATOM-SLOT-005
**PR**: TBD

---

## Overview

Implements CRUD for the `Resource` entity (a bookable human or physical asset) together with its nested `ResourceSchedule` and `ResourceBreak` records. Schedule and break replacement is atomic — both collections are deleted and reinserted within a single `@Transactional` boundary to avoid partial-update corruption. The `extension` JSONB column stores tenant-injected domain metadata (e.g., specialty, vehicle type) verbatim; core business logic never reads from it.

---

## User Story

```
As a Tenant Admin
I want to register resources and define their weekly schedules and breaks
So that the slot calculator can compute accurate availability windows
```

---

## Acceptance Criteria

- [ ] **AC-01**: `POST /api/v1/tenants/{tenantId}/locations/{locationId}/resources` creates a resource with schedule and breaks, returning `201` with the full resource representation
- [ ] **AC-02**: `PUT .../resources/{resourceId}/schedule` with overlapping windows on the same `day_of_week` returns `422 OVERLAPPING_SCHEDULE`
- [ ] **AC-03**: `PUT .../resources/{resourceId}/schedule` with `end_time <= start_time` returns `400`
- [ ] **AC-04**: Schedule replace deletes all existing entries and inserts new ones within one transaction — no partial state exists if the operation fails
- [ ] **AC-05**: Resource `extension` JSONB field round-trips correctly — stored as submitted and returned unchanged
- [ ] **AC-06 (Tenant isolation)**: Tenant B resource returns `404` when queried with Tenant A JWT; all repository queries include `tenant_id` in the WHERE clause
- [ ] **AC-07**: Resource with no schedule entries is excluded from slot availability queries (zero results returned by `SlotCalculatorService`)
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms (`doctor`, `mechanic`, `vehicle`, `staff`) appear in any identifier, field name, or API path in this package

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

<!-- AC validation passed: YYYY-MM-DD, 8 criteria rewritten, 8 marked TBD -->

---

## Technical Design

### Architecture

`ResourceController` → `ResourceService` → three repositories (`ResourceRepository`, `ResourceScheduleRepository`, `ResourceBreakRepository`) → PostgreSQL tables `resources`, `resource_schedules`, `resource_breaks`. Schedule and break replacement runs in a single `@Transactional` method: delete-all then save-all. Overlap detection happens in-memory before any DB writes.

### File Structure

```
apps/api/src/main/java/com/scheduler/
├── domain/entity/
│   ├── Resource.java                    ← JPA entity
│   ├── ResourceSchedule.java            ← JPA entity
│   └── ResourceBreak.java               ← JPA entity
├── repository/
│   ├── ResourceRepository.java
│   ├── ResourceScheduleRepository.java
│   └── ResourceBreakRepository.java
├── service/
│   └── ResourceService.java             ← schedule replace, overlap validation
├── controller/
│   └── ResourceController.java          ← REST endpoints
└── dto/
    ├── CreateResourceRequest.java
    ├── UpdateResourceRequest.java
    ├── ResourceResponse.java
    └── ScheduleEntry.java

apps/api/src/main/resources/db/migration/
├── V007__create_resources.sql
├── V008__create_resource_schedules.sql
└── V009__create_resource_breaks.sql
```

### Interface Contracts

```java
// DTOs — Java 21 records
public record CreateResourceRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank String type,
    Map<String, Object> extension,
    List<ScheduleEntry> schedule,
    List<ScheduleEntry> breaks
) {}

public record ScheduleEntry(
    @NotNull @Min(1) @Max(7) Integer dayOfWeek,
    @NotNull LocalTime startTime,
    @NotNull LocalTime endTime
) {}

public record ResourceResponse(
    UUID id,
    UUID tenantId,
    UUID locationId,
    String name,
    String type,
    String status,
    Map<String, Object> extension,
    Instant createdAt,
    Instant updatedAt
) {}

// Repository interfaces
public interface ResourceRepository extends JpaRepository<Resource, UUID> {
    Page<Resource> findByTenantIdAndLocationId(UUID tenantId, UUID locationId, Pageable pageable);
    Optional<Resource> findByIdAndTenantId(UUID id, UUID tenantId);
}

public interface ResourceScheduleRepository extends JpaRepository<ResourceSchedule, UUID> {
    List<ResourceSchedule> findByResourceIdAndDayOfWeek(UUID resourceId, int dayOfWeek);
    void deleteAllByResourceId(UUID resourceId);
}

public interface ResourceBreakRepository extends JpaRepository<ResourceBreak, UUID> {
    List<ResourceBreak> findByResourceIdAndDayOfWeek(UUID resourceId, int dayOfWeek);
    void deleteAllByResourceId(UUID resourceId);
}

// Service interface
public interface ResourceService {
    ResourceResponse create(UUID tenantId, UUID locationId, CreateResourceRequest request);
    ResourceResponse get(UUID tenantId, UUID resourceId);
    void replaceSchedule(UUID tenantId, UUID resourceId, List<ScheduleEntry> entries);
    void replaceBreaks(UUID tenantId, UUID resourceId, List<ScheduleEntry> entries);
}
```

### Design Rationale

- **ADR-004**: All repository queries include `tenant_id` — enforcing row-level isolation without schema-per-tenant overhead.
- **ADR-005**: The `extension` JSONB column accepts arbitrary tenant-injected metadata. Core scheduling logic never reads from `extension`; it is purely a pass-through storage field.
- **Atomic schedule replace**: Delete-then-insert within one transaction prevents the scheduler from reading a half-replaced schedule during the operation.
- **In-memory overlap detection**: Overlap logic runs on the incoming payload before any DB write, keeping the DB transaction short and avoiding unnecessary lock contention.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL) + API (MockMvc)

```
- shouldCreateResourceWithScheduleAndBreaks:
    Given: valid CreateResourceRequest with 3 schedule entries and 1 break
    Assert: resource persisted; 3 ResourceSchedule rows; 1 ResourceBreak row; response = 201

- shouldRejectOverlappingSchedule_returns422:
    Given: schedule entries for Monday 09:00–12:00 and Monday 11:00–14:00
    Assert: response is 422 OVERLAPPING_SCHEDULE; no ResourceSchedule rows saved

- shouldReplaceScheduleAtomically:
    Given: existing 2-entry schedule; PUT with 1 new entry
    Assert: exactly 1 ResourceSchedule row after replace; no old entries remain

- shouldEnforceTenantIsolation_returns404:
    Given: resource belongs to tenantB
    Assert: GET with tenantA JWT returns 404 RESOURCE_NOT_FOUND

- shouldRoundTripExtensionJsonb:
    Given: extension = {"specialty": "cardiology", "roomNumber": 4}
    Assert: GET returns identical extension map
```

**Coverage requirements**:
- Line coverage ≥ 80% on `ResourceService`
- Overlap detection logic must have dedicated unit tests (no DB required)

---

## Implementation Constraints

- Every JPA query must include `tenant_id` in the WHERE clause
- DTOs must be Java 21 records (never classes)
- `replaceSchedule` and `replaceBreaks` must be `@Transactional`
- All controller endpoints must carry `@PreAuthorize("@tenantGuard.check(#tenantId)")`
- `extension` JSONB is stored and returned verbatim — no validation of its content at this layer
- Core scheduling logic must never read from `extension`
- Overlap detection: two windows overlap when `start1 < end2 AND start2 < end1`
- `end_time <= start_time` is rejected with `400` before persistence
- No `System.out.println` — use SLF4J structured logging

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/com/scheduler/resource/ResourceServiceIT.java` with Testcontainers setup
2. Write `shouldCreateResourceWithScheduleAndBreaks` — assert it fails (entities do not exist yet)
3. Write `shouldRejectOverlappingSchedule_returns422` — assert it fails

### GREEN — Minimum code to pass

1. Create Flyway migrations `V007`, `V008`, `V009` for `resources`, `resource_schedules`, `resource_breaks`
2. Implement `Resource.java`, `ResourceSchedule.java`, `ResourceBreak.java` JPA entities
3. Implement three repositories with tenant-scoped queries
4. Implement `ResourceService.java` — create, replaceSchedule (with overlap check), replaceBreaks
5. Implement `ResourceController.java` with `@PreAuthorize` on every method

### REFACTOR — Quality pass

1. Extract overlap detection into `ScheduleOverlapValidator` utility class (testable in isolation)
2. Add structured logging for schedule replace operations
3. Add Javadoc to all `public` service methods
4. Run `/security-scan` on the new controller

---

## Implementation Reference

### Schedule Replace Operation

**File**: `apps/api/src/main/java/com/scheduler/service/ResourceService.java`

```java
// [TASK: ATOM-RESOURCE-002]
@Transactional
public void replaceSchedule(UUID tenantId, UUID resourceId,
                            List<ScheduleEntry> entries) {
    // Validate resource belongs to tenant
    resourceRepository.findByIdAndTenantId(resourceId, tenantId)
        .orElseThrow(() -> new ResourceNotFoundException(resourceId));

    // Validate no overlapping windows per day_of_week
    validateNoOverlaps(entries);

    // Atomic replace
    scheduleRepository.deleteAllByResourceId(resourceId);
    List<ResourceSchedule> schedules = entries.stream()
        .map(e -> ResourceSchedule.builder()
            .resourceId(resourceId)
            .tenantId(tenantId)
            .dayOfWeek(e.dayOfWeek())
            .startTime(e.startTime())
            .endTime(e.endTime())
            .build())
        .toList();
    scheduleRepository.saveAll(schedules);
}

private void validateNoOverlaps(List<ScheduleEntry> entries) {
    Map<Integer, List<ScheduleEntry>> byDay = entries.stream()
        .collect(Collectors.groupingBy(ScheduleEntry::dayOfWeek));
    byDay.forEach((day, windows) -> {
        for (int i = 0; i < windows.size(); i++) {
            for (int j = i + 1; j < windows.size(); j++) {
                if (windows.get(i).startTime().isBefore(windows.get(j).endTime()) &&
                    windows.get(j).startTime().isBefore(windows.get(i).endTime())) {
                    throw new OverlappingScheduleException(day);
                }
            }
        }
    });
}
```

### JSONB Extension Mapping

**File**: `apps/api/src/main/java/com/scheduler/domain/entity/Resource.java` (excerpt)

```java
// [TASK: ATOM-RESOURCE-002]
@Type(JsonBinaryType.class)
@Column(columnDefinition = "jsonb")
private Map<String, Object> extension = new HashMap<>();
```

---

## Integration Points

**Depends on**: ATOM-LOCATION-001 (locations table, `location_id` FK), Flyway migrations V007–V009

**Enables**: ATOM-SLOT-005 (`SlotCalculatorService` reads `resource_schedules` and `resource_breaks`), ATOM-SLOT-008 (Redis caching of schedules and breaks)

**Cascading updates required**:
- `docs/API-SPEC.md` — add Resource and Schedule endpoints
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/resources/db/migration/V007__create_resources.sql` | New | Resources schema |
| `apps/api/src/main/resources/db/migration/V008__create_resource_schedules.sql` | New | Schedules schema |
| `apps/api/src/main/resources/db/migration/V009__create_resource_breaks.sql` | New | Breaks schema |
| `apps/api/src/main/java/com/scheduler/domain/entity/Resource.java` | New | JPA entity |
| `apps/api/src/main/java/com/scheduler/domain/entity/ResourceSchedule.java` | New | JPA entity |
| `apps/api/src/main/java/com/scheduler/domain/entity/ResourceBreak.java` | New | JPA entity |
| `apps/api/src/main/java/com/scheduler/repository/ResourceRepository.java` | New | Data access |
| `apps/api/src/main/java/com/scheduler/repository/ResourceScheduleRepository.java` | New | Data access |
| `apps/api/src/main/java/com/scheduler/repository/ResourceBreakRepository.java` | New | Data access |
| `apps/api/src/main/java/com/scheduler/service/ResourceService.java` | New | Business logic |
| `apps/api/src/main/java/com/scheduler/controller/ResourceController.java` | New | REST endpoints |
| `apps/api/src/test/java/com/scheduler/resource/ResourceServiceIT.java` | New | Integration tests |
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

*Last updated: 2026-06-18 | Feature: resource-registration | Phase: 2*
