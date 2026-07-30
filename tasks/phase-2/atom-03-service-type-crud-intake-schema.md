# ATOM-SERVICE-003: Service Type CRUD and Intake Schema Storage

**Status**: ✅ Complete
**Feature**: service-type
**Phase**: 2 (Core)
**Tags**: [CONFIG]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-LOCATION-001
**Blocks**: ATOM-SLOT-006, ATOM-BOOKING-009, ATOM-UI-016
**PR**: TBD

---

## Overview

Implements CRUD for the `ServiceType` entity, which defines a bookable appointment type including its duration, pre/post buffer windows, and allowed resource types. The `intakeSchema` field stores a JSON Schema draft-07 document that drives the dynamic customer intake form at booking time. Schema validity is enforced server-side using the `networknt/json-schema-validator` library. Soft-delete prevents new bookings on inactive service types without affecting existing confirmed ones.

---

## User Story

```
As a Tenant Admin
I want to define service types with duration, buffers, and a custom intake form schema
So that customers are guided through the correct data capture when booking each service
```

---

## Acceptance Criteria

- [ ] **AC-01**: `POST /api/v1/tenants/{tenantId}/service-types` with invalid JSON Schema in `intakeSchema` returns `422 INVALID_JSON_SCHEMA` with a list of validation errors
- [ ] **AC-02**: `POST` with `durationMinutes < 5` or `durationMinutes > 480` returns `400`
- [ ] **AC-03**: `POST` with `bufferBeforeMin` or `bufferAfterMin` outside `[0, 120]` returns `400`
- [ ] **AC-04**: `GET /api/v1/tenants/{tenantId}/service-types/{id}` response includes the full `intakeSchema` field — used by the Next.js form builder
- [ ] **AC-05**: `DELETE .../service-types/{id}` sets `status = inactive`; inactive service types are excluded from the default list response
- [ ] **AC-06**: Inactive service type blocks new booking holds (`BookingService` checks status); existing `CONFIRMED` bookings are not affected
- [ ] **AC-07 (Tenant isolation)**: All JPA queries include `tenant_id` in the WHERE clause — zero cross-tenant rows returned
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms (`treatment`, `appointment`, `exam`, `visit`) appear in any class name, field name, or API path in this package

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

`ServiceTypeController` → `ServiceTypeService` → `ServiceTypeRepository` → PostgreSQL `service_types` table. JSON Schema validation runs in the service layer using the `networknt/json-schema-validator` meta-schema check before the entity is persisted. The `intakeSchema` column is stored as `jsonb`. Soft-delete is a JPQL `UPDATE` that sets `status = inactive`.

### File Structure

```
apps/api/src/main/java/com/scheduler/
├── domain/entity/
│   └── ServiceType.java                 ← JPA entity
├── repository/
│   └── ServiceTypeRepository.java
├── service/
│   └── ServiceTypeService.java          ← JSON Schema validation, soft-delete
├── controller/
│   └── ServiceTypeController.java
└── dto/
    ├── CreateServiceTypeRequest.java
    ├── UpdateServiceTypeRequest.java
    └── ServiceTypeResponse.java

apps/api/src/main/resources/db/migration/
└── V010__create_service_types.sql
```

### Interface Contracts

