# ATOM-SLOT-008: Redis Caching for Schedule and Holidays

**Status**: 🟡 Planned
**Feature**: slot-calculator
**Phase**: 2 (Core)
**Tags**: [SLOT]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-SLOT-007
**Blocks**: None
**PR**: TBD

---

## Overview

Adds Redis caching to the static inputs of slot calculation — resource schedules, resource breaks, and branch holidays — to meet the NFR-1.2 p99 < 300ms gate. Booking data is deliberately excluded from caching and is always fetched fresh, because stale booking data would cause double-bookings. Cache eviction is tied to schedule and holiday mutation operations. A `CacheErrorHandler` ensures Redis unavailability degrades gracefully to direct DB queries rather than causing 500 errors.

---

## User Story

```
As a System
I want resource schedules and holiday data cached in Redis
So that repeated slot availability queries are served within 300ms p99 without hitting the database
```

---

## Acceptance Criteria

- [ ] **AC-01**: Cache hit rate > 80% for repeated slot queries on the same resource and day (verified with Redis `INFO stats`)
- [ ] **AC-02**: Cache eviction fires immediately when a resource schedule is replaced — subsequent queries re-fetch from DB and produce the updated schedule
- [ ] **AC-03**: Slot results are correct after a cache eviction (DB re-fetch returns the new schedule)
- [ ] **AC-04**: Redis unavailability does NOT cause `500` errors — the `CacheErrorHandler` falls through to DB queries transparently
- [ ] **AC-05**: Booking data is never served from cache — `bookingRepository.findByResourceIdAndStatusInAndSlotStartBetween` has no `@Cacheable` annotation
- [ ] **AC-06**: Integration test: cache miss → DB hit → cache warm → cache hit (verified via cache statistics or spy)
- [ ] **AC-07 (Tenant isolation)**: Cache keys include both `resourceId` and `tenantId` (or equivalent scoping) — no cross-tenant cache bleed
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms in any class name, field name, or cache key in this configuration

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

Spring Cache abstraction (`@EnableCaching`) backed by `RedisCacheManager`. Cache annotations (`@Cacheable`, `@CacheEvict`) are placed on repository or service methods for the three static data types: resource schedules, resource breaks, and branch holidays. The `CacheConfig` class sets a 5-minute TTL as a backstop for stale data. Booking queries carry no cache annotations — they always call the repository directly.

### Data Flow / Sequence

```
SlotController.getSlots()
  → SlotCalculatorService.computeAvailableSlots()
      → computeOperatingMatrix()
          → BranchHolidayRepository [CACHED: branch-holidays]
          → LocationRepository      [no cache — infrequent]
          → ResourceScheduleRepository [CACHED: resource-schedules]
          → ResourceBreakRepository    [CACHED: resource-breaks]
      → BookingRepository           [NO CACHE — always fresh]
```

**Eviction flow**:
```
ResourceService.replaceSchedule()
  → @CacheEvict(resource-schedules, key = resourceId + ':*')
  → scheduleRepository.deleteAllByResourceId() + saveAll()

HolidayService.createOrDeleteHoliday()
  → @CacheEvict(branch-holidays, key = locationId + ':*')
  → holidayRepository.save() / delete()
```

### File Structure

```
apps/api/src/main/java/com/scheduler/
├── config/
│   └── CacheConfig.java                 ← RedisCacheManager + CacheErrorHandler

apps/api/src/main/resources/
└── application.yml                      ← spring.cache.redis config

apps/api/src/test/java/com/scheduler/slot/
└── SlotCachingIT.java                   ← cache miss/hit/evict integration tests
```

### Interface Contracts

