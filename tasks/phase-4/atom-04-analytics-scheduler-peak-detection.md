---
description: Atom design document for scheduled peak booking window and anomaly detection
---

# ATOM-ANALYTICS-004: Analytics Scheduler — Peak Booking Detection and Anomaly Alerts

**Status**: ✅ Complete (2026-07-20 — gauge registered once via @PostConstruct and updated every run (returns to 0 so alerts auto-resolve); shared AnalyticsFileWriter extracted; optional admin UI badge deferred — no anomalies endpoint exists yet)
**Feature**: analytics-peak-detection
**Phase**: 4 (Intelligence)
**Tags**: [ANALYTICS]
**Complexity**: Medium
**Agent**: coder + observability
**Dependencies**: ATOM-ANALYTICS-001 — booking pattern JSON files available in `docs/memory/booking-patterns/`
**Blocks**: None
**PR**: TBD

---

## Overview

This atom adds two nightly `@Scheduled` Spring components: `PeakWindowDetector` identifies booking windows where demand exceeds 1.5× the 30-day average and writes `peak-windows.json` to the memory namespace; `AnomalyDetector` flags resources whose booking count dropped > 80% versus their 7-day average and emits a Prometheus `Gauge` metric that AlertManager can alert on. Both jobs read from the flat-file memory namespace written by ATOM-ANALYTICS-001 and fail gracefully when insufficient data is available. The key design decision is pushing detection results back into the same `docs/memory/booking-patterns/` namespace so the AI optimization engine (ATOM-ANALYTICS-003) can consume peak and anomaly signals in future iterations.

---

## User Story

```
As a System
I want nightly detection of peak booking windows and booking count anomalies for each resource
So that tenant admins can be alerted to scheduling irregularities and operators can respond before the issue compounds
```

---

## Acceptance Criteria

- [ ] **AC-01**: `PeakWindowDetector.detectPeakWindows()` runs nightly at 02:30 UTC and writes `docs/memory/booking-patterns/peak-windows.json` after ≥ 14 days of aggregate data are available
- [ ] **AC-02**: `peak-windows.json` entries match the `PeakWindowRecord` schema: `resourceId`, `dayOfWeek`, `hourOfDay`, `bookingCount`, `confidenceScore`, `detectedAt`
- [ ] **AC-03**: `AnomalyDetector.detectAnomalies()` runs nightly at 02:45 UTC and writes `docs/memory/booking-patterns/anomalies.json` when any resource's booking count drops > 80% versus its 7-day average
- [ ] **AC-04**: `AnomalyDetector` emits Prometheus `Gauge` metric named `scheduling_booking_anomalies_detected` with value equal to the count of detected anomalies
- [ ] **AC-05**: Both jobs log execution time and record count at INFO level on each run
- [ ] **AC-06**: Both jobs fail gracefully when fewer than 7 days of aggregate pattern files exist — no exception propagates; INFO log indicates insufficient data
- [ ] **AC-07**: `anomalies.json` entries match the `AnomalyRecord` schema: `resourceId`, `tenantId`, `dropPercent`, `sevenDayAvg`, `yesterday`, `detectedAt`
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) appear in any JSON key, field name, or log message produced by this atom

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `PeakWindowDetectorIT` — cron trigger + file existence | `PeakWindowDetector.detectPeakWindows()` | 🔜 Planned |
| AC-02 | `PeakWindowDetectorTest` — JSON schema assertion | `PeakWindowRecord` record | 🔜 Planned |
| AC-03 | `AnomalyDetectorIT` — 80% drop scenario | `AnomalyDetector.detectAnomalies()` | 🔜 Planned |
| AC-04 | `AnomalyDetectorTest` — MeterRegistry mock, Gauge assertion | `AnomalyDetector.detectAnomalies()` | 🔜 Planned |
| AC-05 | `PeakWindowDetectorTest` + `AnomalyDetectorTest` — log capture | Both job classes | 🔜 Planned |
| AC-06 | `PeakWindowDetectorTest` + `AnomalyDetectorTest` — empty data directory | Both `loadLast*Days()` methods | 🔜 Planned |
| AC-07 | `AnomalyDetectorTest` — JSON schema assertion | `AnomalyRecord` record | 🔜 Planned |
| AC-08 | Static review + grep | All analytics output files | 🔜 Planned |

<!-- AC validation passed: TBD, 8 criteria written, all marked TBD -->

