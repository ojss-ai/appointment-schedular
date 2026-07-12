---
description: Atom design document template for the Multi-Tenant Omni-Industry Scheduling Framework
---

# ATOM-[FEATURE]-[NNN]: [Concise Title — 3–7 words]

**Status**: 🟡 Planned
**Feature**: [feature-slug]
**Phase**: [N] ([Foundation | Core | Kafka | Intelligence | Production])
**Tags**: [[MIGRATION] [SLOT] [CONCURRENCY] [KAFKA] [AUTH] [SECURITY] [TEST] [ADR] [ANALYTICS]]
**Complexity**: Low | Medium | High
**Agent**: [orchestrator | coder | testgen | security | migrations | adr-docs | observability]
**Dependencies**: [ATOM-FEATURE-NNN] or None
**Blocks**: [ATOM-FEATURE-NNN] or None
**PR**: TBD

---

## Overview

2–4 sentences: what this atom implements, why it exists, and the one design decision that most shapes the implementation.

---

## User Story

```
As a [Tenant Admin | Resource | Booking User | System]
I want [capability]
So that [business value]
```

---

## Acceptance Criteria

- [ ] **AC-01**: [Specific, testable, observable condition — reference exact method/endpoint/field names]
- [ ] **AC-02**: [Include measurable value where possible: count, status code, time bound]
- [ ] **AC-03 (Tenant isolation)**: All new JPA queries include `tenant_id` in WHERE clause — zero cross-tenant rows returned
- [ ] **AC-04 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) in any identifier, field name, or API path

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | TBD | TBD | 🔜 Planned |
| AC-02 | TBD | TBD | 🔜 Planned |
| AC-03 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, N criteria rewritten, M marked TBD -->

---

## Technical Design

### Architecture

High-level design decisions, patterns used, separation of concerns across layers (Controller → Service → Repository → DB).

### Data Flow / Sequence (if applicable)

```
BookingService.confirmBooking()
  → outboxService.writeBookingEvent()   [same @Transactional]
  → DB commit
  → Debezium CDC picks up outbox row
  → Kafka topic: tenant.bookings.lifecycle
```

### File Structure

```
apps/api/src/main/java/com/scheduler/
├── domain/
│   └── entity/Foo.java              ← JPA entity
├── repository/
│   └── FooRepository.java           ← JpaRepository interface
├── service/
│   └── FooService.java              ← business logic
└── controller/
    └── FooController.java           ← REST endpoints

apps/web/src/app/[tenantId]/
└── foo/
    ├── page.tsx                     ← server component
    └── foo-form.tsx                 ← client component

apps/api/src/main/resources/db/migration/
└── V0NN__description.sql            ← Flyway migration
```

### Interface Contracts

Java record shapes, interface method signatures, and TypeScript types **only — no method bodies**.
Allowed: `record`, `interface`, discriminated union, `enum`.
Banned: any `{ }` implementation body.

```java
// DTO — Java 21 record
public record FooRequest(
    @NotNull UUID tenantId,
    @NotBlank String name
) {}

public record FooResponse(UUID id, UUID tenantId, String name, Instant createdAt) {}

// Repository signature only
public interface FooRepository extends JpaRepository<Foo, UUID> {
    List<Foo> findByTenantIdOrderByCreatedAtDesc(UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM Foo f WHERE f.id = :id AND f.tenantId = :tenantId")
    Optional<Foo> findByIdAndTenantIdForUpdate(UUID id, UUID tenantId);
}

// Service interface
public interface FooService {
    FooResponse create(UUID tenantId, FooRequest request);
    FooResponse findById(UUID tenantId, UUID id);
}
```

```typescript
// TypeScript type shapes
interface FooFormValues {
  name: string;
  tenantId: string;
}
```

### Design Rationale

Explain the **why** behind key decisions — what alternatives were considered and rejected, and why this approach aligns with the project's ADRs.

- **ADR reference**: [ADR-00N] — [one sentence on relevance]
- **Why pessimistic lock here (not Redis)**: [rationale if applicable]
- **Why outbox (not direct Kafka write)**: [rationale if applicable]

---

## Test Strategy

Describe how this atom will be tested. Use **Given/Assert format** — no test framework code in this section.

**Test type**: Unit (JUnit 5) | Integration (Testcontainers + PostgreSQL + Kafka) | API (MockMvc / REST Assured) | E2E (Playwright) | Load (k6)

