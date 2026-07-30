# ATOM-BOOKING-010: BookingService — Confirm Booking

**Status**: ✅ Complete
**Feature**: booking-engine
**Phase**: 2 (Core)
**Tags**: [CONCURRENCY]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-BOOKING-009
**Blocks**: ATOM-BOOKING-011, ATOM-BOOKING-013
**PR**: TBD

---

## Overview

Transitions a `PENDING_HOLD` booking to `CONFIRMED`. The confirmation step validates the customer's extension data against the service type's `intakeSchema`, generates a unique confirmation code, and clears `holdExpiresAt`. If the hold has already expired, the confirmation is rejected with `409 HOLD_EXPIRED`. A Phase 3 outbox write will be added later — for now, only the DB state is updated.

---

## User Story

```
As a Booking User
I want to confirm my pending hold by submitting the required intake information
So that my booking is officially reserved and I receive a confirmation code
```

---

## Acceptance Criteria

- [ ] **AC-01**: `confirmBooking` sets `status = CONFIRMED` and persists the booking in the DB
- [ ] **AC-02**: Extension data submitted during confirmation is stored in the `extension` JSONB column as submitted
- [ ] **AC-03**: Extension data failing the service type's `intakeSchema` validation returns `422 EXTENSION_SCHEMA_VIOLATION` with field-level error details
- [ ] **AC-04**: Calling `confirmBooking` on a booking already in `CONFIRMED` status returns `409 ALREADY_CONFIRMED`
- [ ] **AC-05**: Calling `confirmBooking` on a booking whose `holdExpiresAt` has passed returns `409 HOLD_EXPIRED`
- [ ] **AC-06**: Confirmation code matches pattern `{TENANT_SLUG}-{YYYY}-{5-digit-sequence}` and is unique within the tenant for the calendar year
- [ ] **AC-07**: `holdExpiresAt` is set to `null` after successful confirmation
- [ ] **AC-08 (Tenant isolation)**: Booking is loaded with `tenant_id` in the WHERE clause — cross-tenant confirmation not possible
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

`BookingService.confirmBooking` extends the `BookingService` established in ATOM-BOOKING-009. The method loads the booking by `(id, tenantId)`, performs state guards (not already confirmed, not already expired), validates extension data against the service type's `intakeSchema` using `networknt/json-schema-validator`, generates a Redis-backed confirmation code sequence, and saves the updated booking within a single `@Transactional` boundary.

### Data Flow / Sequence

```
POST /api/v1/tenants/{tenantId}/bookings/{bookingId}/confirm
  → @PreAuthorize tenantGuard.check()
  → BookingService.confirmBooking()
      → BookingRepository.findByIdAndTenantId()      [tenant-scoped load]
      → state guard: status == PENDING_HOLD
      → state guard: holdExpiresAt > now()
      → ServiceTypeRepository.findById()             [load intakeSchema]
      → validateExtension(extensionData, intakeSchema)
      → generateConfirmationCode(tenantId)           [Redis INCR counter]
      → booking.status = CONFIRMED
      → booking.extension = extensionData
      → booking.holdExpiresAt = null
      → bookingRepository.save()
      → DB commit
      // Note: Outbox write added in Phase 3 (ATOM-KAFKA-*)
  → return ConfirmationResponse
```

### File Structure

```
apps/api/src/main/java/com/scheduler/
├── service/
│   └── BookingService.java              ← add confirmBooking()
├── controller/
│   └── BookingController.java           ← add POST .../confirm endpoint
└── dto/
    ├── ConfirmBookingRequest.java
    └── ConfirmationResponse.java

apps/api/src/test/java/com/scheduler/booking/
└── BookingConfirmIT.java                ← confirmation integration tests
```

### Interface Contracts

```java
// DTOs — Java 21 records
public record ConfirmBookingRequest(
    @NotNull Map<String, Object> extensionData
) {}

public record ConfirmationResponse(
    UUID bookingId,
    String status,
    String confirmationCode,
    Instant slotStart,
    Instant slotEnd
) {}

// New method on BookingService
public class BookingService {
    @Transactional
    public ConfirmationResponse confirmBooking(
        UUID bookingId,
        Map<String, Object> extensionData,
        UUID tenantId
    );
}
```

