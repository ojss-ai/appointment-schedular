---
description: Atom design document for booking analytics nightly memory ingestion
---

# ATOM-ANALYTICS-001: Booking Analytics Nightly Memory Ingestion

**Status**: 🟡 Planned
**Feature**: analytics-memory-ingestion
**Phase**: 4 (Intelligence)
**Tags**: [ANALYTICS]
**Complexity**: Medium
**Agent**: coder + observability
**Dependencies**: ATOM-AUDIT-010 (P3 atom-10) — audit data flowing into `audit_log`
**Blocks**: ATOM-ANALYTICS-003, ATOM-ANALYTICS-004
**PR**: TBD

---

## Overview

This atom builds a nightly `@Scheduled` job that aggregates confirmed booking records from `audit_log` into structured JSON memory files under `docs/memory/booking-patterns/`. The files serve as the primary input for the AI slot optimization engine (ATOM-ANALYTICS-003) and the peak detection scheduler (ATOM-ANALYTICS-004). The key design decision is storing aggregated data in flat JSON files rather than a database table, keeping the intelligence layer stateless and auditable without adding schema complexity.

---

## User Story

```
As a System
I want booking pattern data aggregated nightly from audit logs into structured JSON files
So that the AI optimization engine has reliable, tenant-scoped input data to generate slot suggestions
```

---

## Acceptance Criteria

- [ ] **AC-01**: `BookingPatternIngestionJob.ingestYesterdaysPatterns()` executes daily at 02:00 UTC, confirmed by structured log entry `BookingPatternIngestionJob: ingesting patterns for {date}`
- [ ] **AC-02**: After each run, JSON files exist at `docs/memory/booking-patterns/by-resource/{resourceId}.json` for every resource with bookings on the processed date
- [ ] **AC-03**: After 7 consecutive nightly runs, each resource pattern file contains records covering ≥ 7 distinct dates
- [ ] **AC-04**: All written JSON files are valid and match the `BookingPatternRecord` schema documented in `docs/memory/booking-patterns/README.md`
- [ ] **AC-05**: A job failure (I/O error, query exception) does not propagate to the API process — the error is caught, logged at ERROR level, and the scheduler continues
- [ ] **AC-06**: `AuditLogRepository.aggregateBookingPatterns()` completes in < 5 seconds for 30 days of audit data with the required index in place
- [ ] **AC-07 (Tenant isolation)**: All JPA/native queries include `tenant_id` in WHERE or GROUP BY clause — zero cross-tenant rows returned
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) appear in any JSON key, file name, log message, or field name

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `BookingPatternIngestionJobIT` — scheduler cron assert | `BookingPatternIngestionJob.java` | 🔜 Planned |
| AC-02 | `BookingPatternIngestionJobIT` — file existence assert | `BookingPatternIngestionJob.writeJson()` | 🔜 Planned |
| AC-03 | `BookingPatternIngestionJobIT` — 7-run simulation | `BookingPatternIngestionJob.ingestYesterdaysPatterns()` | 🔜 Planned |
| AC-04 | `BookingPatternIngestionJobTest` — JSON schema validation | `BookingPatternRecord` record | 🔜 Planned |
| AC-05 | `BookingPatternIngestionJobTest` — mock I/O failure | `BookingPatternIngestionJob.writeJson()` | 🔜 Planned |
| AC-06 | `AuditLogRepositoryIT` — query timing with 30-day dataset | `AuditLogRepository.aggregateBookingPatterns()` | 🔜 Planned |
| AC-07 | `AuditLogRepositoryIT` — cross-tenant query isolation | `AuditLogRepository` native query | 🔜 Planned |
| AC-08 | Static review / grep | All phase-4 analytics output files | 🔜 Planned |

<!-- AC validation passed: TBD, 8 criteria written, all marked TBD -->

---

## Technical Design

### Architecture

The ingestion pipeline is a single Spring `@Component` with a `@Scheduled` cron method. It queries `audit_log` via a native SQL aggregate query in `AuditLogRepository`, groups results by `resourceId`, and writes each group to a per-resource JSON file using Jackson `ObjectMapper`. An additional aggregate summary file is written for use by the peak detection job. No new database tables are introduced; the output is the flat-file memory namespace. The job runs after the audit CDC pipeline (P3) has processed the previous day's events.

### Data Flow / Sequence

```
@Scheduled(cron "0 0 2 * * *")
  → AuditLogRepository.aggregateBookingPatterns(yesterday)    [native SQL aggregate]
  → DB: audit_log GROUP BY resourceId, tenantId, serviceTypeId, dayOfWeek, hourOfDay
  → rows grouped by resourceId in memory
  → ObjectMapper.writeValue → docs/memory/booking-patterns/by-resource/{id}.json
  → ObjectMapper.writeValue → docs/memory/booking-patterns/aggregate/summary-{date}.json
  → log.info(record count)
```

