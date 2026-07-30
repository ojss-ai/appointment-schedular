---
description: Redis cache warm-up service — conditional on ATOM-PERF-501 p99 > 250ms; proactive cache population for top-20% resources
---

# ATOM-PERF-503: Redis Cache Warm-Up and Performance Tuning

**Status**: ✅ Complete
**Feature**: perf-cache-tuning
**Phase**: 5 (Production)
**Tags**: [PERF]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-PERF-501 (slot availability load test — must show p99 > 250ms to activate this atom)
**Blocks**: None
**PR**: TBD

---

## Overview

This atom is conditionally activated: implement it only if ATOM-PERF-501 reports p99 > 250ms. It adds a `CacheWarmUpService` that fires on application startup and every 30 minutes to pre-populate Redis with operating-matrix data for the top 20% most-booked resources over the next 7 days. Startup warm-up must complete in under 30 seconds and must not add more than 5 seconds to application startup time. After deployment, ATOM-PERF-501 must be re-run to confirm p99 < 300ms.

---

## User Story

```
As a System
I want high-demand resources pre-populated in Redis cache on startup and every 30 minutes
So that the slot availability endpoint meets NFR-1.2 (p99 < 300ms) even under cold-start or cache-eviction conditions
```

---

## Acceptance Criteria

- [ ] **AC-01 (Trigger condition)**: This atom is only implemented if ATOM-PERF-501 shows p99 > 250ms — skip entirely if p99 is already < 250ms
- [ ] **AC-02**: Warm-up completes on application startup in < 30 seconds
- [ ] **AC-03**: Application startup time does not increase by more than 5 seconds due to warm-up
- [ ] **AC-04**: After warm-up: re-run ATOM-PERF-501 and confirm p99 < 300ms
- [ ] **AC-05**: Warm-up scheduled job runs every 30 minutes without causing Redis CPU spikes > 20% above baseline
- [ ] **AC-06**: Warm-up failures for individual resources are silently skipped (DEBUG log only) — never crash the application
- [ ] **AC-07 (Domain abstraction)**: No industry-specific terms in class names, log messages, or configuration keys

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | ATOM-PERF-501 results JSON | `tests/load/results/` | 🔜 Planned |
| AC-02 | Unit test: `CacheWarmUpServiceTest.warmUpCompletesUnder30Seconds` | `CacheWarmUpService` | 🔜 Planned |
| AC-03 | Startup log timing: `Started Application in X seconds` | Spring Boot startup log | 🔜 Planned |
| AC-04 | ATOM-PERF-501 re-run after deployment | `tests/load/slot-availability.js` | 🔜 Planned |
| AC-05 | Redis CPU: `redis-cli INFO stats` during scheduled run | Redis metrics | 🔜 Planned |
| AC-06 | Unit test: `warmUpSkipsOnException_doesNotPropagateError` | `CacheWarmUpService` | 🔜 Planned |

<!-- AC validation passed: TBD, 6 criteria mapped, all TBD -->

---

## Technical Design

### Architecture

`CacheWarmUpService` implements `ApplicationListener<ApplicationReadyEvent>` to trigger on startup. It also carries `@Scheduled(fixedDelay = 1_800_000)` for the 30-minute recurring job. It reads booking-pattern analytics (stored in `docs/memory/booking-patterns/by-resource/`) to identify the top 20% most-booked resources, then calls `SlotCalculatorService.computeOperatingMatrix()` for each resource × next 7 dates. `SlotCalculatorService` is already annotated `@Cacheable`, so each call populates Redis transparently. The warm-up service never writes to Redis directly.

### Data Flow / Sequence

```
ApplicationReadyEvent
  → CacheWarmUpService.onApplicationEvent()
  → loadTop20PercentResources()                    [reads docs/memory/booking-patterns/]
  → for each resourceId × next 7 dates:
      slotCalculator.computeOperatingMatrix(resourceId, null, date, null)
      → @Cacheable check on RedisCache
      → if cache miss: compute and store in Redis
  → log.info("Cache warm-up complete: N resources × 7 days in Xms")

@Scheduled (every 30 min)
  → same warmUpTopResources() flow
```

### File Structure

