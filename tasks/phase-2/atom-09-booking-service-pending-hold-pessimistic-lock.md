# ATOM-BOOKING-009: BookingService — PENDING_HOLD with Pessimistic Lock

**Status**: 🟡 Planned
**Feature**: booking-engine
**Phase**: 2 (Core)
**Tags**: [CONCURRENCY]
**Complexity**: High
**Agent**: coder + testgen (always paired for concurrency tasks)
**Dependencies**: ATOM-SLOT-006, ATOM-TENANT-004 (P1 bookings table)
**Blocks**: ATOM-BOOKING-010, ATOM-BOOKING-012, ATOM-BOOKING-013
**PR**: TBD

---

## Overview

Implements hold creation in `BookingService` with full concurrency protection using pessimistic locking (ADR-002). When a user selects a slot, a `PENDING_HOLD` booking is created that reserves the slot for 10 minutes during checkout. The pessimistic lock (`SELECT ... FOR UPDATE`) prevents concurrent requests from creating conflicting holds on the same resource at overlapping times. The concurrent 10-thread integration test is part of this atom — not deferred to a follow-up.

---

## User Story

```
As a Booking User
I want to place a 10-minute hold on a slot
So that my chosen slot is reserved while I complete the checkout process
```

---

## Acceptance Criteria

- [ ] **AC-01**: `createHold` creates a `PENDING_HOLD` booking with `holdExpiresAt = now() + 600 seconds` (±2s tolerance)
- [ ] **AC-02**: `bufferStart` and `bufferEnd` on the booking are correctly computed from `slotStart`, `durationMinutes`, `bufferBeforeMin`, and `bufferAfterMin`
- [ ] **AC-03**: Attempting to hold a slot already held by another booking returns `409 SLOT_UNAVAILABLE`
- [ ] **AC-04**: Tenant guard prevents booking a resource that belongs to a different tenant — returns `404 RESOURCE_NOT_FOUND`
- [ ] **AC-05**: 10-thread concurrent test: exactly 1 thread succeeds, 9 threads receive `409 SLOT_UNAVAILABLE`; no deadlocks occur; DB contains exactly 1 `PENDING_HOLD` for the target slot
- [ ] **AC-06**: Requesting a slot outside the operating matrix (not in the window computed by `SlotCalculatorService`) returns `422 SLOT_OUTSIDE_OPERATING_HOURS`
- [ ] **AC-07 (Tenant isolation)**: Pessimistic lock query includes `tenantId` — no cross-tenant conflict rows are fetched
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms in any class name, field name, or API path in this package

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

`BookingController` → `BookingService.createHold()` → `BookingRepository` (pessimistic lock query) → PostgreSQL `bookings` table. The lock is acquired via a `@Lock(LockModeType.PESSIMISTIC_WRITE)` JPQL query that selects all conflicting bookings within the candidate's buffer window for update. If any conflicts exist, the hold is rejected. If none, the new `PENDING_HOLD` booking is inserted within the same transaction before the lock is released.

### Data Flow / Sequence

```
POST /api/v1/tenants/{tenantId}/bookings/hold
  → @PreAuthorize tenantGuard.check()
  → BookingService.createHold()
      → ResourceRepository.findByIdAndTenantId()         [validate ownership]
      → ServiceTypeRepository.findByIdAndTenantId()      [load duration + buffers]
      → compute slotEnd, bufferStart, bufferEnd
      → BookingRepository.findConflictingBookingsForUpdate()  [SELECT FOR UPDATE]
      → if conflicts → throw SlotUnavailableException(409)
      → SlotCalculatorService.computeAvailableSlots()    [verify in operating matrix]
      → if not in matrix → throw SlotOutsideOperatingHoursException(422)
      → bookingRepository.save(PENDING_HOLD booking)
      → DB commit → lock released
  → return HoldResponse(bookingId, holdExpiresAt)
```

### File Structure

```
apps/api/src/main/java/com/scheduler/
├── domain/entity/
│   └── Booking.java                     ← JPA entity
├── repository/
│   └── BookingRepository.java           ← pessimistic lock query
├── service/
│   └── BookingService.java              ← createHold()
├── controller/
│   └── BookingController.java
└── dto/
    ├── CreateHoldRequest.java
    └── HoldResponse.java

apps/api/src/test/java/com/scheduler/booking/
└── BookingServiceConcurrencyIT.java     ← 10-thread concurrent test
```

### Interface Contracts