```java
// DTOs — Java 21 records
public record CreateServiceTypeRequest(
    @NotBlank @Size(max = 255) String name,
    String description,
    @NotNull @Min(5) @Max(480) Integer durationMinutes,
    @NotNull @Min(0) @Max(120) Integer bufferBeforeMin,
    @NotNull @Min(0) @Max(120) Integer bufferAfterMin,
    @NotNull JsonNode intakeSchema,
    List<String> allowedResourceTypes
) {}

public record UpdateServiceTypeRequest(
    @Size(max = 255) String name,
    String description,
    @Min(5) @Max(480) Integer durationMinutes,
    @Min(0) @Max(120) Integer bufferBeforeMin,
    @Min(0) @Max(120) Integer bufferAfterMin,
    JsonNode intakeSchema,
    List<String> allowedResourceTypes
) {}

public record ServiceTypeResponse(
    UUID id,
    UUID tenantId,
    String name,
    String description,
    int durationMinutes,
    int bufferBeforeMin,
    int bufferAfterMin,
    JsonNode intakeSchema,
    List<String> allowedResourceTypes,
    String status,
    Instant createdAt,
    Instant updatedAt
) {}

// Repository interface
public interface ServiceTypeRepository extends JpaRepository<ServiceType, UUID> {
    Page<ServiceType> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);
    Optional<ServiceType> findByIdAndTenantId(UUID id, UUID tenantId);

    @Modifying
    @Query("UPDATE ServiceType s SET s.status = 'inactive' WHERE s.id = :id AND s.tenantId = :tenantId")
    int softDelete(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}

// Service interface
public interface ServiceTypeService {
    ServiceTypeResponse create(UUID tenantId, CreateServiceTypeRequest request);
    ServiceTypeResponse get(UUID tenantId, UUID serviceTypeId);
    Page<ServiceTypeResponse> list(UUID tenantId, String status, Pageable pageable);
    ServiceTypeResponse update(UUID tenantId, UUID serviceTypeId, UpdateServiceTypeRequest request);
    void softDelete(UUID tenantId, UUID serviceTypeId);
}
```

### Design Rationale

- **ADR-004**: Row-level tenant isolation via `tenant_id` discriminator on every query.
- **ADR-005**: `intakeSchema` stored as JSONB — allows arbitrary JSON Schema documents without schema migrations per new service type. Core scheduling logic never reads `intakeSchema`; it is consumed only by the booking confirmation flow to validate `extension` data.
- **networknt/json-schema-validator**: Used for meta-schema validation (is the submitted document a valid JSON Schema draft-07?) rather than instance validation. This runs before persistence to give deterministic, field-level error feedback.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL) + API (MockMvc)

```
- shouldRejectInvalidIntakeSchema_returns422:
    Given: intakeSchema = {"type": "invalid-type"}
    Assert: response is 422 INVALID_JSON_SCHEMA with at least 1 validation error message

- shouldRejectOutOfRangeDuration_returns400:
    Given: durationMinutes = 4
    Assert: response is 400 with field error on "durationMinutes"

- shouldReturnFullIntakeSchema_inGetResponse:
    Given: service type created with a 3-field intakeSchema
    Assert: GET response intakeSchema is structurally identical to what was submitted

- shouldSoftDelete_blockNewBookings:
    Given: service type is soft-deleted (status = inactive)
    Assert: BookingService.createHold() with this serviceTypeId throws ServiceTypeInactiveException

- shouldEnforceTenantIsolation_returns404:
    Given: service type belongs to tenantB
    Assert: GET with tenantA JWT returns 404
```

**Coverage requirements**:
- Line coverage ≥ 80% on `ServiceTypeService`
- JSON Schema validation path must have at least 3 test cases (valid, invalid type, missing required keyword)

---

## Implementation Constraints