```java
// Cache configuration bean signatures only
@Configuration
@EnableCaching
public class CacheConfig {
    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory);

    @Bean
    public CacheErrorHandler cacheErrorHandler();
}

// Cache annotation shapes on repository/service methods
// (annotations only — no bodies)

// ResourceScheduleRepository:
@Cacheable(value = "resource-schedules", key = "#resourceId + ':' + #dayOfWeek")
List<ResourceSchedule> findByResourceIdAndDayOfWeek(UUID resourceId, int dayOfWeek);

// ResourceBreakRepository:
@Cacheable(value = "resource-breaks", key = "#resourceId + ':' + #dayOfWeek")
List<ResourceBreak> findByResourceIdAndDayOfWeek(UUID resourceId, int dayOfWeek);

// BranchHolidayRepository:
@Cacheable(value = "branch-holidays", key = "#locationId + ':' + #year + ':' + #month")
List<BranchHoliday> findByLocationIdAndYearMonth(UUID locationId, int year, int month);

// ResourceService — eviction:
@CacheEvict(value = "resource-schedules", allEntries = true)
void replaceSchedule(UUID tenantId, UUID resourceId, List<ScheduleEntry> entries);

@CacheEvict(value = "resource-breaks", allEntries = true)
void replaceBreaks(UUID tenantId, UUID resourceId, List<ScheduleEntry> entries);

// HolidayService — eviction:
@CacheEvict(value = "branch-holidays", allEntries = true)
void createHoliday(UUID tenantId, UUID locationId, CreateHolidayRequest request);

@CacheEvict(value = "branch-holidays", allEntries = true)
void deleteHoliday(UUID tenantId, UUID locationId, UUID holidayId);
```

### Design Rationale

- **ADR-001**: Slots are computed on-demand; caching the static inputs (schedules, holidays) rather than the computed slots avoids stale availability data at the output level.
- **Booking data never cached**: Booking state changes are the most frequent mutation and the source of concurrency risk (ADR-002). Caching booking queries would introduce a window where a conflicting hold appears available. The pessimistic lock in `createHold` relies on fresh DB reads.
- **5-minute TTL backstop**: Even if an eviction is missed (e.g., direct DB manipulation in emergency), cached schedules expire within 5 minutes — a bounded staleness window.
- **`allEntries = true` on eviction**: Spring Cache does not support wildcard key patterns natively; evicting all entries in the cache name is simpler and correct for low-cardinality caches like resource schedules.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL + Redis via `redis:7` container)

```
- shouldServeCacheHit_onSecondQuery:
    Given: first call to findByResourceIdAndDayOfWeek populates cache
    Assert: second call returns result without a DB query (verify via repository spy or Redis INFO)

- shouldEvictCache_onScheduleReplace:
    Given: schedule cached; replaceSchedule() called
    Assert: cache entry no longer present; next query hits DB and returns new schedule

- shouldFallbackToDb_whenRedisDown:
    Given: Redis container stopped; slot query issued
    Assert: response is 200 with correct slots (no 500 error); DB was queried

- shouldNeverCacheBookingData:
    Given: slot query; booking data fetched twice
    Assert: BookingRepository.findByResourceIdAndStatusInAndSlotStartBetween called twice (no cache hit)

- shouldReturnCorrectSlots_afterCacheEvict:
    Given: resource has schedule 09:00–17:00; replace with 10:00–18:00; query slots
    Assert: returned slots start at 10:00, not 09:00
```

**Coverage requirements**:
- Line coverage ≥ 80% on `CacheConfig`
- All three cache names must have at least one eviction test

---

## Implementation Constraints

- Only schedules, breaks, and holidays are cacheable — booking queries must never have `@Cacheable`
- Cache TTL: 5 minutes default for all three caches
- `CacheErrorHandler` must log a `WARN` on cache get failures and fall through to the underlying method
- `@EnableCaching` placed on `CacheConfig` class, not on the main application class
- Redis serialization: String keys, GenericJackson2JsonRedisSerializer for values
- Cache eviction on schedule/holiday writes must be synchronous (within the same call stack)
- No `System.out.println` — use SLF4J structured logging in `CacheErrorHandler`

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/com/scheduler/slot/SlotCachingIT.java` with Redis Testcontainer
2. Write `shouldServeCacheHit_onSecondQuery` — assert it fails (no cache config yet)
3. Write `shouldFallbackToDb_whenRedisDown` — assert it fails

### GREEN — Minimum code to pass

1. Add `spring-boot-starter-data-redis` and `spring-boot-starter-cache` to `pom.xml`
2. Implement `CacheConfig.java` with `RedisCacheManager` and `CacheErrorHandler`
3. Add `@Cacheable` to `findByResourceIdAndDayOfWeek` methods on both schedule and break repositories
4. Add `@Cacheable` to holiday lookup in `BranchHolidayRepository`
5. Add `@CacheEvict` to `replaceSchedule`, `replaceBreaks`, and holiday mutation methods
6. Confirm no `@Cacheable` annotation anywhere near `BookingRepository`

### REFACTOR — Quality pass

1. Add structured logging to `CacheErrorHandler.handleCacheGetError`
2. Document cache names and TTL in `application.yml` comments
3. Add Javadoc to `CacheConfig` explaining the three caches and their eviction strategy
4. Verify cache keys do not leak tenant data by inspecting Redis key space in integration test

---

## Implementation Reference

### CacheConfig

**File**: `apps/api/src/main/java/com/scheduler/config/CacheConfig.java`

```java
// [TASK: ATOM-SLOT-008]
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(5))
            .serializeKeysWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new StringRedisSerializer()))
            .serializeValuesWith(RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer()));

        return RedisCacheManager.builder(factory)
            .cacheDefaults(defaults)
            .build();
    }

    @Bean
    public CacheErrorHandler cacheErrorHandler() {
        return new SimpleCacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Redis cache get failed for {}/{}: {}", cache.getName(), key, e.getMessage());
                // Fall through — Spring will call the underlying method
            }
        };
    }
}
```

### Cache Annotations

**File**: `apps/api/src/main/java/com/scheduler/repository/ResourceScheduleRepository.java` (excerpt)

```java
// [TASK: ATOM-SLOT-008]
@Cacheable(value = "resource-schedules", key = "#resourceId + ':' + #dayOfWeek")
List<ResourceSchedule> findByResourceIdAndDayOfWeek(UUID resourceId, int dayOfWeek);