### Design Rationale

- **Extension validation at confirm time**: The intake form data is submitted during confirmation (not hold creation), because the checkout form renders from `intakeSchema` only after the hold is placed. Validating at confirm time ensures data is present before the slot is permanently locked.
- **Redis counter for confirmation codes**: `INCR confirm-seq:{tenantId}` is atomic and produces a monotonically increasing sequence per tenant-year. This avoids a DB sequence table and is crash-safe (Redis counter persists across restarts if AOF/RDB is configured).
- **Outbox deferred to Phase 3**: The `BOOKING_CONFIRMED` event will be written to the outbox table in Phase 3. A code comment marks the insertion point. No Kafka write happens in this atom.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL + Redis)

```
- shouldConfirmHold_persistsCorrectly:
    Given: PENDING_HOLD booking with valid extensionData matching intakeSchema
    Assert: status = CONFIRMED; extension stored correctly; holdExpiresAt = null; confirmationCode not null

- shouldRejectInvalidExtension_returns422:
    Given: intakeSchema requires field "name" (string); extensionData = {}
    Assert: response is 422 EXTENSION_SCHEMA_VIOLATION with error mentioning "name"

- shouldRejectAlreadyConfirmed_returns409:
    Given: booking status = CONFIRMED
    Assert: confirmBooking returns 409 ALREADY_CONFIRMED

- shouldRejectExpiredHold_returns409:
    Given: booking holdExpiresAt = now() - 1 second
    Assert: confirmBooking returns 409 HOLD_EXPIRED

- shouldGenerateUniqueConfirmationCode_perTenant:
    Given: two confirmations for same tenant in same year
    Assert: both codes match pattern {SLUG}-{YYYY}-{5-digit}; codes are different
```

**Coverage requirements**:
- Line coverage ≥ 80% on `BookingService.confirmBooking`
- Schema validation path must test both valid and invalid extension data

---

## Implementation Constraints

- Every JPA query must include `tenant_id` in the WHERE clause
- DTOs must be Java 21 records (never classes)
- `confirmBooking` must be `@Transactional`
- All controller endpoints must carry `@PreAuthorize("@tenantGuard.check(#tenantId)")`
- Extension data validated against `intakeSchema` using `networknt/json-schema-validator` (reuse `IntakeSchemaValidator` from ATOM-SERVICE-003)
- Confirmation code pattern: `{TENANT_SLUG}-{YYYY}-{5-digit-zero-padded-sequence}`
- Redis counter key: `confirm-seq:{tenantId}` — incremented atomically with `INCR`
- `holdExpiresAt` must be null-checked before comparing to `now()` (already-confirmed bookings have null `holdExpiresAt`)
- No direct Kafka writes — outbox write deferred to Phase 3 (add `// TODO: Phase 3 — write outbox event` comment)
- No `System.out.println` — use SLF4J structured logging

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/com/scheduler/booking/BookingConfirmIT.java`
2. Write `shouldConfirmHold_persistsCorrectly` — assert it fails
3. Write `shouldRejectExpiredHold_returns409` — assert it fails

### GREEN — Minimum code to pass

1. Add `confirmationCode` column to bookings table (Flyway migration)
2. Add `confirmBooking()` method to `BookingService`
3. Add `ConfirmBookingRequest` and `ConfirmationResponse` records
4. Add `POST .../bookings/{bookingId}/confirm` endpoint to `BookingController`
5. Reuse `IntakeSchemaValidator` for extension validation

### REFACTOR — Quality pass

1. Add structured logging: `log.info("Booking confirmed: id={}, code={}, tenantId={}", ...)`
2. Add Javadoc to `confirmBooking` documenting state machine transitions
3. Verify Redis counter survives application restart (document Redis persistence requirement)
4. Run `/security-scan` on the updated controller

---

## Implementation Reference

### BookingService.confirmBooking

**File**: `apps/api/src/main/java/com/scheduler/service/BookingService.java`

```java
// [TASK: ATOM-BOOKING-010]
@Transactional
public ConfirmationResponse confirmBooking(UUID bookingId,
                                            Map<String,Object> extensionData,
                                            UUID tenantId) {
    // 1. Load booking (tenant scoped)
    Booking booking = bookingRepository.findByIdAndTenantId(bookingId, tenantId)
        .orElseThrow(() -> new BookingNotFoundException(bookingId));

    // 2. State guard
    if ("CONFIRMED".equals(booking.getStatus())) {
        throw new BookingAlreadyConfirmedException(bookingId);
    }
    if (!"PENDING_HOLD".equals(booking.getStatus())) {
        throw new InvalidBookingStateException(booking.getStatus(), "PENDING_HOLD");
    }
    if (booking.getHoldExpiresAt().isBefore(Instant.now())) {
        throw new HoldExpiredException(bookingId);
    }

    // 3. Validate extensionData against serviceType.intakeSchema
    ServiceType service = serviceTypeRepository.findById(booking.getServiceTypeId())
        .orElseThrow();
    validateExtension(extensionData, service.getIntakeSchema());

    // 4. Generate confirmation code
    String confirmCode = generateConfirmationCode(tenantId);

    // 5. Update booking
    booking.setStatus("CONFIRMED");
    booking.setExtension(extensionData);
    booking.setHoldExpiresAt(null);
    booking.setConfirmationCode(confirmCode);
    bookingRepository.save(booking);
    // TODO: Phase 3 — write outbox event (BOOKING_CONFIRMED)

    return new ConfirmationResponse(bookingId, "CONFIRMED", confirmCode,
        booking.getSlotStart(), booking.getSlotEnd());
}