### File Structure

```
apps/api/src/main/java/com/scheduler/
├── analytics/
│   ├── job/
│   │   └── BookingPatternIngestionJob.java     ← @Scheduled @Component
│   ├── record/
│   │   └── BookingPatternRecord.java           ← Java 21 record (output DTO)
│   │   └── BookingPatternRow.java              ← Java 21 record (query projection)
│   └── repository/
│       └── AuditLogRepository.java             ← JpaRepository + native aggregate query

apps/api/src/main/resources/db/migration/
└── V041__idx_audit_log_booking_patterns.sql    ← Flyway index migration

docs/memory/booking-patterns/
├── README.md                                   ← BookingPatternRecord schema docs
├── by-resource/
│   └── {resourceId}.json
├── by-service-type/
│   └── {serviceTypeId}.json
└── aggregate/
    └── summary-{YYYY-MM-DD}.json
```

### Interface Contracts

```java
// Java 21 record — output shape written to JSON files
public record BookingPatternRecord(
    UUID   resourceId,
    UUID   tenantId,
    UUID   serviceTypeId,
    int    dayOfWeek,      // 1=Monday, 7=Sunday
    int    hourOfDay,      // 0–23 UTC
    long   bookingCount,
    double utilization,    // confirmed / total_available_slots
    Instant updatedAt
) {}

// Java 21 record — native query projection
public record BookingPatternRow(
    UUID   resourceId,
    UUID   tenantId,
    UUID   serviceTypeId,
    int    dayOfWeek,
    int    hourOfDay,
    long   bookingCount
) {}

// Repository signature only
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Query(value = """
        SELECT
            a.metadata->>'resourceId'    AS resource_id,
            a.tenant_id,
            a.metadata->>'serviceTypeId' AS service_type_id,
            EXTRACT(DOW FROM a.when_)    AS day_of_week,
            EXTRACT(HOUR FROM a.when_)   AS hour_of_day,
            COUNT(*)                     AS booking_count
        FROM audit_log a
        WHERE a.what = 'BookingConfirmed'
          AND DATE(a.when_) = :date
          AND a.tenant_id IS NOT NULL
        GROUP BY 1, 2, 3, 4, 5
        """, nativeQuery = true)
    List<BookingPatternRow> aggregateBookingPatterns(@Param("date") LocalDate date);
}

// Scheduled job — method signature only
@Component
public class BookingPatternIngestionJob {
    @Scheduled(cron = "0 0 2 * * *")
    public void ingestYesterdaysPatterns();

    private void writeJson(Path file, Object data);
}
```

### Design Rationale

- **Why JSON files, not a DB table**: Keeps the intelligence layer decoupled from the transactional schema. Files are human-readable, trivially version-controlled for debugging, and can be consumed by the Claude API call in ATOM-ANALYTICS-003 without an extra DB round-trip. ADR-005 (generic domain model with JSONB extension) supports this philosophy of keeping analytics state external.
- **Why native SQL aggregate**: JPA JPQL cannot express `EXTRACT(DOW …)` and JSONB accessor syntax directly. The native query is the most readable and performant approach for this aggregate.
- **Why 02:00 UTC**: Ensures all previous-day CDC events (P3 Debezium pipeline) have been processed before the ingestion job reads from `audit_log`.
- **ADR-003 reference**: The outbox pattern (ADR-003) guarantees `audit_log` entries are complete before this job runs — the ingestion job is safe to consume them after the nightly CDC lag window.

---

## Test Strategy

**Test type**: Unit (JUnit 5) + Integration (Testcontainers + PostgreSQL)

```
- shouldWriteResourcePatternFile_whenBookingsExistForDate:
    Given: audit_log contains 5 BookingConfirmed events for resourceId X on date D
    Assert: file docs/memory/booking-patterns/by-resource/{X}.json exists and contains 5 records

- shouldWriteAggregateSummaryFile_withCorrectDate:
    Given: ingestion job runs for date D
    Assert: docs/memory/booking-patterns/aggregate/summary-{D}.json exists and is valid JSON

- shouldNotThrow_whenWriteFails:
    Given: output directory is not writable (mocked IOException)
    Assert: ingestYesterdaysPatterns() completes without rethrowing; error logged at ERROR level

- shouldReturnOnlyTenantOwnedRows_whenAggregating:
    Given: audit_log contains BookingConfirmed events for tenantA and tenantB
    Assert: aggregateBookingPatterns() result for tenantA contains zero rows with tenantId = tenantB

- shouldCompleteWithin5Seconds_for30DaysOfData:
    Given: audit_log seeded with 30 days × ~1000 BookingConfirmed events
    Assert: aggregateBookingPatterns() execution time < 5000ms (measured with Testcontainers timer)

- shouldProduceEmptyResult_whenNoBookingsOnDate:
    Given: audit_log has no BookingConfirmed events for yesterday
    Assert: ingestYesterdaysPatterns() exits after DB query; no files written; no exception thrown
```

