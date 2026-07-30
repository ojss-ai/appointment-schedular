# ATOM-BOOKING-013: Booking Concurrency Integration Tests

**Status**: ✅ Complete
**Feature**: booking-engine
**Phase**: 2 (Core)
**Tags**: [TEST] [CONCURRENCY]
**Complexity**: High
**Agent**: testgen
**Dependencies**: ATOM-BOOKING-009, ATOM-BOOKING-010, ATOM-BOOKING-012
**Blocks**: None
**PR**: TBD

---

## Overview

A comprehensive concurrency and edge-case integration test suite covering the full booking state machine under concurrent load. All 6 scenarios must pass reliably before Phase 3 begins. Tests use Testcontainers with a shared PostgreSQL container (`withReuse(true)`) to minimize overhead. Each test resets its own booking data via `@Sql` or `@AfterEach` cleanup. The GC deadlock scenario runs the `HoldGcScheduler` in the background while concurrent `createHold` calls are in flight.

---

## User Story

```
As a System
I want proof that the booking engine is concurrency-safe under all edge cases
So that Phase 3 Kafka integration can be built on a verified, race-condition-free foundation
```

---

## Acceptance Criteria

- [ ] **AC-01**: All 6 scenarios pass reliably when run 3 consecutive times without flakiness
- [ ] **AC-02**: Total test suite runs in under 60 seconds
- [ ] **AC-03**: Each test resets its booking data independently — no cross-test state leakage
- [ ] **AC-04 (Scenario 1)**: 10 simultaneous holds on the same slot produce exactly 1 success and 9 `SlotUnavailableException` results — never 0 or 2 successes
- [ ] **AC-05 (Scenario 4)**: Adjacent-slot buffer collision correctly blocks the overlapping candidate; a slot exactly at the buffer boundary succeeds
- [ ] **AC-06 (Scenario 6)**: No double-bookings occur under the hold-and-confirm race condition — exactly 1 confirmed booking exists after the race
- [ ] **AC-07**: GC deadlock check passes: Scenario 1 with `HoldGcScheduler` running concurrently produces no `DeadlockLoserDataAccessException`
- [ ] **AC-08 (Tenant isolation)**: All test setups create tenant-scoped fixtures — no shared tenant data between test scenarios
- [ ] **AC-09 (Domain abstraction)**: No industry-specific terms in any test method name, helper method, or fixture variable name

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

`BookingConcurrencyIT` is a single `@SpringBootTest(webEnvironment = RANDOM_PORT)` class. A static `@Container` PostgreSQL instance is shared across all tests via `withReuse(true)`. Test helper methods (`createTestTenant`, `createTestResource`, etc.) insert fixtures directly via service layer calls, not raw SQL, to ensure transactional integrity. Each test cleans up using `bookingRepository.deleteAll()` filtered to the test's `tenantId` in `@AfterEach`.

### File Structure

```
apps/api/src/test/java/com/scheduler/booking/
└── BookingConcurrencyIT.java            ← 6-scenario concurrency test class
```

### Interface Contracts

```java
// Test helper method signatures only
private UUID createTestTenant();
private UUID createTestLocation(UUID tenantId);
private UUID createTestResource(UUID tenantId, UUID locationId);
private UUID createTestServiceType(UUID tenantId, int durationMin, int bufferBeforeMin, int bufferAfterMin);
private Instant tomorrowAt(int hour);
```

### Design Rationale

- **Shared PostgreSQL container**: `withReuse(true)` reuses the container across all tests in the suite, reducing startup overhead from ~5s per test to ~0.1s. Isolation is achieved by per-test tenant fixtures, not by container restart.
- **`@Sql` cleanup**: Preferred over `@AfterEach` deleteAll for speed; SQL `DELETE WHERE tenant_id = ?` is faster than loading entities.
- **3× repeatability requirement**: Concurrency tests are inherently non-deterministic. Running 3 consecutive times confirms there are no timing-dependent false passes.
- **GC-in-background scenario**: Verifies that the GC's batch delete and `createHold`'s `SELECT FOR UPDATE` do not deadlock. PostgreSQL should handle this gracefully via lock timeout or row-level skip, but the test proves it.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL) — concurrent load

```
- scenario1_tenSimultaneousHolds_exactlyOneSucceeds:
    Given: 10 threads all call createHold(same resourceId, same slotStart)
    Assert: exactly 1 SUCCESS; 9 SlotUnavailableExceptions; DB has exactly 1 PENDING_HOLD

- scenario2_holdExpiry_slotBecomesAvailable:
    Given: create hold; set holdExpiresAt = Instant.now().minusSeconds(1)
    Assert: expireStaleHolds() deletes the row; createHold for same slot returns SUCCESS

- scenario3_confirmAfterHoldExpiry_throws409:
    Given: create hold; set holdExpiresAt = Instant.now().minusSeconds(1)
    Assert: confirmBooking throws HoldExpiredException (409 HOLD_EXPIRED)

- scenario4_adjacentSlotBufferCollision:
    Given: confirmed booking 09:00–10:00 with 15-min post-buffer (bufferEnd = 10:15)
    Assert: createHold at 10:00 → 409 SLOT_UNAVAILABLE (within buffer window)
    Assert: createHold at 10:15 → SUCCESS (just outside buffer)

- scenario5_cancelAndRebook_slotAvailable:
    Given: confirmed booking at slot X; cancel it
    Assert: createHold for slot X returns SUCCESS

- scenario6_holdAndConfirmRace_noDoubleBooking:
    Given: Thread A: createHold(slot X) → sleep 100ms → confirmBooking
    Given: Thread B (starts immediately after A holds): createHold(slot X)
    Assert: Thread B gets 409 SLOT_UNAVAILABLE; final confirmed booking count for slot X = 1
```