- Every JPA query must include `tenant_id` in the WHERE clause
- DTOs must be Java 21 records (never classes)
- `ServiceTypeService` methods that mutate state must be `@Transactional`
- All controller endpoints must carry `@PreAuthorize("@tenantGuard.check(#tenantId)")`
- `intakeSchema` validated as JSON Schema draft-07 using `networknt/json-schema-validator` before persistence
- Soft-delete sets `status = inactive` — no physical row deletion
- Inactive service types must be rejected at booking hold time (`BookingService` checks status before creating a hold)
- `extension` JSONB on Booking is validated against this `intakeSchema` during `confirmBooking` — not here
- No `System.out.println` — use SLF4J structured logging

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/com/scheduler/servicetype/ServiceTypeControllerIT.java`
2. Write `shouldRejectInvalidIntakeSchema_returns422` — assert it fails
3. Write `shouldEnforceTenantIsolation_returns404` — assert it fails

### GREEN — Minimum code to pass

1. Create Flyway migration `V010__create_service_types.sql` with `intake_schema JSONB`, `tenant_id UUID NOT NULL`
2. Implement `ServiceType.java` JPA entity
3. Implement `ServiceTypeRepository.java` with tenant-scoped queries
4. Implement `ServiceTypeService.java` — meta-schema validation + CRUD + soft-delete
5. Implement `ServiceTypeController.java` with `@PreAuthorize`

### REFACTOR — Quality pass

1. Extract `IntakeSchemaValidator` as a standalone `@Component` for reuse in `BookingService`
2. Add structured logging
3. Add Javadoc to all `public` service methods
4. Run `/security-scan` on the new controller

---

## Implementation Reference

### JSON Schema Validation

**File**: `apps/api/src/main/java/com/scheduler/service/ServiceTypeService.java`

```java
// [TASK: ATOM-SERVICE-003]
private void validateIntakeSchema(JsonNode schema) {
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    JsonSchema metaSchema = factory.getSchema(
        URI.create("https://json-schema.org/draft-07/schema#"));
    Set<ValidationMessage> errors = metaSchema.validate(schema);
    if (!errors.isEmpty()) {
        throw new InvalidIntakeSchemaException(errors);
    }
}
```

### Create Request Record

**File**: `apps/api/src/main/java/com/scheduler/dto/CreateServiceTypeRequest.java`

```java
// [TASK: ATOM-SERVICE-003]
public record CreateServiceTypeRequest(
    @NotBlank @Size(max = 255) String name,
    String description,
    @NotNull @Min(5) @Max(480) Integer durationMinutes,
    @NotNull @Min(0) @Max(120) Integer bufferBeforeMin,
    @NotNull @Min(0) @Max(120) Integer bufferAfterMin,
    @NotNull JsonNode intakeSchema,            // validated as JSON Schema draft-07
    List<String> allowedResourceTypes
) {}
```

### Soft-Delete Query

**File**: `apps/api/src/main/java/com/scheduler/repository/ServiceTypeRepository.java`

```java
// [TASK: ATOM-SERVICE-003]
// Soft-delete sets status = 'inactive'
// Inactive service types:
//   - Excluded from public list by default
//   - Cannot be used for new bookings (BookingService checks status)
//   - Existing CONFIRMED bookings are NOT affected
@Modifying
@Query("UPDATE ServiceType s SET s.status = 'inactive' WHERE s.id = :id AND s.tenantId = :tenantId")
int softDelete(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
```

---

## Integration Points

**Depends on**: ATOM-LOCATION-001 (tenants table for tenant-scoped queries), Flyway V010

**Enables**: ATOM-SLOT-006 (`computeAvailableSlots` reads `durationMinutes`, `bufferBeforeMin`, `bufferAfterMin`), ATOM-BOOKING-009 (hold creation validates service type is active), ATOM-BOOKING-010 (confirmation validates `extensionData` against `intakeSchema`), ATOM-UI-016 (form builder writes to `intakeSchema`)

**Cascading updates required**:
- `docs/API-SPEC.md` — add ServiceType CRUD endpoints
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/resources/db/migration/V010__create_service_types.sql` | New | ServiceType schema |
| `apps/api/src/main/java/com/scheduler/domain/entity/ServiceType.java` | New | JPA entity |
| `apps/api/src/main/java/com/scheduler/repository/ServiceTypeRepository.java` | New | Data access |
| `apps/api/src/main/java/com/scheduler/service/ServiceTypeService.java` | New | Business logic + schema validation |
| `apps/api/src/main/java/com/scheduler/controller/ServiceTypeController.java` | New | REST endpoints |
| `apps/api/src/main/java/com/scheduler/dto/CreateServiceTypeRequest.java` | New | Create DTO |
| `apps/api/src/main/java/com/scheduler/dto/UpdateServiceTypeRequest.java` | New | Update DTO |
| `apps/api/src/main/java/com/scheduler/dto/ServiceTypeResponse.java` | New | Response DTO |
| `apps/api/src/test/java/com/scheduler/servicetype/ServiceTypeControllerIT.java` | New | Integration tests |
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

*Last updated: 2026-06-18 | Feature: service-type | Phase: 2*
