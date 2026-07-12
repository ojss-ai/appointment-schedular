# ATOM-BOOKING-012: HoldGcScheduler — Expired Hold Cleanup

**Status**: 🟡 Planned
**Feature**: booking-engine
**Phase**: 2 (Core)
**Tags**: [CONCURRENCY]
**Complexity**: Low
**Agent**: coder
**Dependencies**: ATOM-BOOKING-009
**Blocks**: ATOM-BOOKING-013
**PR**: TBD

---

## Overview

A background scheduler that runs every 60 seconds and deletes `PENDING_HOLD` bookings whose `holdExpiresAt` has passed. Expired holds free their slots immediately upon deletion — no status update to `EXPIRED` is performed, keeping the slot query logic simple. Deletion is batched at 500 rows per run to prevent long-held transactions and lock contention under high hold volume.

---

## User Story

```
As a System
I want expired pending holds to be cleaned up automatically
So that slots reserved but never confirmed are freed within 61 seconds of expiry
```

---

## Acceptance Criteria

- [ ] **AC-01**: `HoldGcScheduler.expireStaleHolds()` runs every 60 seconds (verifiable in logs)
- [ ] **AC-02**: A `PENDING_HOLD` booking with `holdExpiresAt < now()` is deleted within 61 seconds of expiry
- [ ] **AC-03**: After GC deletes an expired hold, a new `createHold` for the same slot succeeds without `409 SLOT_UNAVAILABLE`
- [ ] **AC-04**: The GC does not cause deadlocks under concurrent booking load (verified in ATOM-BOOKING-013)
- [ ] **AC-05**: Batch size of 500 limits the number of rows deleted per scheduler execution, preventing long-running transactions
- [ ] **AC-06**: `@EnableScheduling` is active on the main application class or `CacheConfig`
- [ ] **AC-07 (Tenant isolation)**: The delete query does not filter by `tenant_id` — expired holds from all tenants are cleaned by the same GC job (system-level operation, not tenant-scoped)
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms in any class name, field name, or log message in this component

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | TBD | TBD | 🔜 Planned |
| AC-02 | TBD | TBD | 🔜 Planned |
| AC-03 | TBD | TBD | 🔜 Planned |
| AC-04 | TBD (ATOM-BOOKING-013) | TBD | 🔜 Planned |
| AC-05 | TBD | TBD | 🔜 Planned |
| AC-06 | TBD | TBD | 🔜 Planned |
| AC-07 | TBD | TBD | 🔜 Planned |
| AC-08 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 8 criteria rewritten, 8 marked TBD -->

---

## Technical Design

### Architecture

`HoldGcScheduler` is a `@Component` with a single `@Scheduled(fixedDelay = 60_000)` method. It calls one `@Modifying` native SQL query on `BookingRepository` — a batched `DELETE FROM bookings WHERE status = 'PENDING_HOLD' AND hold_expires_at < :now LIMIT :batchSize`. The native query is used because JPQL does not support `LIMIT` in `DELETE` statements.

### Data Flow / Sequence

```
[Scheduler tick — every 60s]
  → HoldGcScheduler.expireStaleHolds()
      → BookingRepository.deleteExpiredHolds(now(), BATCH_SIZE=500)
          → DELETE FROM bookings WHERE status='PENDING_HOLD'
            AND hold_expires_at < :now LIMIT 500
      → if deleted > 0: log.info("HoldGcScheduler: expired {} stale PENDING_HOLD bookings", deleted)
```

### File Structure

```
apps/api/src/main/java/com/scheduler/
├── scheduler/
│   └── HoldGcScheduler.java             ← @Component with @Scheduled

apps/api/src/main/resources/
└── application.yml                      ← spring.task.scheduling.pool.size: 2

apps/api/src/test/java/com/scheduler/booking/
└── HoldGcSchedulerIT.java               ← direct invocation test
```

### Interface Contracts

```java
// Scheduler component — method signatures only
@Component
public class HoldGcScheduler {
    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireStaleHolds();
}

// Repository query added to BookingRepository
public interface BookingRepository extends JpaRepository<Booking, UUID> {
    @Modifying
    @Query(value = """
        DELETE FROM bookings
        WHERE id IN (
            SELECT id FROM bookings
            WHERE status = 'PENDING_HOLD'
              AND hold_expires_at < :now
            LIMIT :batchSize
        )
        """, nativeQuery = true)
    int deleteExpiredHolds(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
```

### Design Rationale

- **Delete over status=EXPIRED**: Physical deletion removes the row from the B-tree index immediately, freeing the slot without an additional filter in the conflict query. If an audit trail of abandoned holds is required, a feature flag can toggle to `UPDATE status = 'EXPIRED'` behavior.
- **Batch size of 500**: Limits the transaction duration and lock footprint. If more than 500 holds expire simultaneously (e.g., after an outage), they are cleaned up across multiple 60-second cycles.
- **No `SELECT FOR UPDATE` in GC**: The GC targets rows with `status = 'PENDING_HOLD' AND hold_expires_at < now()`. By the time a hold expires, no `confirmBooking` call can legitimately lock it (the `holdExpiresAt` guard in `confirmBooking` would have already rejected it). No row-level coordination with `createHold` is needed.
- **`@EnableScheduling` placement**: Added to the main application class to activate all `@Scheduled` beans globally.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL)