**Coverage requirements**:
- Line coverage ≥ 80% on `BookingPatternIngestionJob`
- Integration test must use Testcontainers PostgreSQL with the Flyway index migration applied

---

## Implementation Constraints

- Every JPA/native query must include `tenant_id` in the WHERE or GROUP BY clause
- DTOs must be Java 21 records (never classes)
- `BookingPatternIngestionJob` must be annotated `@Slf4j` — no `System.out.println`
- Job failure must be caught inside `ingestYesterdaysPatterns()` — never propagate to the scheduler thread pool
- Output JSON keys must use camelCase and generic domain terms only (`resourceId`, `serviceTypeId` — never `doctorId`, `vehicleId`)
- Memory files written to `docs/memory/booking-patterns/` — path configurable via `${app.memory.booking-patterns-path}`
- No `slots` table — this job reads only `audit_log`
- All Next.js API calls through `apps/web/lib/api-client.ts` (N/A for this backend-only atom)
- Flyway migration `V041__idx_audit_log_booking_patterns.sql` must exist before the query code is merged (NFR-1.3 equivalent)

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/…/analytics/BookingPatternIngestionJobIT.java` with Testcontainers PostgreSQL
2. Write `shouldWriteResourcePatternFile_whenBookingsExistForDate` — assert it fails (job class does not exist)
3. Write `shouldReturnOnlyTenantOwnedRows_whenAggregating` in `AuditLogRepositoryIT.java` — assert it fails
4. Write `shouldNotThrow_whenWriteFails` — assert it fails

### GREEN — Minimum code to pass

1. Create Flyway migration `V041__idx_audit_log_booking_patterns.sql` with compound index on `(tenant_id, what, when_)`
2. Implement `BookingPatternRow.java` Java 21 record
3. Implement `BookingPatternRecord.java` Java 21 record
4. Add `aggregateBookingPatterns()` native query to `AuditLogRepository`
5. Implement `BookingPatternIngestionJob` with `ingestYesterdaysPatterns()` and `writeJson()` — minimum logic to pass RED tests

### REFACTOR — Quality pass

1. Add structured logging: `log.info("BookingPatternIngestionJob: wrote {} pattern records for date {}", rows.size(), yesterday)`
2. Extract `groupAndWriteByResource()` private method for clarity
3. Add Javadoc to `ingestYesterdaysPatterns()` and `writeJson()`
4. Verify no cross-layer leakage (no JPA entities exposed in written JSON — use records only)
5. Run `/security-scan` scoped to `analytics/` package

---

## Implementation Reference

### Flyway Migration V041

**File**: `apps/api/src/main/resources/db/migration/V041__idx_audit_log_booking_patterns.sql`

```sql
-- [TASK: ATOM-ANALYTICS-001] Index supporting nightly booking pattern aggregation
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_audit_log_booking_patterns
    ON audit_log (tenant_id, what, when_)
    WHERE what = 'BookingConfirmed';
```

### BookingPatternRow (query projection)

**File**: `apps/api/src/main/java/com/scheduler/analytics/record/BookingPatternRow.java`

```java
// [TASK: ATOM-ANALYTICS-001]
package com.scheduler.analytics.record;

import java.util.UUID;

public record BookingPatternRow(
    UUID   resourceId,
    UUID   tenantId,
    UUID   serviceTypeId,
    int    dayOfWeek,
    int    hourOfDay,
    long   bookingCount
) {}
```

### BookingPatternRecord (output DTO)

**File**: `apps/api/src/main/java/com/scheduler/analytics/record/BookingPatternRecord.java`

```java
// [TASK: ATOM-ANALYTICS-001]
package com.scheduler.analytics.record;

import java.time.Instant;
import java.util.UUID;

public record BookingPatternRecord(
    UUID    resourceId,
    UUID    tenantId,
    UUID    serviceTypeId,
    int     dayOfWeek,
    int     hourOfDay,
    long    bookingCount,
    double  utilization,
    Instant updatedAt
) {}
```

### AuditLogRepository

**File**: `apps/api/src/main/java/com/scheduler/analytics/repository/AuditLogRepository.java`

```java
// [TASK: ATOM-ANALYTICS-001]
package com.scheduler.analytics.repository;