```java
// DTOs — Java 21 records
public record CreateHoldRequest(
    @NotNull UUID resourceId,
    @NotNull UUID serviceTypeId,
    @NotNull Instant slotStart
) {}

public record HoldResponse(UUID bookingId, Instant holdExpiresAt) {}

// Repository interface — pessimistic lock query
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT b FROM Booking b
        WHERE b.resourceId = :resourceId
          AND b.tenantId   = :tenantId
          AND b.status IN :statuses
          AND b.bufferStart < :bufferEnd
          AND b.bufferEnd   > :bufferStart
        """)
    List<Booking> findConflictingBookingsForUpdate(
        @Param("resourceId")  UUID resourceId,
        @Param("tenantId")    UUID tenantId,
        @Param("statuses")    List<String> statuses,
        @Param("bufferStart") Instant bufferStart,
        @Param("bufferEnd")   Instant bufferEnd
    );

    long countByResourceIdAndStatus(UUID resourceId, String status);
    Optional<Booking> findByIdAndTenantId(UUID id, UUID tenantId);
}

// Service method signature
public class BookingService {
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public HoldResponse createHold(CreateHoldRequest req, UUID tenantId);
}
```

### Design Rationale

- **ADR-002**: Pessimistic lock (`SELECT ... FOR UPDATE`) is the primary concurrency guard. It serializes concurrent `createHold` calls for the same resource slot. Redis distributed lock is not used here — it is reserved for horizontal scale when a single DB lock is insufficient.
- **`SERIALIZABLE` isolation**: Provides the strongest guarantee against phantom reads during the conflict check and insert within the same transaction window.
- **Buffer stored on booking**: `bufferStart` and `bufferEnd` are pre-computed and stored so that the conflict query does not need to join the service type table — the query remains index-friendly.
- **Operating matrix check after lock**: The slot is validated against the current operating matrix after the lock is acquired to prevent holds on out-of-hours times, but before the insert, within the same transaction.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL) — concurrent load

```
- shouldCreateHold_withCorrectExpiry:
    Given: valid resource + service type + future slot in operating hours
    Assert: returned HoldResponse.holdExpiresAt is within 2 seconds of now() + 600s; DB row status = PENDING_HOLD

- shouldRejectConflictingHold_returns409:
    Given: PENDING_HOLD already exists for resource at 10:00–11:00 (bufferStart/End computed)
    Assert: second createHold for same slot returns 409 SLOT_UNAVAILABLE

- shouldEnforceTenantIsolation_returns404:
    Given: resource belongs to tenantB
    Assert: createHold with tenantA JWT returns 404 RESOURCE_NOT_FOUND

- tenThreadsHoldSameSlot_exactlyOneSucceeds:
    Given: 10 concurrent threads all call createHold for the same resourceId + slotStart
    Assert: exactly 1 SUCCESS; 9 SlotUnavailableExceptions; 0 deadlocks; 1 PENDING_HOLD in DB

- shouldRejectSlotOutsideOperatingHours_returns422:
    Given: slotStart is outside resource schedule window
    Assert: response is 422 SLOT_OUTSIDE_OPERATING_HOURS
```

**Coverage requirements**:
- Line coverage ≥ 80% on `BookingService.createHold`
- Concurrency test must simulate ≥ 10 simultaneous requests (tagged `[CONCURRENCY]`)
- Deadlock detection: test must assert zero exceptions of type `DeadlockLoserDataAccessException`

---

## Implementation Constraints