---

## Technical Design

### Architecture

Two independent `@Component` classes run on separate cron schedules, both offset from the ATOM-ANALYTICS-001 ingestion job (02:00 UTC) to ensure aggregate files are written before detection runs:

- **`PeakWindowDetector`** (02:30 UTC): loads last 30 days of `aggregate/summary-{date}.json` files, computes a 30-day mean booking count, and classifies any window exceeding 1.5× mean as a peak. Results written to `peak-windows.json`.
- **`AnomalyDetector`** (02:45 UTC): loads last 7 days of aggregate files plus yesterday's file, computes per-resource 7-day averages, and flags resources with > 80% drop. Results written to `anomalies.json` and a Prometheus `Gauge` is registered or updated with the anomaly count.

Both components share the same `ObjectMapper` and `outputBasePath` configuration. Neither introduces a database table.

### Data Flow / Sequence

```
02:00 UTC — BookingPatternIngestionJob writes aggregate/summary-{yesterday}.json
02:30 UTC — PeakWindowDetector.detectPeakWindows()
  → loadLast30Days(docs/memory/booking-patterns/aggregate/)
  → compute 30-day mean bookingCount
  → filter records where bookingCount > mean × 1.5
  → map to PeakWindowRecord list
  → writeJson(docs/memory/booking-patterns/peak-windows.json, peaks)
  → log.info("PeakWindowDetector: identified {} peak windows", peaks.size())

02:45 UTC — AnomalyDetector.detectAnomalies()
  → computeAnomalies()
      → loadLast7Days(aggregate/) + loadYesterday(aggregate/)
      → per resource: if yesterday < (7dayAvg × 0.20) → AnomalyRecord
  → if anomalies not empty:
      → writeJson(docs/memory/booking-patterns/anomalies.json, anomalies)
      → Gauge.builder("scheduling_booking_anomalies_detected", ...).register(meterRegistry)
      → log.warn("AnomalyDetector: {} anomalies detected", anomalies.size())
  → log.info("AnomalyDetector: completed, anomalies={}", anomalies.size())
```

### File Structure

```
apps/api/src/main/java/com/scheduler/
├── analytics/
│   ├── job/
│   │   ├── PeakWindowDetector.java              ← @Scheduled @Component 02:30 UTC
│   │   └── AnomalyDetector.java                 ← @Scheduled @Component 02:45 UTC
│   └── record/
│       ├── PeakWindowRecord.java                ← Java 21 record (peak output DTO)
│       └── AnomalyRecord.java                   ← Java 21 record (anomaly output DTO)

docs/memory/booking-patterns/
├── peak-windows.json                            ← written nightly by PeakWindowDetector
└── anomalies.json                               ← written nightly by AnomalyDetector

apps/web/src/app/[tenantId]/dashboard/
└── anomaly-badge.tsx                            ← optional: badge component for admin UI
```

### Interface Contracts

```java
// Output records — Java 21 records

public record PeakWindowRecord(
    UUID    resourceId,
    int     dayOfWeek,       // 1=Monday, 7=Sunday
    int     hourOfDay,       // 0–23 UTC
    long    bookingCount,
    double  confidenceScore, // bookingCount / 30-day mean
    Instant detectedAt
) {}

public record AnomalyRecord(
    UUID    resourceId,
    UUID    tenantId,
    double  dropPercent,     // 0.0–100.0
    double  sevenDayAvg,
    long    yesterday,
    Instant detectedAt
) {}

// Job component — method signatures only
@Component
public class PeakWindowDetector {

    @Scheduled(cron = "0 30 2 * * *")
    public void detectPeakWindows();

    private List<BookingPatternRecord> loadLast30Days(Path aggregateDir);

    private void writeJson(Path file, Object data);
}

@Component
public class AnomalyDetector {

    @Scheduled(cron = "0 45 2 * * *")
    public void detectAnomalies();

    private List<AnomalyRecord> computeAnomalies();

    private void writeJson(Path file, Object data);
}
```

```typescript
// Admin UI — anomaly badge component type shape
interface AnomalyBadgeProps {
  tenantId: string;
}

// Fetches: GET /api/v1/tenants/{tenantId}/analytics/anomalies
// Renders badge if response.anomalies.length > 0
```

### Design Rationale