```
- shouldReturnOnlyTenantOwnedRecords_whenQueried:
    Given: tenantA and tenantB each have 3 Foo records
    Assert: GET /tenants/{tenantA-id}/foos returns exactly 3 records, all with tenantId = tenantA

- shouldRejectCrossTenantRequest_returns403:
    Given: JWT bearing tenantA claims
    Assert: GET /tenants/{tenantB-id}/foos returns 403 TENANT_MISMATCH

- shouldPersistAtomically_orRollbackBoth:
    Given: exception thrown after Foo insert but before outbox write
    Assert: Foo row not in DB; outbox row not in DB (full rollback)

- shouldBeIdempotent_onDuplicateMessage:
    Given: same Kafka message key delivered twice
    Assert: processed_events has exactly 1 row for that key; side effect triggered exactly once
```

**Coverage requirements**:
- Line coverage ≥ 80% on service class
- Concurrency test must simulate ≥ 10 simultaneous requests if `[CONCURRENCY]` tagged
- Idempotency test required if `[KAFKA]` tagged (duplicate message delivery)

---

## Implementation Constraints

Flat bullet list — no rationale, no code:

- Every JPA query must include `tenant_id` in the WHERE clause
- DTOs must be Java 21 records (never classes)
- Methods that mutate booking state must be `@Transactional`
- All controller endpoints must carry `@PreAuthorize("@tenantGuard.check(#tenantId)")`
- No direct Kafka writes — use `outboxService.writeBookingEvent()` within the caller's transaction
- No `slots` table — `SlotCalculatorService` computes availability on demand
- `extension` JSONB column is read/write only for tenant-injected metadata; core logic must never read it
- No `console.log` in Next.js; no `System.out.println` in Java — use pino / SLF4J
- All Next.js API calls through `apps/web/lib/api-client.ts`
- [Add atom-specific constraints here]

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/…/FooServiceIT.java` with Testcontainers setup
2. Write `shouldReturnOnlyTenantOwnedRecords_whenQueried` — assert it fails (entity doesn't exist yet)
3. Write `shouldRejectCrossTenantRequest_returns403` — assert it fails

### GREEN — Minimum code to pass

1. Create Flyway migration `V0NN__create_foo.sql` with `tenant_id UUID NOT NULL`
2. Implement `Foo.java` JPA entity
3. Implement `FooRepository.java` with tenant-scoped queries
4. Implement `FooService.java` — minimum logic to pass RED tests
5. Implement `FooController.java` with `@PreAuthorize`

### REFACTOR — Quality pass

1. Add structured logging (`log.info("Foo created: id={}, tenantId={}", ...)`)
2. Add Javadoc to all `public` service methods
3. Verify no cross-layer leakage (entity not exposed in controller response)
4. Run `/security-scan` on new controller

---

## Implementation Reference

Complete, production-ready code for this atom. The coder agent must implement exactly this — no deviations without updating the spec.

### [Layer Name — e.g., Flyway Migration V0NN]

**File**: `apps/api/src/main/resources/db/migration/V0NN__create_foo.sql`

```sql
-- [TASK: ATOM-FEATURE-NNN] Foo table
CREATE TABLE foo (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID        NOT NULL REFERENCES tenants(id),
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_foo_tenant_id ON foo(tenant_id);
```

### [Layer Name — e.g., JPA Entity]

**File**: `apps/api/src/main/java/com/scheduler/domain/entity/Foo.java`

```java
// [TASK: ATOM-FEATURE-NNN]
package com.scheduler.domain.entity;
// ... complete implementation
```

---

## Integration Points

**Depends on**: [list atom IDs or infrastructure that must exist first — e.g., "ATOM-BOOKING-001 (bookings table)", "Redis running"]

**Enables**: [what becomes possible after this atom — e.g., "ATOM-BOOKING-003 can now write outbox events"]

**Cascading updates required**:
- `docs/API-SPEC.md` — add new endpoints
- `docs/KAFKA-SPEC.md` — add new topic/schema (if applicable)
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/resources/db/migration/V0NN__create_foo.sql` | New | Schema for Foo |
| `apps/api/src/main/java/.../entity/Foo.java` | New | JPA entity |
| `apps/api/src/main/java/.../repository/FooRepository.java` | New | Data access |
| `apps/api/src/main/java/.../service/FooService.java` | New | Business logic |
| `apps/api/src/main/java/.../controller/FooController.java` | New | REST endpoints |
| `apps/api/src/test/java/.../FooServiceIT.java` | New | Integration tests |
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

> Only include this section if assumptions cannot be verified against source files.

⚠️ The following assumptions could not be verified and require confirmation before implementation:

| # | Assumption | Expected source | Risk | Blocker? |
|---|-----------|-----------------|------|----------|
| 1 | [Assumption text] | [File path] | HIGH / MEDIUM / LOW | YES / NO |

---

*Last updated: [date] | Feature: [feature-slug] | Phase: [N]*