- Every JPA query must include `tenant_id` in the WHERE clause
- DTOs must be Java 21 records (never classes)
- `createHold` must be `@Transactional(isolation = Isolation.SERIALIZABLE)`
- All controller endpoints must carry `@PreAuthorize("@tenantGuard.check(#tenantId)")`
- Booking ID must be set explicitly to `UUID.randomUUID()` before insert (not `@GeneratedValue`)
- `holdExpiresAt` = `Instant.now().plusSeconds(600)`
- `PENDING_HOLD` and `CONFIRMED` statuses both block new holds (passed as the `statuses` list)
- Slot must be verified in the operating matrix within the same transaction as the lock acquisition
- No direct Kafka writes — outbox write deferred to Phase 3 (noted in code comment)
- No `System.out.println` — use SLF4J structured logging

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/com/scheduler/booking/BookingServiceConcurrencyIT.java` with Testcontainers
2. Write `shouldCreateHold_withCorrectExpiry` — assert it fails (entity does not exist)
3. Write `tenThreadsHoldSameSlot_exactlyOneSucceeds` — assert it fails

### GREEN — Minimum code to pass

1. Confirm `bookings` table migration exists (Phase 1 output); add `buffer_start`, `buffer_end`, `hold_expires_at` columns if missing
2. Implement `Booking.java` JPA entity
3. Implement `BookingRepository.java` with `findConflictingBookingsForUpdate` pessimistic lock query
4. Implement `BookingService.createHold()` — conflict check, matrix validation, insert
5. Implement `BookingController.java` with `@PreAuthorize`

### REFACTOR — Quality pass

1. Add structured logging: `log.info("Hold created: bookingId={}, tenantId={}, slot={}", ...)`
2. Add Javadoc to `createHold` documenting the lock sequence
3. Verify `findConflictingBookingsForUpdate` query plan uses the `(tenant_id, resource_id, buffer_start, buffer_end)` index (NFR-1.3)
4. Run `/security-scan` on the new controller

---

## Implementation Reference

### Booking Entity

**File**: `apps/api/src/main/java/com/scheduler/domain/entity/Booking.java`

```java
// [TASK: ATOM-BOOKING-009]
@Entity @Table(name = "bookings")
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class Booking {
    @Id private UUID id;          // set explicitly to UUID.randomUUID() before insert
    @Column(nullable=false) private UUID   tenantId;
    @Column(nullable=false) private UUID   resourceId;
    @Column(nullable=false) private UUID   serviceTypeId;
    @Column(nullable=false) private UUID   locationId;
    @Column(nullable=false) private UUID   userId;
    @Column(nullable=false) private String status;      // PENDING_HOLD, CONFIRMED, CANCELLED
    @Column(nullable=false) private Instant slotStart;
    @Column(nullable=false) private Instant slotEnd;
    @Column(nullable=false) private Instant bufferStart;
    @Column(nullable=false) private Instant bufferEnd;
    private Instant holdExpiresAt;
    private Instant cancelledAt;
    private UUID    cancelledBy;
    private String  cancellationReason;
    @Type(JsonBinaryType.class)
    @Column(columnDefinition="jsonb")
    private Map<String,Object> extension;
    private Instant createdAt;
    private Instant updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = Instant.now(); }
    @PreUpdate  void onUpdate() { updatedAt = Instant.now(); }
}
```

### BookingService.createHold

**File**: `apps/api/src/main/java/com/scheduler/service/BookingService.java`

```java
// [TASK: ATOM-BOOKING-009]
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository     bookingRepository;
    private final ResourceRepository    resourceRepository;
    private final ServiceTypeRepository serviceTypeRepository;
    private final SlotCalculatorService slotCalculator;

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public HoldResponse createHold(CreateHoldRequest req, UUID tenantId) {

        // 1. Validate resource and service belong to tenant
        Resource resource = resourceRepository.findByIdAndTenantId(req.resourceId(), tenantId)
            .orElseThrow(() -> new ResourceNotFoundException(req.resourceId()));
        ServiceType service = serviceTypeRepository.findByIdAndTenantId(req.serviceTypeId(), tenantId)
            .orElseThrow(() -> new ServiceTypeNotFoundException(req.serviceTypeId()));

        // 2. Compute slot timing
        Instant slotStart   = req.slotStart();
        Instant slotEnd     = slotStart.plus(service.getDurationMinutes(), ChronoUnit.MINUTES);
        Instant bufferStart = slotStart.minus(service.getBufferBeforeMin(), ChronoUnit.MINUTES);
        Instant bufferEnd   = slotEnd.plus(service.getBufferAfterMin(), ChronoUnit.MINUTES);

        // 3. Pessimistic lock: check and lock any overlapping bookings
        List<Booking> conflicts = bookingRepository
            .findConflictingBookingsForUpdate(
                req.resourceId(), tenantId,
                List.of("PENDING_HOLD", "CONFIRMED"),
                bufferStart, bufferEnd);

        if (!conflicts.isEmpty()) {
            throw new SlotUnavailableException(req.resourceId(), slotStart);
        }

        // 4. Verify slot is within operating matrix
        LocalDate date = slotStart.atZone(ZoneOffset.UTC).toLocalDate();
        List<AvailableSlot> available = slotCalculator.computeAvailableSlots(
            req.resourceId(), req.serviceTypeId(), resource.getLocationId(), date, tenantId);
        boolean inMatrix = available.stream()
            .anyMatch(s -> s.startTime().equals(slotStart));
        if (!inMatrix) {
            throw new SlotOutsideOperatingHoursException(slotStart);
        }

        // 5. Create PENDING_HOLD booking
        UUID bookingId = UUID.randomUUID();
        Booking booking = Booking.builder()
            .id(bookingId)
            .tenantId(tenantId)
            .resourceId(req.resourceId())
            .serviceTypeId(req.serviceTypeId())
            .locationId(resource.getLocationId())
            .userId(TenantContext.getUserId())
            .status("PENDING_HOLD")
            .slotStart(slotStart)
            .slotEnd(slotEnd)
            .bufferStart(bufferStart)
            .bufferEnd(bufferEnd)
            .holdExpiresAt(Instant.now().plusSeconds(600))   // 10 minutes
            .build();
        bookingRepository.save(booking);

        return new HoldResponse(bookingId, booking.getHoldExpiresAt());
    }
}
```

### Pessimistic Lock Query

**File**: `apps/api/src/main/java/com/scheduler/repository/BookingRepository.java`

```java
// [TASK: ATOM-BOOKING-009]
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT b FROM Booking b
        WHERE b.resourceId = :resourceId
          AND b.tenantId   = :tenantId
          AND b.status IN :statuses
          AND b.bufferStart < :bufferEnd
          AND b.bufferEnd   > :bufferStart
        """)
    List<Booking> findConflictingBookingsForUpdate(
        @Param("resourceId")  UUID resourceId,
        @Param("tenantId")    UUID tenantId,
        @Param("statuses")    List<String> statuses,
        @Param("bufferStart") Instant bufferStart,
        @Param("bufferEnd")   Instant bufferEnd
    );
}
```

### Concurrent Test (10-thread)

**File**: `apps/api/src/test/java/com/scheduler/booking/BookingServiceConcurrencyIT.java`

```java
// [TASK: ATOM-BOOKING-009]
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class BookingServiceConcurrencyIT {

    @Container static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:15").withReuse(true);

    @Test
    void tenThreadsHoldSameSlot_exactlyOneSucceeds() throws Exception {
        UUID tenantId      = createTestTenant();
        UUID resourceId    = createTestResource(tenantId);
        UUID serviceTypeId = createTestServiceType(tenantId, 60, 0, 0);
        Instant targetSlot = LocalDate.now().plusDays(1)
            .atTime(9, 0).atZone(ZoneOffset.UTC).toInstant();

        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            futures.add(pool.submit(() -> {
                try {
                    bookingService.createHold(
                        new CreateHoldRequest(resourceId, serviceTypeId, targetSlot),
                        tenantId);
                    return "SUCCESS";
                } catch (SlotUnavailableException e) {
                    return "CONFLICT";
                }
            }));
        }

        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);

        long successes = futures.stream().map(f -> {
            try { return f.get(); } catch (Exception e) { return "ERROR"; }
        }).filter("SUCCESS"::equals).count();

        assertThat(successes).isEqualTo(1);

        long holdCount = bookingRepository.countByResourceIdAndStatus(resourceId, "PENDING_HOLD");
        assertThat(holdCount).isEqualTo(1);
    }
}
```

---

## Integration Points

**Depends on**: ATOM-SLOT-006 (`computeAvailableSlots` for matrix validation), ATOM-SERVICE-003 (service type with duration/buffer), ATOM-RESOURCE-002 (resource entity), Phase 1 bookings table migration

**Enables**: ATOM-BOOKING-010 (`confirmBooking` transitions from `PENDING_HOLD`), ATOM-BOOKING-012 (`HoldGcScheduler` cleans up expired holds), ATOM-BOOKING-013 (concurrency integration test suite)

**Cascading updates required**:
- `docs/API-SPEC.md` — add booking hold endpoint
- `docs/ADR/ADR-002-concurrency-locking-model.md` — reference implementation complete
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/com/scheduler/domain/entity/Booking.java` | New | JPA entity |
| `apps/api/src/main/java/com/scheduler/repository/BookingRepository.java` | New | Pessimistic lock query |
| `apps/api/src/main/java/com/scheduler/service/BookingService.java` | New | createHold() |
| `apps/api/src/main/java/com/scheduler/controller/BookingController.java` | New | REST endpoints |
| `apps/api/src/main/java/com/scheduler/dto/CreateHoldRequest.java` | New | Hold request DTO |
| `apps/api/src/main/java/com/scheduler/dto/HoldResponse.java` | New | Hold response DTO |
| `apps/api/src/test/java/com/scheduler/booking/BookingServiceConcurrencyIT.java` | New | 10-thread concurrency test |
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