**Coverage requirements**:
- Each scenario must pass on 3 consecutive runs without any failure
- Scenario 1: `successes == 1` is the exact assertion — not `successes >= 1`
- GC deadlock check: run Scenario 1 with `HoldGcScheduler` triggered in background thread

---

## Implementation Constraints

- Use Testcontainers `PostgreSQLContainer` with `withReuse(true)` — no embedded H2
- `@SpringBootTest(webEnvironment = RANDOM_PORT)` — full application context
- Each test must create its own `tenantId` fixture — no shared tenants across scenarios
- Cleanup must run in `@AfterEach` — tests must not depend on execution order
- `ExecutorService` with `newFixedThreadPool(10)` for concurrent hold scenarios
- `pool.awaitTermination(30, TimeUnit.SECONDS)` timeout on concurrent tests
- `Future.get()` wrapped in try-catch — `ExecutionException` wrapping `SlotUnavailableException` must be handled
- No `System.out.println` — use SLF4J structured logging in helpers
- All test fixture methods must have generic domain names: `createTestResource`, not `createTestDoctor`

---

## Implementation Plan (TDD)

### RED — Write failing tests first

All 6 scenarios are written first as shell tests with `// TODO: implement` bodies. They fail because the underlying service code from atoms 09–12 has not yet been written.

### GREEN — Minimum code to pass

This atom is written after ATOM-BOOKING-009, ATOM-BOOKING-010, ATOM-BOOKING-011, and ATOM-BOOKING-012 are complete. All 6 scenarios are implemented and asserted to pass.

### REFACTOR — Quality pass

1. Extract shared fixture setup into `@BeforeEach` with per-test tenant IDs
2. Add `@Tag("concurrency")` to the class for selective CI execution
3. Verify 3× consecutive pass in CI before marking the atom complete
4. Document any non-obvious timing assumptions in test method Javadoc

---

## Implementation Reference

### BookingConcurrencyIT

**File**: `apps/api/src/test/java/com/scheduler/booking/BookingConcurrencyIT.java`

```java
// [TASK: ATOM-BOOKING-013]
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class BookingConcurrencyIT {

    @Container static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15").withReuse(true);

    // --- Scenario 1: 10 simultaneous holds on same slot ---
    @Test
    void scenario1_tenSimultaneousHolds_exactlyOneSucceeds() throws Exception {
        // spin up 10 threads, all call createHold() for same resourceId + slotStart
        // assert: exactly 1 success, 9 SlotUnavailableException
        // assert: DB has exactly 1 PENDING_HOLD row for that slot
    }

    // --- Scenario 2: Hold expiry and re-booking ---
    @Test
    void scenario2_holdExpiry_slotBecomesAvailable() {
        // create hold; force holdExpiresAt = Instant.now().minusSeconds(1)
        // run GC: holdGcScheduler.expireStaleHolds()
        // assert: row deleted
        // create new hold for same slot: assert success
    }

    // --- Scenario 3: Confirm after expiry ---
    @Test
    void scenario3_confirmAfterHoldExpiry_throws409() {
        // create hold; force expiry; attempt confirm
        // assert: HoldExpiredException (409)
    }

    // --- Scenario 4: Adjacent slot buffer collision ---
    @Test
    void scenario4_adjacentSlotBufferCollision() {
        // confirm booking 09:00–10:00 with 15-min post-buffer (bufferEnd = 10:15)
        // attempt hold at 10:00 → assert 409 (within buffer)
        // attempt hold at 10:15 → assert success (just outside buffer)
    }

    // --- Scenario 5: Cancel and re-book ---
    @Test
    void scenario5_cancelAndRebook_slotAvailable() {
        // confirm booking; cancel it
        // create new hold for same slot → assert success
    }

    // --- Scenario 6: Concurrent hold + confirm race ---
    @Test
    void scenario6_holdAndConfirmRace_noDoubleBooking() throws Exception {
        // Thread A: createHold → sleep 100ms → confirmBooking
        // Thread B (starts immediately after A holds): createHold same slot
        // assert: Thread B gets 409 SLOT_UNAVAILABLE
        // assert: final confirmed count = 1
    }
}
```

### Test Data Helpers

```java
// [TASK: ATOM-BOOKING-013]
private UUID createTestTenant()      { ... }
private UUID createTestLocation(UUID tenantId) { ... }
private UUID createTestResource(UUID tenantId, UUID locationId) { ... }
private UUID createTestServiceType(UUID tenantId, int duration, int bufBefore, int bufAfter) { ... }
private Instant tomorrowAt(int hour) {
    return LocalDate.now().plusDays(1).atTime(hour, 0).atZone(ZoneOffset.UTC).toInstant();
}
```

### Testcontainers Shared Instance Note

```java
// [TASK: ATOM-BOOKING-013]
// Use @Container static + withReuse(true) for fast test runs
// Each test uses @AfterEach to reset booking data, not restart containers
```

---

## Integration Points

**Depends on**: ATOM-BOOKING-009 (`createHold` with pessimistic lock), ATOM-BOOKING-010 (`confirmBooking`), ATOM-BOOKING-011 (`cancelBooking`), ATOM-BOOKING-012 (`HoldGcScheduler`)

**Enables**: Phase 3 start — all concurrency contracts verified; Kafka integration can be built on this foundation

**Cascading updates required**:
- `tasks/MASTER-TASK-LIST.md` — mark atom complete; Phase 2 complete gate passed

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/test/java/com/scheduler/booking/BookingConcurrencyIT.java` | New | 6-scenario concurrency test suite |
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
- [ ] All 6 concurrency scenarios pass on 3 consecutive runs
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: booking-engine | Phase: 2*