- **Why 02:30 and 02:45 UTC offsets**: The ingestion job (ATOM-ANALYTICS-001) runs at 02:00 UTC. Both detection jobs are offset by 30 and 45 minutes respectively to ensure aggregate summary files are fully written before being read. This avoids a race condition without introducing explicit coordination.
- **Why 1.5× mean as the peak threshold**: A 50% above-average multiplier is a well-established heuristic for identifying meaningful spikes in scheduling data without over-alerting on normal daily variation. The threshold is applied to counts ≥ 10 bookings to avoid false positives from low-volume resources.
- **Why 80% drop for anomaly detection**: An 80% drop in booking volume versus a 7-day baseline represents a statistically significant departure that warrants investigation — misconfigured availability, accidental closure, or system error. Smaller drops (e.g., 30–40%) are within normal weekly variation.
- **Why Prometheus Gauge, not a Kafka event**: Anomaly alerts are operational signals consumed by the infrastructure monitoring stack (AlertManager), not by downstream business services. A Prometheus `Gauge` integrates directly with existing monitoring without introducing a new Kafka topic or consumer.
- **Why flat-file output, not a DB table**: Consistent with the analytics layer design decision from ATOM-ANALYTICS-001 — flat files are human-readable for debugging, decouple the intelligence layer from the transactional schema, and can be consumed by future AI agents without a DB query.
- **ADR-001 reference**: No slot records are written — this atom reads booking aggregates, not slot states.

---

## Test Strategy

**Test type**: Unit (JUnit 5 + Mockito) + Integration (Testcontainers + file system)

```
- shouldWritePeakWindowsFile_whenBookingCountExceedsThreshold:
    Given: loadLast30Days returns records where resource A has bookingCount = 3× 30-day mean
    Assert: peak-windows.json written; contains entry for resource A with confidenceScore ≥ 1.5

- shouldNotWritePeakWindowsFile_whenAllCountsBelowThreshold:
    Given: loadLast30Days returns records all within 1.5× mean
    Assert: peak-windows.json either absent or empty array

- shouldGracefullyExit_whenLessThan7DaysOfAggregateData:
    Given: aggregate directory contains fewer than 7 summary files
    Assert: detectPeakWindows() and detectAnomalies() both complete without throwing;
            INFO log contains "insufficient data"

- shouldWriteAnomaliesFile_whenResourceDropsAbove80Percent:
    Given: resource B had 7-day average of 30 bookings; yesterday had 4 bookings (87% drop)
    Assert: anomalies.json written; contains entry for resource B with dropPercent ≥ 80

- shouldEmitPrometheusGauge_whenAnomaliesDetected:
    Given: AnomalyDetector.computeAnomalies() returns 2 anomaly records
    Assert: MeterRegistry contains Gauge named "scheduling_booking_anomalies_detected" with value 2.0

- shouldNotEmitGauge_whenNoAnomaliesDetected:
    Given: AnomalyDetector.computeAnomalies() returns empty list
    Assert: anomalies.json not written; no new Gauge registered

- shouldLogExecutionTimeAndRecordCount_atInfoLevel:
    Given: both jobs complete normally
    Assert: SLF4J test appender captures INFO log with record count for each job

- shouldNotIncludeIndustrySpecificTerms_inOutputJson:
    Given: detection jobs run and write output files
    Assert: grep for doctor|patient|vehicle|mechanic in peak-windows.json and anomalies.json returns zero matches
```

**Coverage requirements**:
- Line coverage ≥ 80% on `PeakWindowDetector` and `AnomalyDetector`
- Prometheus Gauge emission path covered
- Graceful no-data exit path covered for both jobs

---

## Implementation Constraints

