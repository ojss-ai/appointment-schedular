# ADR-001 — On-Demand Slot Generation (No Stored Slots)

**Status:** Accepted
**Date:** 2026-06-18
**Deciders:** Architecture Lead (Suraj)
**adr-docs agent:** auto-captured

---

## Context

The scheduling engine must present available time slots to end users. The naive implementation is to pre-compute and store all available slots as rows in a `slots` table, which is then queried at booking time.

We need to decide whether slots are:
- (A) Pre-computed and stored as database rows, OR
- (B) Computed on-demand at query time from raw schedule and booking data

---

## Decision

**Option B — Slots are computed entirely on demand. No `slots` table exists.**

The `SlotCalculatorService` derives availability at query time by:
1. Loading the resource's weekly schedule (base operating hours)
2. Loading break patterns for the requested day
3. Loading branch holidays for the date range
4. Subtracting all existing `CONFIRMED` and `PENDING_HOLD` bookings (including their buffer windows)
5. Returning the remaining open intervals as a transient slot list

---

## Rationale

| Concern | Stored slots | On-demand computation |
|---|---|---|
| Stale data | High risk — stored slots can be stale if a booking is made concurrently | No staleness — computed from live booking records |
| Race conditions | Two users can book same stored slot if locking is missed | Lock applied at booking write; computation is read-only |
| Schema changes | Slot schema must evolve when service rules change | No slot schema; only booking records exist |
| Storage cost | O(resources × days × time-granularity) — grows unbounded | Zero — no slot rows |
| Complexity | Requires a slot generation job + sync mechanism | Single service class |
| Performance | Fast read (row lookup) | Must be optimized (index + cache) |

The performance concern of on-demand computation is addressed by:
- Compound B-tree index on `(tenant_id, location_id, slot_start)` covering active bookings (NFR-1.3)
- Redis caching of resource schedules and branch holidays (5-min TTL)
- Only booking data fetched fresh on every request (small dataset per resource per day)

Target: p99 < 300ms (NFR-1.2). Redis cache added to operating matrix if p99 exceeds 250ms in load testing.

---

## Consequences

- Positive: No slot-sync jobs, no stale-slot bugs, simpler schema
- Positive: Adding new service rules (e.g., new buffer type) only changes `SlotCalculatorService`, not stored data
- Negative: Slightly more compute per slot-availability request vs. a direct row lookup
- Mitigation: Redis caching for the static parts (schedule, holidays); tight index on booking query

---

## Alternatives Considered

**Option A (stored slots):** Rejected. The operational complexity of keeping a slot table in sync with bookings, breaks, and schedule changes outweighs the read-performance benefit. Pre-computed slots also require a full regeneration job when any schedule parameter changes.

---

## Review Trigger

Revisit this ADR if load testing shows slot computation p99 consistently exceeds 250ms after Redis caching is applied. In that case, a hybrid approach (cache warm-up for high-demand resources) may be considered.
