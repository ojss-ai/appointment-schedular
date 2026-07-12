---
description: k6 load test for concurrent booking checkout — NFR-1.1 gate (500 transactions/min, 0 double-bookings)
---

# ATOM-PERF-502: k6 Load Test — Concurrent Booking Checkout (500 req/min)

**Status**: 🟡 Planned
**Feature**: perf-load-testing
**Phase**: 5 (Production)
**Tags**: [TEST] [PERF]
**Complexity**: Medium
**Agent**: testgen
**Dependencies**: Phase 2 atom-13 — concurrency tests passing; ATOM-PERF-501 (slot availability test complete)
**Blocks**: None
**PR**: TBD

---

## Overview

This atom writes and executes the k6 load test that validates the full booking checkout path (hold + confirm) against NFR-1.1: 500 concurrent transactions/minute with zero double-bookings. The test runs two concurrent scenarios — `distributed` (450 VUs across different slots) and `concentrated` (50 VUs competing for the same 5 slots) — to verify both throughput and pessimistic locking correctness under contention. A post-test SQL verification script confirms zero double-bookings in the database.

---

## User Story

```
As a System
I want the booking checkout path (hold + confirm) verified at 500 concurrent transactions/min
So that NFR-1.1 (zero double-bookings under concurrency) is confirmed before production deployment
```

---

## Acceptance Criteria

- [ ] **AC-01**: 500 total transactions/min (distributed + concentrated scenarios combined) sustained for 2 minutes
- [ ] **AC-02**: 0 double-bookings for any (resource_id, slot_start) pair — post-test SQL query returns 0 rows
- [ ] **AC-03**: `http_req_duration{scenario:distributed}` p99 < 500ms
- [ ] **AC-04**: `http_req_failed{scenario:concentrated}` rate < 0.60 (approximately 50% expected 409 Conflict responses are acceptable)
- [ ] **AC-05**: GC scheduler clears all expired PENDING_HOLD records within 11 minutes of test end
- [ ] **AC-06**: Kafka consumer lag returns to 0 within 2 minutes of test end
- [ ] **AC-07**: Test results saved to `tests/load/results/booking-checkout-{date}.json`
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms in fixture data, endpoint paths, or verification scripts

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `tests/load/booking-checkout.js` — scenarios config | k6 arrival rate executor | 🔜 Planned |
| AC-02 | `tests/load/verify-no-double-bookings.sh` — SQL query | `bookings` table | 🔜 Planned |
| AC-03 | `tests/load/booking-checkout.js` — thresholds block | k6 metrics | 🔜 Planned |
| AC-04 | `tests/load/booking-checkout.js` — concentrated scenario threshold | k6 metrics | 🔜 Planned |
| AC-05 | Manual: query `bookings WHERE status='PENDING_HOLD'` 11 min post-test | `HoldGarbageCollector` | 🔜 Planned |
| AC-06 | Manual: Kafka consumer lag via `kafka-consumer-groups.sh --describe` | MSK / local Kafka | 🔜 Planned |
| AC-07 | `k6 run --out json=...` flag | CLI invocation | 🔜 Planned |

<!-- AC validation passed: TBD, 7 criteria mapped, all TBD -->

---

## Technical Design

### Architecture

The test exercises the stateful write path: JWT → hold endpoint (pessimistic lock via `SELECT FOR UPDATE`) → 10-minute PENDING_HOLD state → confirm endpoint → CONFIRMED state + outbox event. The `concentrated` scenario deliberately drives lock contention to verify that ADR-002 (pessimistic lock) prevents double-bookings rather than producing them. A post-test Bash/SQL script reads directly from PostgreSQL to assert zero double-bookings — this is the definitive correctness gate.

### Data Flow / Sequence

```
k6 VU (distributed) → POST /api/v1/tenants/{tenantId}/bookings/hold
  → BookingService.holdSlot()                      [@Transactional]
  → SELECT ... FOR UPDATE on bookings (ADR-002)
  → INSERT booking (status=PENDING_HOLD)
  → outboxService.writeBookingEvent()              [same transaction]
  → DB commit
  → 201 Created { bookingId, holdExpiresAt }

k6 VU → POST /api/v1/tenants/{tenantId}/bookings/{bookingId}/confirm
  → BookingService.confirmBooking()                [@Transactional]
  → UPDATE booking SET status=CONFIRMED
  → outboxService.writeBookingEvent()
  → DB commit
  → 200 OK

k6 VU (concentrated, losing lock) → 409 Conflict   [expected]

Post-test → verify-no-double-bookings.sh
  → SELECT resource_id, slot_start, COUNT(*) FROM bookings
     WHERE status='CONFIRMED' GROUP BY ... HAVING COUNT(*) > 1
  → Must return 0 rows
```