import com.scheduler.analytics.record.BookingPatternRow;
import com.scheduler.domain.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    @Query(value = """
        SELECT
            a.metadata->>'resourceId'    AS resource_id,
            a.tenant_id,
            a.metadata->>'serviceTypeId' AS service_type_id,
            EXTRACT(DOW FROM a.when_)    AS day_of_week,
            EXTRACT(HOUR FROM a.when_)   AS hour_of_day,
            COUNT(*)                     AS booking_count
        FROM audit_log a
        WHERE a.what = 'BookingConfirmed'
          AND DATE(a.when_) = :date
          AND a.tenant_id IS NOT NULL
        GROUP BY 1, 2, 3, 4, 5
        """, nativeQuery = true)
    List<BookingPatternRow> aggregateBookingPatterns(@Param("date") LocalDate date);
}
```

### BookingPatternIngestionJob

**File**: `apps/api/src/main/java/com/scheduler/analytics/job/BookingPatternIngestionJob.java`

```java
// [TASK: ATOM-ANALYTICS-001]
package com.scheduler.analytics.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.analytics.record.BookingPatternRow;
import com.scheduler.analytics.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookingPatternIngestionJob {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper        objectMapper;

    @Value("${app.memory.booking-patterns-path:docs/memory/booking-patterns}")
    private String outputBasePath;

    @Scheduled(cron = "0 0 2 * * *")   // 02:00 UTC nightly
    public void ingestYesterdaysPatterns() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("BookingPatternIngestionJob: ingesting patterns for {}", yesterday);

        try {
            List<BookingPatternRow> rows = auditLogRepository
                .aggregateBookingPatterns(yesterday);

            // Group by resourceId and write per-resource JSON files
            rows.stream()
                .collect(Collectors.groupingBy(BookingPatternRow::resourceId))
                .forEach((resourceId, patterns) -> {
                    Path file = Path.of(outputBasePath, "by-resource", resourceId + ".json");
                    writeJson(file, patterns);
                });

            // Write aggregate daily summary
            Path summary = Path.of(outputBasePath, "aggregate",
                "summary-" + yesterday + ".json");
            writeJson(summary, rows);

            log.info("BookingPatternIngestionJob: wrote {} pattern records for date {}",
                rows.size(), yesterday);

        } catch (Exception e) {
            log.error("BookingPatternIngestionJob: failed for date {}: {}",
                yesterday, e.getMessage(), e);
        }
    }

    private void writeJson(Path file, Object data) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (IOException e) {
            log.error("BookingPatternIngestionJob: failed to write file {}: {}",
                file, e.getMessage());
        }
    }
}
```

---

## Integration Points

**Depends on**: ATOM-AUDIT-010 (P3) — `audit_log` table populated with `BookingConfirmed` events via Debezium CDC

**Enables**: ATOM-ANALYTICS-003 (`SlotOptimizationService` reads `by-resource/*.json`), ATOM-ANALYTICS-004 (`PeakWindowDetector` reads `aggregate/summary-*.json`)

**Cascading updates required**:
- `docs/memory/booking-patterns/README.md` — add `BookingPatternRecord` JSON schema documentation
- `tasks/MASTER-TASK-LIST.md` — mark ATOM-ANALYTICS-001 complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/resources/db/migration/V041__idx_audit_log_booking_patterns.sql` | New | Index for nightly aggregate query |
| `apps/api/src/main/java/…/analytics/record/BookingPatternRow.java` | New | Native query projection record |
| `apps/api/src/main/java/…/analytics/record/BookingPatternRecord.java` | New | Output DTO written to JSON files |
| `apps/api/src/main/java/…/analytics/repository/AuditLogRepository.java` | New | Native aggregate query |
| `apps/api/src/main/java/…/analytics/job/BookingPatternIngestionJob.java` | New | Nightly `@Scheduled` ingestion job |
| `apps/api/src/test/java/…/analytics/BookingPatternIngestionJobIT.java` | New | Integration tests (Testcontainers) |
| `apps/api/src/test/java/…/analytics/AuditLogRepositoryIT.java` | New | Repository + tenant isolation tests |
| `docs/memory/booking-patterns/README.md` | New | JSON schema documentation |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] Zero JPA/native queries without `tenant_id` in WHERE or GROUP BY clause
- [ ] Zero industry-specific terms in any identifier, JSON key, or log message
- [ ] Flyway migration `V041__idx_audit_log_booking_patterns.sql` exists
- [ ] Job failure does not propagate — all exceptions caught and logged at ERROR level
- [ ] Output JSON keys are camelCase generic domain terms only
- [ ] ADR created or referenced (if architectural decision made)
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: analytics-memory-ingestion | Phase: 4*
