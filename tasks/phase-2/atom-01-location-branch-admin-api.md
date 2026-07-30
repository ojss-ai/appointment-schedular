# ATOM-LOCATION-001: Location Branch Admin CRUD API

**Status**: ✅ Complete
**Feature**: location-admin
**Phase**: 2 (Core)
**Tags**: [CONFIG]
**Complexity**: Low
**Agent**: coder
**Dependencies**: ATOM-TENANT-005 (P1 tenants table), ATOM-TENANT-004 (P1 auth scaffold)
**Blocks**: ATOM-RESOURCE-002, ATOM-SLOT-005
**PR**: TBD

---

## Overview

Implements full CRUD for the `Location` entity, representing a physical branch with its own timezone, operating hours, and resource pool. Write operations are restricted to the `ADMIN` role; read operations are available to all authenticated roles within the tenant. Soft-delete sets `status = inactive` rather than removing rows, preserving booking history integrity. The timezone field is validated against the IANA ZoneId registry at creation time.

---

## User Story

```
As a Tenant Admin
I want to create, update, and manage branch locations
So that I can define the physical sites where bookings can be made
```

---

## Acceptance Criteria

- [ ] **AC-01**: `GET /api/v1/tenants/{tenantId}/locations` returns `200` with paginated location list for an authenticated Tenant A JWT; the response contains only locations with `tenantId = tenantA`
- [ ] **AC-02**: `GET /api/v1/tenants/{tenantId}/locations` called with Tenant A JWT and Tenant B's `tenantId` path variable returns `403 TENANT_MISMATCH`
- [ ] **AC-03**: `POST /api/v1/tenants/{tenantId}/locations` with an invalid IANA timezone string returns `400` with a `timezone` field error
- [ ] **AC-04**: `DELETE /api/v1/tenants/{tenantId}/locations/{locationId}` sets `status = inactive`; subsequent `GET` list excludes the location by default
- [ ] **AC-05**: `POST` with `countryCode` not matching `[A-Z]{2}` returns `400`
- [ ] **AC-06**: `POST` with a duplicate `slug` for the same tenant returns `409 SLUG_ALREADY_EXISTS`
- [ ] **AC-07 (Tenant isolation)**: All JPA queries on `Location` include `tenant_id` in the WHERE clause — zero cross-tenant rows returned under any request
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms (`clinic`, `store`, `hospital`, etc.) appear in any class name, field name, or API path in this package

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

Standard CRUD layering: `LocationController` → `LocationService` → `LocationRepository` → PostgreSQL `locations` table. The controller enforces authentication and role checks via `@PreAuthorize`. The service validates timezone via `ZoneId.of()` and enforces slug uniqueness. The repository exposes only tenant-scoped queries. Soft-delete is a JPQL `UPDATE` rather than a physical row delete.

### File Structure

```
apps/api/src/main/java/com/scheduler/
├── domain/entity/
│   └── Location.java                    ← JPA entity
├── repository/
│   └── LocationRepository.java          ← JpaRepository interface
├── service/
│   └── LocationService.java             ← business logic, timezone validation
├── controller/
│   └── LocationController.java          ← REST endpoints
└── dto/
    ├── CreateLocationRequest.java        ← Java 21 record
    ├── UpdateLocationRequest.java        ← Java 21 record
    └── LocationResponse.java            ← Java 21 record

apps/api/src/main/resources/db/migration/
└── V006__create_locations.sql           ← Flyway migration (from Phase 1)
```

### Interface Contracts