### File Structure

```
tests/
└── load/
    ├── booking-checkout.js              ← k6 main script (distributed + concentrated)
    ├── verify-no-double-bookings.sh     ← post-test SQL double-booking assertion
    ├── results/
    │   └── booking-checkout-{date}.json ← k6 JSON output
    └── README.md                        ← run instructions (shared with atom-01)
```

### Interface Contracts

```javascript
// k6 scenarios object shape
export const options = {
  scenarios: {
    distributed: {
      executor: 'constant-arrival-rate',
      rate: 450,                  // 450 iterations/min across different slots
      timeUnit: '1m',
      duration: '2m',
      preAllocatedVUs: 500,
    },
    concentrated: {
      executor: 'constant-arrival-rate',
      rate: 50,                   // 50 iterations/min competing for same 5 slots
      timeUnit: '1m',
      duration: '2m',
      preAllocatedVUs: 60,
    },
  },
  thresholds: {
    'http_req_duration{scenario:distributed}': ['p(99)<500'],
    'http_req_failed{scenario:concentrated}':  ['rate<0.6'],  // ~50% 409s expected
    checks: ['rate>0.99'],
  },
}
```

### Design Rationale

- **ADR-002 (pessimistic lock)**: The `concentrated` scenario is designed to trigger `SELECT FOR UPDATE` contention. 409 Conflict responses from losing VUs are correct behaviour — the test threshold `rate<0.6` accounts for this. Zero double-bookings is the correctness invariant, not zero 409s.
- **ADR-003 (outbox pattern)**: Every booking state transition writes to the `outbox` table in the same transaction. AC-06 (Kafka lag returns to 0) validates the full outbox → Debezium → Kafka pipeline under load.
- **NFR-1.1 context**: 500 concurrent transactions/minute is the production throughput target. The distributed/concentrated split ensures both the happy path and the contention path are tested at target scale.

---

## Test Strategy

**Test type**: Load (k6) + Post-test verification (bash/psql)

```
- shouldSustain500TransactionsPerMin_distributed:
    Given: 450 VUs/min across 10 tenants × randomised slots, Redis + DB running
    Assert: k6 distributed scenario p(99) < 500ms; checks rate > 0.99

- shouldProduceZeroDoubleBookings_underContention:
    Given: 50 VUs/min all targeting the same 5 slots (concentrated scenario)
    Assert: verify-no-double-bookings.sh returns 0 rows from PostgreSQL

- shouldReturnCorrect409_whenSlotAlreadyHeld:
    Given: VU holds slot; second VU attempts hold on same slot within hold window
    Assert: second VU receives 409 Conflict (not 200, not 500)

- shouldClearExpiredHolds_within11Minutes:
    Given: test completes; PENDING_HOLD records older than 10 minutes exist
    Assert: COUNT(*) WHERE status='PENDING_HOLD' AND hold_expires_at < NOW() = 0 at T+11min

- shouldDrainKafkaLag_within2Minutes:
    Given: high write throughput during test
    Assert: kafka-consumer-groups.sh shows lag = 0 within 120 seconds of test end
```

**Coverage requirements**:
- No line coverage requirement — black-box load test
- `verify-no-double-bookings.sh` must be run immediately after k6 completes

---

## Implementation Constraints

- `thresholds` must include `http_req_duration: ['p(99)<300']` for the slot availability check within the hold flow — NFR-1.2 applies to any slot query made during checkout
- `SELECT ... FOR UPDATE` is the mandatory concurrency guard (ADR-002) — never relax to optimistic locking for this path
- All booking state transitions must use `outboxService.writeBookingEvent()` within `@Transactional` scope — never write directly to Kafka
- Post-test verification (`verify-no-double-bookings.sh`) must be run as part of the CI gate — k6 exit 0 alone is insufficient
- Test fixtures must use generic domain terms (`resource`, `service-type`) — no industry-specific terms
- All JWTs generated in k6 `setup()` — never hardcoded

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `tests/load/booking-checkout.js` with both scenarios and thresholds
2. Run against API without pessimistic lock enabled — confirm double-bookings appear in `verify-no-double-bookings.sh`
3. Confirm k6 exits non-zero due to threshold violations

### GREEN — Minimum code to pass

1. Confirm `SELECT FOR UPDATE` is active in `BookingService.holdSlot()` (Phase 2 dependency)
2. Seed test fixtures (10 tenants, resources, service types, available slots)
3. Run full test — verify k6 exits 0 and `verify-no-double-bookings.sh` returns 0 rows