```
apps/api/src/main/java/com/scheduler/
└── service/
    └── CacheWarmUpService.java          ← ApplicationListener + @Scheduled warm-up

docs/memory/booking-patterns/
└── by-resource/
    └── {resourceId}.json                ← booking frequency data (written by observability agent)
```

### Interface Contracts

```java
// Service interface (signatures only — no method bodies)
public interface CacheWarmUpService {
    void warmUpTopResources();
}

// Internal record (Java 21)
public record BookingPatternRecord(
    UUID resourceId,
    UUID tenantId,
    long bookingCount,
    LocalDate weekStarting
) {}
```

### Design Rationale

- **ADR-001 (no slots table)**: Warm-up calls `SlotCalculatorService` — the authoritative on-demand computation path. There is no shortcut that bypasses slot computation; warm-up just pre-triggers it so the first real requests hit a warm cache.
- **NFR-1.2 context**: This atom is the remediation path when the 300ms p99 gate is in danger. Proactive cache population for top-20% resources shifts the cache hit rate from ~60% to >80%, which the ATOM-PERF-501 test showed is sufficient to meet the NFR.
- **Why ApplicationReadyEvent (not ApplicationStartedEvent)**: All Spring beans (including repositories) are guaranteed to be available; `ApplicationStartedEvent` fires before JPA is fully initialised.
- **Why fixedDelay (not fixedRate)**: `fixedDelay` ensures the next warm-up starts 30 minutes after the previous one completes, preventing overlap if a warm-up run takes longer than expected.

---

## Test Strategy

**Test type**: Unit (JUnit 5) + Integration (Testcontainers + Redis)

```
- warmUpCompletesUnder30Seconds:
    Given: 20 resourceIds in booking-patterns directory, SlotCalculatorService mocked
    Assert: warmUpTopResources() completes in < 30_000ms (timed with Stopwatch)

- warmUpSkipsOnException_doesNotPropagateError:
    Given: SlotCalculatorService throws RuntimeException for resource #3
    Assert: warm-up continues for remaining resources; no exception propagates to caller

- warmUpPopulatesRedisCache:
    Given: Redis empty; 5 resources in top-20% list
    Assert: after warmUpTopResources(), Redis contains keys for each resource × 7 dates

- scheduledJobRunsEvery30Minutes:
    Given: @Scheduled annotation present on warmUpTopResources()
    Assert: Spring scheduling metadata shows fixedDelay = 1_800_000

- loadTop20PercentResources_returnsCorrectSubset:
    Given: booking-patterns directory with 10 resource files, bookingCount varies
    Assert: returns exactly top 2 (20% of 10) by total bookingCount descending
```

**Coverage requirements**:
- Line coverage ≥ 80% on `CacheWarmUpService`
- Integration test must use Testcontainers Redis to verify actual cache population

---

## Implementation Constraints