private void validateExtension(Map<String,Object> data, JsonNode schema) {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode dataNode = mapper.valueToTree(data);
    JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    JsonSchema jsonSchema = factory.getSchema(schema);
    Set<ValidationMessage> errors = jsonSchema.validate(dataNode);
    if (!errors.isEmpty()) {
        throw new ExtensionSchemaViolationException(errors);
    }
}

private String generateConfirmationCode(UUID tenantId) {
    // Pattern: {tenantSlug}-{YYYY}-{5-digit-sequence}
    Tenant tenant = tenantRepository.findById(tenantId).orElseThrow();
    String counterKey = "confirm-seq:" + tenantId;
    Long seq = redisTemplate.opsForValue().increment(counterKey);
    int year = LocalDate.now().getYear();
    return "%s-%d-%05d".formatted(tenant.getSlug().toUpperCase(), year, seq);
}
```

---

## Integration Points

**Depends on**: ATOM-BOOKING-009 (Booking entity with PENDING_HOLD state, `holdExpiresAt` field), ATOM-SERVICE-003 (`intakeSchema` on ServiceType)

**Enables**: ATOM-BOOKING-011 (cancel only valid on CONFIRMED bookings), ATOM-BOOKING-013 (confirm-after-expiry scenario in concurrency tests), ATOM-UI-014 (Next.js checkout submits to this endpoint)

**Cascading updates required**:
- `docs/API-SPEC.md` — add booking confirm endpoint
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/com/scheduler/service/BookingService.java` | Modified | Add confirmBooking() |
| `apps/api/src/main/java/com/scheduler/controller/BookingController.java` | Modified | Add confirm endpoint |
| `apps/api/src/main/java/com/scheduler/dto/ConfirmBookingRequest.java` | New | Confirm request DTO |
| `apps/api/src/main/java/com/scheduler/dto/ConfirmationResponse.java` | New | Confirm response DTO |
| `apps/api/src/main/resources/db/migration/V0NN__add_confirmation_code_to_bookings.sql` | New | Add confirmation_code column |
| `apps/api/src/test/java/com/scheduler/booking/BookingConfirmIT.java` | New | Confirmation integration tests |
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