```
- shouldDeleteExpiredHold_afterGcRun:
    Given: PENDING_HOLD booking with holdExpiresAt = Instant.now().minusSeconds(1)
    Assert: holdGcScheduler.expireStaleHolds() deletes the row; bookingRepository.count() decreases by 1

- shouldLeaveNonExpiredHold_intact:
    Given: PENDING_HOLD booking with holdExpiresAt = Instant.now().plusSeconds(300)
    Assert: holdGcScheduler.expireStaleHolds() does not delete it

- shouldFreeSlot_afterExpiredHoldDeleted:
    Given: expired hold deleted by GC for slot X
    Assert: createHold for slot X returns success (no 409)

- shouldDeleteInBatches_when500PlusExpiredHoldsExist:
    Given: 600 expired PENDING_HOLD bookings
    Assert: first expireStaleHolds() call deletes exactly 500; second call deletes the remaining 100

- shouldNotDeleteConfirmedOrCancelledBookings:
    Given: mix of PENDING_HOLD (expired), CONFIRMED, CANCELLED rows
    Assert: only the expired PENDING_HOLD rows are deleted; others remain
```

**Coverage requirements**:
- Line coverage ≥ 80% on `HoldGcScheduler`
- Batch size boundary condition must be tested (> 500 expired holds)

---

## Implementation Constraints

- GC job deletes rows — no `status = EXPIRED` update (keep state machine clean)
- Batch size hardcoded at `500` as a constant (`BATCH_SIZE = 500`)
- `@Scheduled(fixedDelay = 60_000)` — fixed delay, not fixed rate (avoids overlap if a run takes > 60s)
- `@Transactional` on `expireStaleHolds` so the delete is atomic
- Native SQL query required for `LIMIT` in DELETE — JPQL does not support `LIMIT` in bulk operations
- Log only if `deleted > 0` to avoid noise in quiet periods
- `@EnableScheduling` on main application class
- `spring.task.scheduling.pool.size: 2` in `application.yml`
- No `System.out.println` — use SLF4J structured logging

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/com/scheduler/booking/HoldGcSchedulerIT.java`
2. Write `shouldDeleteExpiredHold_afterGcRun` — assert it fails (scheduler does not exist)
3. Write `shouldNotDeleteConfirmedOrCancelledBookings` — assert it fails

### GREEN — Minimum code to pass

1. Add `deleteExpiredHolds` native query to `BookingRepository`
2. Implement `HoldGcScheduler.java` with `@Scheduled(fixedDelay = 60_000)` method
3. Add `@EnableScheduling` to main application class
4. Add `spring.task.scheduling.pool.size: 2` to `application.yml`
5. Pass all test scenarios

### REFACTOR — Quality pass

1. Add log statement: `log.info("HoldGcScheduler: expired {} stale PENDING_HOLD bookings", deleted)`
2. Add Javadoc to `expireStaleHolds` documenting the batch behavior
3. Verify the native query works correctly against PostgreSQL 15 (Testcontainers)
4. Document feature-flag pattern for future `EXPIRED` status behavior in a code comment

---

## Implementation Reference

### HoldGcScheduler

**File**: `apps/api/src/main/java/com/scheduler/scheduler/HoldGcScheduler.java`

```java
// [TASK: ATOM-BOOKING-012]
@Component
@RequiredArgsConstructor
@Slf4j
public class HoldGcScheduler {

    private final BookingRepository bookingRepository;

    private static final int BATCH_SIZE = 500;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireStaleHolds() {
        int deleted = bookingRepository.deleteExpiredHolds(Instant.now(), BATCH_SIZE);
        if (deleted > 0) {
            log.info("HoldGcScheduler: expired {} stale PENDING_HOLD bookings", deleted);
        }
    }
}
```

### Repository Query

**File**: `apps/api/src/main/java/com/scheduler/repository/BookingRepository.java` (addition)

```java
// [TASK: ATOM-BOOKING-012]
@Modifying
@Query(value = """
    DELETE FROM bookings
    WHERE id IN (
        SELECT id FROM bookings
        WHERE status = 'PENDING_HOLD'
          AND hold_expires_at < :now
        LIMIT :batchSize
    )
    """, nativeQuery = true)
int deleteExpiredHolds(@Param("now") Instant now, @Param("batchSize") int batchSize);
```

### application.yml Addition

**File**: `apps/api/src/main/resources/application.yml`

```yaml
# [TASK: ATOM-BOOKING-012]
spring:
  task:
    scheduling:
      pool:
        size: 2
```

### Design Notes

```java
// [TASK: ATOM-BOOKING-012]
// Design rationale:
// - Delete vs. status=EXPIRED: Delete is preferred — it removes rows from the index immediately,
//   freeing the slot without additional query complexity.
//   If an audit trail of abandoned holds is needed, add status=EXPIRED behaviour behind a feature flag.
// - Batch size: max 500 rows per run to avoid long-held transactions and lock contention.
// - No SELECT FOR UPDATE: The GC deletes rows others might be trying to confirm —
//   using WHERE status = 'PENDING_HOLD' AND hold_expires_at < now() naturally skips rows
//   that have already been confirmed.
```

---

## Integration Points

**Depends on**: ATOM-BOOKING-009 (Booking entity with `holdExpiresAt` field, `PENDING_HOLD` status)

**Enables**: ATOM-BOOKING-013 (GC deadlock check: run Scenario 1 with GC running in background)

**Cascading updates required**:
- `docs/ARCHITECTURE.md` — document GC scheduler behavior and batch size
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/com/scheduler/scheduler/HoldGcScheduler.java` | New | Expired hold cleanup scheduler |
| `apps/api/src/main/java/com/scheduler/repository/BookingRepository.java` | Modified | Add deleteExpiredHolds native query |
| `apps/api/src/main/resources/application.yml` | Modified | Add scheduling pool config |
| `apps/api/src/main/java/com/scheduler/Application.java` | Modified | Add @EnableScheduling |
| `apps/api/src/test/java/com/scheduler/booking/HoldGcSchedulerIT.java` | New | GC integration tests |
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