### REFACTOR — Quality pass

1. Parameterise slot selection in `concentrated` scenario to a configurable list (not hardcoded IDs)
2. Add Kafka lag check to CI step as a post-test assertion script
3. Document `tests/load/README.md` with concentrated vs distributed scenario explanation

---

## Implementation Reference

### k6 Load Test Script

**File**: `tests/load/booking-checkout.js`

```javascript
// [TASK: ATOM-PERF-502]
import http from 'k6/http'
import { check, sleep, group } from 'k6'

export const options = {
  scenarios: {
    distributed: {
      executor: 'constant-arrival-rate',
      rate: 450,                  // 450 VUs spread across different slots
      timeUnit: '1m',
      duration: '2m',
      preAllocatedVUs: 500,
    },
    concentrated: {
      executor: 'constant-arrival-rate',
      rate: 50,                   // 50 VUs fighting for the same 5 slots
      timeUnit: '1m',
      duration: '2m',
      preAllocatedVUs: 60,
    },
  },
  thresholds: {
    'http_req_duration{scenario:distributed}': ['p(99)<500'],
    'http_req_failed{scenario:concentrated}':  ['rate<0.6'],  // ~50% expected 409s
    checks: ['rate>0.99'],
  },
}

export default function () {
  const token = getToken()   // pre-created JWT from setup()
  const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }

  group('hold', () => {
    const holdRes = http.post(
      `${__ENV.API_URL}/api/v1/tenants/${tenantId}/bookings/hold`,
      JSON.stringify({ resourceId: pickResource(), serviceTypeId, slotStart: pickSlot() }),
      { headers }
    )
    if (holdRes.status !== 201) return  // 409 expected in concentrated scenario

    const { bookingId, holdExpiresAt } = JSON.parse(holdRes.body)

    group('confirm', () => {
      const confirmRes = http.post(
        `${__ENV.API_URL}/api/v1/tenants/${tenantId}/bookings/${bookingId}/confirm`,
        JSON.stringify({ extensionData: { notes: 'k6 load test' } }),
        { headers }
      )
      check(confirmRes, { 'confirmed': r => r.status === 200 })
    })
  })
  sleep(0.1)
}
```

### Post-Test Double-Booking Verification Script

**File**: `tests/load/verify-no-double-bookings.sh`

```bash
#!/bin/bash
# [TASK: ATOM-PERF-502]
# After load test: assert no slot has > 1 CONFIRMED booking
psql -h localhost -U scheduler -d scheduler << 'SQL'
SELECT resource_id, slot_start, COUNT(*) as count
FROM bookings
WHERE status = 'CONFIRMED'
  AND created_at > NOW() - INTERVAL '10 minutes'
GROUP BY resource_id, slot_start
HAVING COUNT(*) > 1;
SQL
# Should return 0 rows
```

---

## Integration Points

**Depends on**: Phase 2 atom-13 (concurrency tests passing — `SELECT FOR UPDATE` active); ATOM-PERF-501 (slot availability test complete); PostgreSQL + Redis + Kafka all running

**Enables**: Phase 5 sign-off gate — NFR-1.1 is confirmed by this test

**NFR Gates satisfied**: NFR-1.1 (500+ concurrent reservations/min, zero double-bookings), NFR-2.1 (Kafka consumer lag drains within 2 minutes)

**Cascading updates required**:
- `tasks/MASTER-TASK-LIST.md` — mark atom complete and record double-booking count (must be 0)
- `tests/load/README.md` — document concentrated vs distributed scenario and verification steps

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `tests/load/booking-checkout.js` | New | k6 load test — distributed + concentrated booking scenarios |
| `tests/load/verify-no-double-bookings.sh` | New | Post-test SQL assertion — zero double-bookings gate |
| `tests/load/results/` | New (dir) | Persisted JSON results (shared with atom-01) |
| `tests/load/README.md` | Modified | Add booking checkout run instructions |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] k6 exits with code 0 (all thresholds pass)
- [ ] `verify-no-double-bookings.sh` returns 0 rows after test
- [ ] Results JSON saved to `tests/load/results/booking-checkout-{date}.json`
- [ ] Kafka consumer lag confirmed at 0 within 2 minutes post-test
- [ ] PENDING_HOLD records cleared within 11 minutes post-test
- [ ] Zero industry-specific terms in any fixture identifier or endpoint path
- [ ] NFR-1.1 gate confirmed (500 concurrent transactions/min, 0 double-bookings)
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: perf-load-testing | Phase: 5*