- Both jobs must be annotated `@Slf4j` — no `System.out.println`
- Job failures (I/O errors) must be caught and logged at ERROR level — never propagate to scheduler thread pool
- Memory files written to `docs/memory/booking-patterns/` — path configurable via `${app.memory.booking-patterns-path}`
- Output JSON keys must use camelCase generic domain terms only — no industry-specific terms
- `PeakWindowDetector` cron: `0 30 2 * * *` (02:30 UTC, after ingestion at 02:00)
- `AnomalyDetector` cron: `0 45 2 * * *` (02:45 UTC)
- Prometheus `Gauge` metric name: `scheduling_booking_anomalies_detected` — do not change without updating AlertManager rules
- DTOs must be Java 21 records (never classes)
- No new database tables for this atom
- No direct Kafka writes from these jobs
- All Next.js API calls through `apps/web/lib/api-client.ts` (for the optional admin UI badge)

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/…/analytics/PeakWindowDetectorTest.java` with temp file system and mock `ObjectMapper`
2. Write `shouldWritePeakWindowsFile_whenBookingCountExceedsThreshold` — fails (class does not exist)
3. Write `shouldGracefullyExit_whenLessThan7DaysOfAggregateData` — fails
4. Create `src/test/java/…/analytics/AnomalyDetectorTest.java` with `SimpleMeterRegistry`
5. Write `shouldEmitPrometheusGauge_whenAnomaliesDetected` — fails
6. Write `shouldWriteAnomaliesFile_whenResourceDropsAbove80Percent` — fails

### GREEN — Minimum code to pass

1. Implement `PeakWindowRecord.java` Java 21 record
2. Implement `AnomalyRecord.java` Java 21 record
3. Implement `PeakWindowDetector.java` with `detectPeakWindows()`, `loadLast30Days()`, `writeJson()`
4. Implement `AnomalyDetector.java` with `detectAnomalies()`, `computeAnomalies()`, `writeJson()`, Gauge registration

### REFACTOR — Quality pass

1. Extract shared `writeJson(Path, Object)` logic to a `AnalyticsFileWriter` utility class or `@Component` to avoid duplication between both jobs
2. Add Javadoc to all public methods
3. Add structured logging with execution start time and duration: `log.info("PeakWindowDetector: completed in {}ms, peaks={}", duration, peaks.size())`
4. Run `/security-scan` scoped to `analytics/job/` package
5. Verify output JSON matches documented schema via Jackson schema validation in tests

---

## Implementation Reference

### PeakWindowRecord

**File**: `apps/api/src/main/java/com/scheduler/analytics/record/PeakWindowRecord.java`

```java
// [TASK: ATOM-ANALYTICS-004]
package com.scheduler.analytics.record;

import java.time.Instant;
import java.util.UUID;

public record PeakWindowRecord(
    UUID    resourceId,
    int     dayOfWeek,
    int     hourOfDay,
    long    bookingCount,
    double  confidenceScore,
    Instant detectedAt
) {}
```

### AnomalyRecord

**File**: `apps/api/src/main/java/com/scheduler/analytics/record/AnomalyRecord.java`

```java
// [TASK: ATOM-ANALYTICS-004]
package com.scheduler.analytics.record;

import java.time.Instant;
import java.util.UUID;

