# scheduling:booking-patterns — Flat-File Analytics Memory Namespace

> Written nightly by the Phase 4 analytics jobs (`apps/api`,
> `com.scheduler.api.analytics`). Consumed by the AI slot optimization
> endpoint (ATOM-ANALYTICS-003) and future agent sessions. **Never edit by
> hand** — files are regenerated every night. Contains aggregated,
> non-PII counts only (UUIDs + counters; no names, contact data, or
> booking payloads).

## Write / read schedule (all UTC)

| Time | Job | Writes | Reads |
|---|---|---|---|
| 02:00 | `BookingPatternIngestionJob` | `by-resource/`, `by-service-type/`, `aggregate/summary-{date}.json` | `audit_log` (BookingConfirmed) |
| 02:30 | `PeakWindowDetector` | `peak-windows.json` | `aggregate/` (last 30 days, min 14) |
| 02:45 | `AnomalyDetector` | `anomalies.json` + Prometheus gauge `scheduling_booking_anomalies_detected` | `aggregate/` (last 8 days) |
| on request | `SlotOptimizationService` | Redis `slot-opt:{tenantId}` (24h TTL) | `by-resource/` |

Base path configurable via `app.memory.booking-patterns-path`
(env `BOOKING_PATTERNS_PATH`); default `docs/memory/booking-patterns`.

## Directory layout

```
booking-patterns/
├── README.md                       ← this file
├── by-resource/{resourceId}.json       BookingPatternRecord[] (merged across dates)
├── by-service-type/{serviceTypeId}.json BookingPatternRecord[] (merged across dates)
├── aggregate/summary-{YYYY-MM-DD}.json  BookingPatternRecord[] (one snapshot per date)
├── peak-windows.json                    PeakWindowRecord[]
└── anomalies.json                       AnomalyRecord[]
```

## `BookingPatternRecord` schema

All keys camelCase, generic domain terms only (never industry-specific).

| Key | Type | Meaning |
|---|---|---|
| `resourceId` | UUID string | Bookable resource |
| `tenantId` | UUID string | Owning tenant (every record is tenant-scoped) |
| `serviceTypeId` | UUID string \| null | Service type of the confirmed bookings |
| `dayOfWeek` | int | ISO: 1 = Monday … 7 = Sunday (of the booking `slotStart`) |
| `hourOfDay` | int | 0–23 UTC |
| `bookingCount` | long | Confirmed bookings in this bucket on the ingested date |
| `utilization` | double | `bookingCount / assumedSlotsPerHour`, clamped to 1.0 (`app.analytics.assumed-slots-per-hour`, default 4 — slots are never stored, ADR-001) |
| `updatedAt` | ISO-8601 instant | Timestamp of the nightly run that wrote the record; its date component identifies the ingested date |

Per-resource / per-service-type files are **merged** nightly: new records
replace previous records in the same `(tenantId, serviceTypeId, dayOfWeek,
hourOfDay)` bucket, everything else is retained — after 7 consecutive runs a
file covers ≥ 7 distinct `updatedAt` dates (ATOM-ANALYTICS-001 AC-03).

## `PeakWindowRecord` schema (`peak-windows.json`)

| Key | Type | Meaning |
|---|---|---|
| `resourceId` | UUID string | Resource with peak demand |
| `dayOfWeek` | int | ISO 1–7 |
| `hourOfDay` | int | 0–23 UTC |
| `bookingCount` | long | Bookings in the peak window |
| `confidenceScore` | double | `bookingCount / 30-day mean` (peak ⇒ > 1.5) |
| `detectedAt` | ISO-8601 instant | Detection run timestamp |

## `AnomalyRecord` schema (`anomalies.json`)

| Key | Type | Meaning |
|---|---|---|
| `resourceId` | UUID string | Affected resource |
| `tenantId` | UUID string | Owning tenant |
| `dropPercent` | double | 0–100; anomaly ⇒ > 80 |
| `sevenDayAvg` | double | Average daily confirmed bookings over the 7-day baseline |
| `yesterday` | long | Confirmed bookings on the most recent ingested day |
| `detectedAt` | ISO-8601 instant | Detection run timestamp |