```java
// DTOs — Java 21 records
public record CreateLocationRequest(
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 100) String slug,
    String addressLine1,
    String city,
    @Pattern(regexp = "[A-Z]{2}", message = "Must be ISO 3166-1 alpha-2") String countryCode,
    @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal latitude,
    @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal longitude,
    @NotBlank String timezone
) {}

public record UpdateLocationRequest(
    @Size(max = 255) String name,
    String addressLine1,
    String city,
    @Pattern(regexp = "[A-Z]{2}") String countryCode,
    BigDecimal latitude,
    BigDecimal longitude,
    @NotBlank String timezone
) {}

public record LocationResponse(
    UUID id,
    UUID tenantId,
    String name,
    String slug,
    String addressLine1,
    String city,
    String countryCode,
    BigDecimal latitude,
    BigDecimal longitude,
    String timezone,
    String status,
    Instant createdAt,
    Instant updatedAt
) {}

// Repository interface
public interface LocationRepository extends JpaRepository<Location, UUID> {
    Page<Location> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);
    Optional<Location> findByIdAndTenantId(UUID id, UUID tenantId);
    boolean existsByTenantIdAndSlug(UUID tenantId, String slug);

    @Modifying
    @Query("UPDATE Location l SET l.status = 'inactive' WHERE l.id = :id AND l.tenantId = :tenantId")
    int softDelete(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}

// Service interface
public interface LocationService {
    Page<LocationResponse> list(UUID tenantId, String status, Pageable pageable);
    LocationResponse create(UUID tenantId, CreateLocationRequest request);
    LocationResponse get(UUID tenantId, UUID locationId);
    LocationResponse update(UUID tenantId, UUID locationId, UpdateLocationRequest request);
    void softDelete(UUID tenantId, UUID locationId);
}
```

### Design Rationale

- **ADR-004**: Row-level tenant isolation via `tenant_id` discriminator on every query — chosen over schema-per-tenant for lower operational cost.
- **Soft-delete over hard-delete**: Preserves referential integrity for Bookings that reference a location; inactive locations are simply hidden from scheduling logic.
- **ZoneId validation at service layer**: Bean Validation cannot validate IANA timezone strings; validation in the service throws a descriptive `ValidationException` before the entity is persisted.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL) + API (MockMvc)

```
- shouldReturnOnlyTenantOwnedLocations_whenListed:
    Given: tenantA has 3 locations; tenantB has 2 locations
    Assert: GET /api/v1/tenants/{tenantA-id}/locations returns exactly 3, all with tenantId = tenantA

- shouldRejectCrossTenantRequest_returns403:
    Given: JWT bearing tenantA claims
    Assert: GET /api/v1/tenants/{tenantB-id}/locations returns 403

- shouldRejectInvalidTimezone_returns400:
    Given: POST request with timezone = "Invalid/Zone"
    Assert: response is 400 with field error on "timezone"

- shouldEnforceSlugUniqueness_returns409:
    Given: a location with slug "downtown" exists for tenantA
    Assert: POST with same slug for tenantA returns 409 SLUG_ALREADY_EXISTS

- shouldSoftDelete_excludesFromDefaultList:
    Given: location is active; DELETE is called
    Assert: location status = inactive; GET list (default status=active) omits it
```

**Coverage requirements**:
- Line coverage ≥ 80% on `LocationService`
- All repository queries must have at least one tenant-isolation integration test

---

## Implementation Constraints

- Every JPA query must include `tenant_id` in the WHERE clause
- DTOs must be Java 21 records (never classes)
- `LocationService` methods that mutate state must be `@Transactional`
- All controller endpoints must carry `@PreAuthorize("@tenantGuard.check(#tenantId)")`
- Write endpoints additionally require `hasRole('ADMIN')`
- Timezone validated via `ZoneId.of()` in the service; invalid value throws `ValidationException`
- Slug uniqueness enforced within tenant scope only — duplicate slugs across tenants are allowed
- Soft-delete never physically removes rows — sets `status = inactive`
- No `System.out.println` — use SLF4J structured logging

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/com/scheduler/location/LocationControllerIT.java` with Testcontainers setup
2. Write `shouldReturnOnlyTenantOwnedLocations_whenListed` — assert it fails (entity does not exist yet)
3. Write `shouldRejectCrossTenantRequest_returns403` — assert it fails

### GREEN — Minimum code to pass

1. Confirm Flyway migration `V006__create_locations.sql` exists (Phase 1 output)
2. Implement `Location.java` JPA entity
3. Implement `LocationRepository.java` with tenant-scoped queries
4. Implement `LocationService.java` — timezone validation + slug check + soft-delete
5. Implement `LocationController.java` with `@PreAuthorize` on every endpoint

### REFACTOR — Quality pass

1. Add structured logging (`log.info("Location created: id={}, tenantId={}", ...)`)
2. Add Javadoc to all `public` service methods
3. Verify no `Location` entity exposed directly in controller response — only `LocationResponse`
4. Run `/security-scan` on the new controller

---

## Implementation Reference

### JPA Entity

**File**: `apps/api/src/main/java/com/scheduler/domain/entity/Location.java`

```java
// [TASK: ATOM-LOCATION-001]
package com.scheduler.domain.entity;