public record AnomalyRecord(
    UUID    resourceId,
    UUID    tenantId,
    double  dropPercent,
    double  sevenDayAvg,
    long    yesterday,
    Instant detectedAt
) {}
```

### PeakWindowDetector

**File**: `apps/api/src/main/java/com/scheduler/analytics/job/PeakWindowDetector.java`

```java
// [TASK: ATOM-ANALYTICS-004]
package com.scheduler.analytics.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.analytics.record.BookingPatternRecord;
import com.scheduler.analytics.record.PeakWindowRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PeakWindowDetector {

    private final ObjectMapper objectMapper;

    @Value("${app.memory.booking-patterns-path:docs/memory/booking-patterns}")
    private String patternsPath;

    @Scheduled(cron = "0 30 2 * * *")   // 02:30 UTC nightly
    public void detectPeakWindows() {
        long start = System.currentTimeMillis();
        Path aggregateDir = Path.of(patternsPath, "aggregate");
        List<BookingPatternRecord> allPatterns = loadLast30Days(aggregateDir);

        if (allPatterns.isEmpty()) {
            log.info("PeakWindowDetector: insufficient data — skipping peak detection");
            return;
        }

        double avg = allPatterns.stream()
            .mapToLong(BookingPatternRecord::bookingCount)
            .average()
            .orElse(0);
        double threshold = avg * 1.5;

        List<PeakWindowRecord> peaks = allPatterns.stream()
            .filter(p -> p.bookingCount() > threshold)
            .map(p -> new PeakWindowRecord(
                p.resourceId(), p.dayOfWeek(), p.hourOfDay(),
                p.bookingCount(), p.bookingCount() / avg, Instant.now()))
            .toList();

        Path outFile = Path.of(patternsPath, "peak-windows.json");
        writeJson(outFile, peaks);

        long elapsed = System.currentTimeMillis() - start;
        log.info("PeakWindowDetector: identified {} peak windows in {}ms", peaks.size(), elapsed);
    }

    private List<BookingPatternRecord> loadLast30Days(Path aggregateDir) {
        if (!Files.exists(aggregateDir)) return List.of();
        try (var stream = Files.list(aggregateDir)) {
            return stream
                .sorted()
                .limit(30)
                .map(f -> {
                    try {
                        return objectMapper.readValue(f.toFile(), BookingPatternRecord[].class);
                    } catch (IOException e) {
                        log.warn("PeakWindowDetector: could not read aggregate file {}: {}", f, e.getMessage());
                        return new BookingPatternRecord[0];
                    }
                })
                .flatMap(java.util.Arrays::stream)
                .toList();
        } catch (IOException e) {
            log.error("PeakWindowDetector: could not list aggregate directory: {}", e.getMessage());
            return List.of();
        }
    }

    private void writeJson(Path file, Object data) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (IOException e) {
            log.error("PeakWindowDetector: failed to write file {}: {}", file, e.getMessage());
        }
    }
}
```

### AnomalyDetector

**File**: `apps/api/src/main/java/com/scheduler/analytics/job/AnomalyDetector.java`

```java
// [TASK: ATOM-ANALYTICS-004]
package com.scheduler.analytics.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.analytics.record.AnomalyRecord;
import com.scheduler.analytics.record.BookingPatternRecord;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class AnomalyDetector {

    private final MeterRegistry meterRegistry;
    private final ObjectMapper   objectMapper;

    @Value("${app.memory.booking-patterns-path:docs/memory/booking-patterns}")
    private String patternsPath;

    @Scheduled(cron = "0 45 2 * * *")   // 02:45 UTC nightly
    public void detectAnomalies() {
        long start     = System.currentTimeMillis();
        List<AnomalyRecord> anomalies = computeAnomalies();

        if (!anomalies.isEmpty()) {
            Path outFile = Path.of(patternsPath, "anomalies.json");
            writeJson(outFile, anomalies);

            Gauge.builder("scheduling_booking_anomalies_detected", anomalies, List::size)
                .description("Number of booking anomalies detected in the latest nightly scan")
                .register(meterRegistry);

            log.warn("AnomalyDetector: {} anomalies detected — see {}",
                anomalies.size(), outFile);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("AnomalyDetector: completed in {}ms, anomalies={}", elapsed, anomalies.size());
    }

    private List<AnomalyRecord> computeAnomalies() {
        Path aggregateDir = Path.of(patternsPath, "aggregate");
        if (!Files.exists(aggregateDir)) {
            log.info("AnomalyDetector: insufficient data — aggregate directory does not exist");
            return List.of();
        }

        // Load last 8 days to get 7-day baseline + yesterday
        List<BookingPatternRecord> recent = loadLastNDays(aggregateDir, 8);
        if (recent.size() < 7) {
            log.info("AnomalyDetector: insufficient data — fewer than 7 days available");
            return List.of();
        }

        // Split: last 7 days as baseline, most-recent as yesterday proxy
        List<BookingPatternRecord> baseline  = recent.subList(0, recent.size() - 1);
        List<BookingPatternRecord> yesterday = recent.subList(recent.size() - 1, recent.size());

        Map<java.util.UUID, Double> sevenDayAvg = baseline.stream()
            .collect(Collectors.groupingBy(
                BookingPatternRecord::resourceId,
                Collectors.averagingDouble(r -> (double) r.bookingCount())));

        Map<java.util.UUID, Long> yesterdayCount = yesterday.stream()
            .collect(Collectors.groupingBy(
                BookingPatternRecord::resourceId,
                Collectors.summingLong(BookingPatternRecord::bookingCount)));

        return sevenDayAvg.entrySet().stream()
            .filter(e -> {
                long yCount = yesterdayCount.getOrDefault(e.getKey(), 0L);
                return e.getValue() > 0 && yCount < e.getValue() * 0.20;
            })
            .map(e -> {
                long yCount = yesterdayCount.getOrDefault(e.getKey(), 0L);
                double drop = (1.0 - yCount / e.getValue()) * 100.0;
                // tenantId not available in aggregated record — set from first matching baseline entry
                java.util.UUID tenantId = baseline.stream()
                    .filter(r -> r.resourceId().equals(e.getKey()))
                    .map(BookingPatternRecord::tenantId)
                    .findFirst().orElse(null);
                return new AnomalyRecord(e.getKey(), tenantId, drop, e.getValue(), yCount, Instant.now());
            })
            .toList();
    }

    private List<BookingPatternRecord> loadLastNDays(Path aggregateDir, int n) {
        try (var stream = Files.list(aggregateDir)) {
            return stream
                .sorted(java.util.Comparator.reverseOrder())
                .limit(n)
                .map(f -> {
                    try {
                        return objectMapper.readValue(f.toFile(), BookingPatternRecord[].class);
                    } catch (IOException e) {
                        log.warn("AnomalyDetector: could not read file {}: {}", f, e.getMessage());
                        return new BookingPatternRecord[0];
                    }
                })
                .flatMap(java.util.Arrays::stream)
                .toList();
        } catch (IOException e) {
            log.error("AnomalyDetector: could not list aggregate directory: {}", e.getMessage());
            return List.of();
        }
    }

    private void writeJson(Path file, Object data) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (IOException e) {
            log.error("AnomalyDetector: failed to write file {}: {}", file, e.getMessage());
        }
    }
}
```

### Optional Admin UI — Anomaly Badge

**File**: `apps/web/src/app/[tenantId]/dashboard/anomaly-badge.tsx`

```typescript
// [TASK: ATOM-ANALYTICS-004] — optional v1 admin UI badge
'use client';