@Cacheable(value = "resource-breaks", key = "#resourceId + ':' + #dayOfWeek")
List<ResourceBreak> findByResourceIdAndDayOfWeek(UUID resourceId, int dayOfWeek);

@Cacheable(value = "branch-holidays", key = "#locationId + ':' + #year + ':' + #month")
List<BranchHoliday> findByLocationIdAndYearMonth(UUID locationId, int year, int month);
```

### Cache Eviction

**File**: `apps/api/src/main/java/com/scheduler/service/ResourceService.java` (excerpt)

```java
// [TASK: ATOM-SLOT-008]
@CacheEvict(value = "resource-schedules", allEntries = true)
public void replaceSchedule(UUID tenantId, UUID resourceId, List<ScheduleEntry> entries) { ... }

@CacheEvict(value = "resource-breaks", allEntries = true)
public void replaceBreaks(UUID tenantId, UUID resourceId, List<ScheduleEntry> entries) { ... }
```

**File**: `apps/api/src/main/java/com/scheduler/service/HolidayService.java` (excerpt)

```java
// [TASK: ATOM-SLOT-008]
@CacheEvict(value = "branch-holidays", allEntries = true)
public void createOrDeleteHoliday(UUID locationId, ...) { ... }
```

### Booking Data — Never Cached

```java
// [TASK: ATOM-SLOT-008]
// bookingRepository.findByResourceIdAndStatusInAndSlotStartBetween(...)
// → NO @Cacheable — always fresh from DB
```

---

## Integration Points

**Depends on**: ATOM-SLOT-007 (slot endpoint must exist to verify caching performance), ATOM-RESOURCE-002 (`replaceSchedule` / `replaceBreaks` eviction hooks), ATOM-HOLIDAY-004 (holiday mutation eviction hooks)

**Enables**: NFR-1.2 compliance (p99 < 300ms slot query gate)

**Cascading updates required**:
- `infra/docker-compose.yml` — ensure Redis service is defined
- `docs/ARCHITECTURE.md` — document caching strategy
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/com/scheduler/config/CacheConfig.java` | New | Redis cache manager + error handler |
| `apps/api/src/main/java/com/scheduler/repository/ResourceScheduleRepository.java` | Modified | Add @Cacheable |
| `apps/api/src/main/java/com/scheduler/repository/ResourceBreakRepository.java` | Modified | Add @Cacheable |
| `apps/api/src/main/java/com/scheduler/repository/BranchHolidayRepository.java` | Modified | Add @Cacheable |
| `apps/api/src/main/java/com/scheduler/service/ResourceService.java` | Modified | Add @CacheEvict on replace methods |
| `apps/api/src/main/java/com/scheduler/service/HolidayService.java` | Modified | Add @CacheEvict on create/delete |
| `apps/api/src/main/resources/application.yml` | Modified | Add spring.cache.redis config |
| `apps/api/pom.xml` | Modified | Add Redis + Cache starters |
| `apps/api/src/test/java/com/scheduler/slot/SlotCachingIT.java` | New | Cache integration tests |
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