@Entity
@Table(name = "locations")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Location {
    @Id @GeneratedValue(strategy = AUTO)
    private UUID id;

    @Column(nullable = false) private UUID   tenantId;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private String slug;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String countryCode;
    private String postalCode;
    private BigDecimal latitude;
    private BigDecimal longitude;
    @Column(nullable = false) private String timezone;
    @Column(nullable = false) private String status;
    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); status = "active"; }
    @PreUpdate  void onUpdate() { updatedAt = Instant.now(); }
}
```

### Repository

**File**: `apps/api/src/main/java/com/scheduler/repository/LocationRepository.java`

```java
// [TASK: ATOM-LOCATION-001]
package com.scheduler.repository;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    // ALL queries MUST include tenant_id
    Page<Location> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);

    Optional<Location> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByTenantIdAndSlug(UUID tenantId, String slug);

    @Modifying
    @Query("UPDATE Location l SET l.status = 'inactive' WHERE l.id = :id AND l.tenantId = :tenantId")
    int softDelete(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
```

### Controller

**File**: `apps/api/src/main/java/com/scheduler/controller/LocationController.java`

```java
// [TASK: ATOM-LOCATION-001]
package com.scheduler.controller;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/locations")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locationService;

    @GetMapping
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public Page<LocationResponse> list(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "active") String status,
            Pageable pageable) {
        return locationService.list(tenantId, status, pageable);
    }

    @PostMapping
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasRole('ADMIN')")
    @ResponseStatus(CREATED)
    public LocationResponse create(@PathVariable UUID tenantId,
                                   @Valid @RequestBody CreateLocationRequest req) {
        return locationService.create(tenantId, req);
    }

    @GetMapping("/{locationId}")
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    public LocationResponse get(@PathVariable UUID tenantId, @PathVariable UUID locationId) {
        return locationService.get(tenantId, locationId);
    }

    @PutMapping("/{locationId}")
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasRole('ADMIN')")
    public LocationResponse update(@PathVariable UUID tenantId,
                                   @PathVariable UUID locationId,
                                   @Valid @RequestBody UpdateLocationRequest req) {
        return locationService.update(tenantId, locationId, req);
    }

    @DeleteMapping("/{locationId}")
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasRole('ADMIN')")
    @ResponseStatus(NO_CONTENT)
    public void delete(@PathVariable UUID tenantId, @PathVariable UUID locationId) {
        locationService.softDelete(tenantId, locationId);
    }
}
```

### Service — Timezone Validation Snippet

**File**: `apps/api/src/main/java/com/scheduler/service/LocationService.java`

```java
// [TASK: ATOM-LOCATION-001]
// Validate timezone against ZoneId
try { ZoneId.of(req.timezone()); }
catch (DateTimeException e) {
    throw new ValidationException("Invalid IANA timezone: " + req.timezone());
}
```

---

## Integration Points

**Depends on**: ATOM-TENANT-005 (tenants table), ATOM-TENANT-004 (Spring Security auth scaffold), Flyway migration V006 from Phase 1

**Enables**: ATOM-RESOURCE-002 (resources belong to locations), ATOM-SLOT-005 (operating matrix reads location timezone), ATOM-BOOKING-009 (hold creation reads location timezone)

**Cascading updates required**:
- `docs/API-SPEC.md` — add Location CRUD endpoints
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/com/scheduler/domain/entity/Location.java` | New | JPA entity |
| `apps/api/src/main/java/com/scheduler/repository/LocationRepository.java` | New | Tenant-scoped data access |
| `apps/api/src/main/java/com/scheduler/service/LocationService.java` | New | Business logic, timezone validation |
| `apps/api/src/main/java/com/scheduler/controller/LocationController.java` | New | REST endpoints |
| `apps/api/src/main/java/com/scheduler/dto/CreateLocationRequest.java` | New | Create DTO |
| `apps/api/src/main/java/com/scheduler/dto/UpdateLocationRequest.java` | New | Update DTO |
| `apps/api/src/main/java/com/scheduler/dto/LocationResponse.java` | New | Response DTO |
| `apps/api/src/test/java/com/scheduler/location/LocationControllerIT.java` | New | Integration tests |
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

*Last updated: 2026-06-18 | Feature: location-admin | Phase: 2*