import { useQuery } from '@tanstack/react-query';
import { apiClient } from '@/lib/api-client';

interface AnomalyBadgeProps {
  tenantId: string;
}

export function AnomalyBadge({ tenantId }: AnomalyBadgeProps) {
  const { data } = useQuery({
    queryKey: ['anomalies', tenantId],
    queryFn: () => apiClient.get<{ anomalies: unknown[] }>(
      `/api/v1/tenants/${tenantId}/analytics/anomalies`
    ),
    refetchInterval: 5 * 60 * 1000, // refresh every 5 minutes
  });

  const count = data?.anomalies?.length ?? 0;
  if (count === 0) return null;

  return (
    <span className="anomaly-badge">
      ⚠ {count} scheduling {count === 1 ? 'anomaly' : 'anomalies'} detected
    </span>
  );
}
```

---

## Integration Points

**Depends on**: ATOM-ANALYTICS-001 — `docs/memory/booking-patterns/aggregate/summary-{date}.json` files written nightly; Prometheus / Micrometer configured in the Spring Boot app

**Enables**: AlertManager can alert on `scheduling_booking_anomalies_detected > 0`; future AI agents can consume `peak-windows.json` and `anomalies.json` as additional context

**Cascading updates required**:
- Prometheus AlertManager rules — add alert for `scheduling_booking_anomalies_detected > 0` (observability agent)
- `docs/API-SPEC.md` — add `GET /api/v1/tenants/{tenantId}/analytics/anomalies` endpoint if the UI badge endpoint is implemented
- `tasks/MASTER-TASK-LIST.md` — mark ATOM-ANALYTICS-004 complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/…/analytics/record/PeakWindowRecord.java` | New | Peak window output record |
| `apps/api/src/main/java/…/analytics/record/AnomalyRecord.java` | New | Anomaly output record |
| `apps/api/src/main/java/…/analytics/job/PeakWindowDetector.java` | New | Nightly peak window detection job |
| `apps/api/src/main/java/…/analytics/job/AnomalyDetector.java` | New | Nightly anomaly detection + Prometheus gauge |
| `apps/api/src/test/java/…/analytics/PeakWindowDetectorTest.java` | New | Unit tests |
| `apps/api/src/test/java/…/analytics/AnomalyDetectorTest.java` | New | Unit tests |
| `apps/web/src/app/[tenantId]/dashboard/anomaly-badge.tsx` | New (optional) | Admin UI anomaly badge |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] Zero industry-specific terms in any JSON key, field name, or log message
- [ ] Prometheus metric name `scheduling_booking_anomalies_detected` matches AlertManager rule
- [ ] Both jobs fail gracefully with INFO log when < 7 days of data available
- [ ] Both jobs log execution time and record count at INFO level
- [ ] Job exceptions caught and logged at ERROR level — never propagate to scheduler
- [ ] ADR created or referenced (if architectural decision made)
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: analytics-peak-detection | Phase: 4*