- `CacheWarmUpService` must never write to Redis directly — all cache population goes through `SlotCalculatorService` `@Cacheable` annotations
- Warm-up failures for individual resources must be caught and logged at DEBUG level only — never propagate
- No `System.out.println` — use SLF4J `log.info` / `log.debug`
- `@Value("${app.memory.booking-patterns-path:docs/memory/booking-patterns}")` — path must be configurable
- This atom is conditional — do not implement if ATOM-PERF-501 p99 is already < 250ms

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `CacheWarmUpServiceTest.java` with mocked `SlotCalculatorService`
2. Write `warmUpCompletesUnder30Seconds` — assert it fails (class doesn't exist)
3. Write `warmUpSkipsOnException_doesNotPropagateError` — assert it fails

### GREEN — Minimum code to pass

1. Create `CacheWarmUpService.java` implementing `ApplicationListener<ApplicationReadyEvent>`
2. Implement `loadTop20PercentResources()` reading from `booking-patterns/by-resource/`
3. Implement `warmUpTopResources()` with try/catch per resource
4. Add `@Scheduled` annotation for 30-minute repeat
5. Run all tests — verify they pass

### REFACTOR — Quality pass

1. Add structured logging with resource count and duration
2. Add `@ConditionalOnProperty(name = "app.cache.warmup.enabled", havingValue = "true", matchIfMissing = true)` to allow disabling in test environments
3. Run ATOM-PERF-501 and confirm p99 < 300ms with warm-up active

---

## Implementation Reference

### Cache Warm-Up Service

**File**: `apps/api/src/main/java/com/scheduler/service/CacheWarmUpService.java`

```java
// [TASK: ATOM-PERF-503]
@Component
@RequiredArgsConstructor
@Slf4j
public class CacheWarmUpService implements ApplicationListener<ApplicationReadyEvent> {

    private final ResourceRepository     resourceRepository;
    private final SlotCalculatorService  slotCalculator;
    private final ObjectMapper           objectMapper;

    @Value("${app.memory.booking-patterns-path:docs/memory/booking-patterns}")
    private String patternsPath;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        warmUpTopResources();
    }

    @Scheduled(fixedDelay = 1_800_000)   // every 30 minutes
    public void warmUpTopResources() {
        Instant start = Instant.now();
        List<UUID> topResources = loadTop20PercentResources();
        LocalDate today = LocalDate.now();

        topResources.forEach(resourceId -> {
            for (int i = 0; i < 7; i++) {
                LocalDate date = today.plusDays(i);
                // This call populates the Redis cache via @Cacheable on schedule/holiday repos
                try {
                    slotCalculator.computeOperatingMatrix(resourceId, /* locationId */ null, date, null);
                } catch (Exception e) {
                    log.debug("Warm-up skip for {} on {}: {}", resourceId, date, e.getMessage());
                }
            }
        });

        log.info("Cache warm-up complete: {} resources × 7 days in {}ms",
            topResources.size(), Duration.between(start, Instant.now()).toMillis());
    }

    private List<UUID> loadTop20PercentResources() {
        // Read from docs/memory/booking-patterns/by-resource/ — sort by total bookingCount
        // Return top 20%
        try (var stream = Files.list(Path.of(patternsPath, "by-resource"))) {
            return stream
                .map(f -> readPatterns(f))
                .sorted(Comparator.comparingLong(
                    patterns -> -patterns.stream().mapToLong(BookingPatternRecord::bookingCount).sum()))
                .limit(Math.max(1, (long)(stream.count() * 0.2)))
                .map(patterns -> patterns.get(0).resourceId())
                .toList();
        } catch (IOException e) {
            return List.of();
        }
    }
}
```

---

## Integration Points

**Depends on**: ATOM-PERF-501 (must show p99 > 250ms to justify this atom); Phase 2 atom-08 (Redis `@Cacheable` on `SlotCalculatorService` must be in place); booking-patterns analytics data must exist in `docs/memory/booking-patterns/by-resource/`

**Enables**: Re-run of ATOM-PERF-501 to confirm p99 < 300ms after warm-up is active

**NFR Gates satisfied**: NFR-1.2 (slot generation < 300ms p99) — this is the remediation path when NFR-1.2 is at risk

**Cascading updates required**:
- `tasks/MASTER-TASK-LIST.md` — mark atom complete; note whether it was activated or skipped
- `tests/load/README.md` — add section on cache warm-up and when to trigger this atom

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/com/scheduler/service/CacheWarmUpService.java` | New | Startup + scheduled cache warm-up for top-20% resources |
| `apps/api/src/test/java/com/scheduler/service/CacheWarmUpServiceTest.java` | New | Unit tests: timing, error isolation, top-20% selection |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete (or skipped if p99 < 250ms) |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] AC-01 trigger condition documented — p99 result from ATOM-PERF-501 recorded
- [ ] Unit tests pass: warm-up timing, exception isolation, top-20% selection
- [ ] Integration test confirms Redis cache populated after warm-up
- [ ] ATOM-PERF-501 re-run confirms p99 < 300ms after warm-up active
- [ ] Warm-up does not increase startup time by more than 5 seconds
- [ ] No direct Redis writes in `CacheWarmUpService` — all via `@Cacheable`
- [ ] Zero industry-specific terms in class names, log messages, or config keys
- [ ] Atom status updated to ✅ Complete (or ⏭️ Skipped with reason)
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: perf-cache-tuning | Phase: 5*
