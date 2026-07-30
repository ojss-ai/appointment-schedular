---
description: k6 load test for slot availability endpoint — NFR-1.2 gate (p99 < 300ms at 500 RPS)
---

# ATOM-PERF-501: k6 Load Test — Slot Availability Endpoint (500 RPS)

**Status**: ✅ Complete
**Feature**: perf-load-testing
**Phase**: 5 (Production)
**Tags**: [TEST] [PERF]
**Complexity**: Medium
**Agent**: testgen
**Dependencies**: Phase 2 atom-08 — Redis caching active on SlotCalculatorService
**Blocks**: ATOM-PERF-503 (Redis warm-up atom — triggered only if this test fails p99 > 250ms)
**PR**: TBD

---

## Overview

This atom writes and executes the k6 load test that validates the slot availability endpoint against NFR-1.2: p99 latency < 300ms sustained at 500 RPS. The test ramps from 0 to 500 virtual users over 30 seconds, holds load for 2 minutes, then ramps down. Redis cache must be active before running; the test doubles as a cache hit-rate validator. Failing this test gate blocks Phase 5 sign-off and triggers ATOM-PERF-503 (cache warm-up).

---

## User Story

```
As a System
I want the slot availability endpoint verified at 500 RPS under sustained load
So that NFR-1.2 (p99 < 300ms) is confirmed before production deployment
```

---

## Acceptance Criteria

- [ ] **AC-01**: k6 process exits with code 0 — all thresholds met (`http_req_duration p(99)<300`, `http_req_failed rate<0.01`, `slot_latency p(99)<300`)
- [ ] **AC-02**: 500 RPS sustained for 2 minutes with p99 latency < 300ms
- [ ] **AC-03**: Redis cache hit rate > 80% (verified via `redis-cli INFO stats | grep keyspace_hits` during test)
- [ ] **AC-04**: Error rate < 1% across the entire test run
- [ ] **AC-05**: No OOM errors on Spring Boot API instances — JVM heap monitored during test
- [ ] **AC-06**: Test results saved to `tests/load/results/slot-availability-{date}.json`
- [ ] **AC-07 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) in any endpoint path or fixture data identifier

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `tests/load/slot-availability.js` — k6 thresholds block | `tests/load/slot-availability.js` | 🔜 Planned |
| AC-02 | `tests/load/slot-availability.js` — stages config | `tests/load/slot-availability.js` | 🔜 Planned |
| AC-03 | Manual: `redis-cli INFO stats` during test | Redis metrics | 🔜 Planned |
| AC-04 | `tests/load/slot-availability.js` — `error_rate` threshold | `tests/load/slot-availability.js` | 🔜 Planned |
| AC-05 | JVM heap monitor (JConsole / Actuator `/metrics`) | Spring Boot Actuator | 🔜 Planned |
| AC-06 | `k6 run --out json=...` flag | CLI invocation | 🔜 Planned |

<!-- AC validation passed: TBD, 6 criteria mapped, all TBD -->

---

## Technical Design

### Architecture

The load test is a pure k6 script with no server-side changes. It exercises the read-path: JWT → ALB → Spring Boot → SlotCalculatorService (Redis @Cacheable) → on-demand slot computation. The test seeds 10 tenants × 5 locations × 3 resources via a `setup()` function that calls the API before the load phase begins. Slot computation is never stored (ADR-001); all results come from in-flight computation or Redis cache.

### Data Flow / Sequence

```
k6 VU → GET /api/v1/tenants/{tenantId}/slots?locationId=...&resourceId=...&date=...
  → Spring Security (JWT validation)
  → SlotController.getAvailableSlots()
  → SlotCalculatorService.computeAvailableSlots()    [Redis @Cacheable]
  → if cache miss: OperatingMatrixService + BookingRepository
  → JSON response: { slots: [...] }
  → k6 check: status==200, body.slots defined
  → k6 metrics: slotLatency.add(), errorRate.add()
```

### File Structure

```
tests/
└── load/
    ├── slot-availability.js       ← k6 main script
    ├── fixtures/
    │   └── setup.js               ← seed 10 tenants × 5 locations × 3 resources
    ├── results/
    │   └── slot-availability-{date}.json   ← k6 JSON output
    └── README.md                  ← install, seed, run, interpret instructions
```

### Interface Contracts

```javascript
// k6 options object shape
export const options = {
  stages: [
    { duration: '30s', target: 100 },   // ramp up
    { duration: '2m',  target: 500 },   // sustain at 500 RPS  — NFR-1.1 gate
    { duration: '30s', target: 0   },   // ramp down
  ],
  thresholds: {
    http_req_duration: ['p(99)<300'],   // NFR-1.2 gate — BLOCKS if breached
    http_req_failed:   ['rate<0.01'],   // < 1% error rate
    slot_latency:      ['p(99)<300'],   // custom Trend metric
  },
}

// setup() return shape
// { tenantId: string, locationId: string, resourceIds: string[], serviceTypeId: string, token: string }
```

### Design Rationale

- **ADR-001 (no slots table)**: Slot availability is computed on demand by `SlotCalculatorService`; this test verifies that computation + Redis cache can sustain NFR-1.2 at peak load. There is nothing to pre-compute or batch.
- **NFR-1.2 context**: The 300ms p99 gate exists because slot availability is the highest-frequency read in the system. At 500 RPS with mixed cache hit/miss ratio, cold computation must still stay within budget.
- **Why k6 (not JMeter/Gatling)**: k6 scripts are JavaScript, version-controlled alongside the codebase, and integrate cleanly into GitHub Actions as a binary download.

---

## Test Strategy

**Test type**: Load (k6)

```
- shouldMeetNFR12_p99Under300ms_at500RPS:
    Given: 10 tenants seeded, Redis warm, API running at localhost:8080
    Assert: k6 exits 0; results JSON shows p(99) < 300ms over 2-minute sustain window

- shouldMaintainErrorRateBelow1Percent:
    Given: 500 concurrent VUs hitting randomised tenant/location/resource combos
    Assert: http_req_failed rate < 0.01 throughout test

- shouldHitRedisCacheAbove80Percent:
    Given: same set of resources queried repeatedly across VUs
    Assert: redis-cli keyspace_hits / (keyspace_hits + keyspace_misses) > 0.80

- shouldNotOOMApiProcess:
    Given: sustained 500 RPS for 2 minutes
    Assert: JVM heap usage (via /actuator/metrics/jvm.memory.used) never triggers GC pause > 500ms
```

**Coverage requirements**:
- No line coverage requirement — this is a black-box load test
- Must be runnable in CI as an optional gate (k6 installed via GitHub Actions step)

---

## Implementation Constraints

- `thresholds` must include `http_req_duration: ['p(99)<300']` — NFR-1.2; removing or weakening this threshold is prohibited
- Test fixtures must use generic domain terms: `resource`, `service-type`, `location` — never `doctor`, `vehicle`, `room`
- All test JWT tokens generated in `setup()` — never hardcoded in script body
- `k6 run --out json=...` flag is mandatory so results are persisted for audit
- No `console.log` in fixture scripts; use `k6/html` report or structured k6 summary
- All API calls go through `__ENV.API_URL` — never a hardcoded host

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `tests/load/slot-availability.js` with full options/thresholds block
2. Run against a cold API (no Redis warm-up) — confirm p99 threshold breaches and k6 exits non-zero
3. Confirm results JSON is written to `tests/load/results/`

### GREEN — Minimum code to pass

1. Ensure Redis @Cacheable is active on `SlotCalculatorService` (P2 dependency)
2. Seed test fixtures via `setup()` — 10 tenants, 5 locations, 3 resources each
3. Run full test — verify k6 exits 0 with all thresholds green

### REFACTOR — Quality pass

1. Extract helper functions (`randomFutureDate`, `randomResource`, `loadTestFixtures`) to `fixtures/setup.js`
2. Write `tests/load/README.md` with install → seed → run → interpret → remediation steps
3. Add GitHub Actions step to run test in CI (optional gate — human reviews results)

---

## Implementation Reference

### k6 Load Test Script

**File**: `tests/load/slot-availability.js`

```javascript
// [TASK: ATOM-PERF-501]
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Trend, Rate } from 'k6/metrics'

const slotLatency = new Trend('slot_latency', true)
const errorRate   = new Rate('error_rate')

export const options = {
  stages: [
    { duration: '30s', target: 100 },   // ramp up
    { duration: '2m',  target: 500 },   // sustain at 500 RPS
    { duration: '30s', target: 0   },   // ramp down
  ],
  thresholds: {
    http_req_duration: ['p(99)<300'],   // NFR-1.2 gate
    http_req_failed:   ['rate<0.01'],   // < 1% error rate
    slot_latency:      ['p(99)<300'],
  },
}

// Test data: pre-seed via setup()
export function setup() {
  // Returns { tenantId, locationId, resourceId, serviceTypeId, token }
  // Seed via API calls to create 10 tenants × 5 locations × 3 resources
  return loadTestFixtures()
}

export default function (data) {
  const params = {
    headers: { Authorization: `Bearer ${data.token}` },
  }
  const url = `${__ENV.API_URL}/api/v1/tenants/${data.tenantId}/slots` +
    `?locationId=${data.locationId}` +
    `&resourceId=${randomResource(data.resourceIds)}` +
    `&serviceTypeId=${data.serviceTypeId}` +
    `&date=${randomFutureDate()}`

  const res = http.get(url, params)
  slotLatency.add(res.timings.duration)
  errorRate.add(res.status >= 400)

  check(res, {
    'status is 200': r => r.status === 200,
    'has slots key': r => JSON.parse(r.body).slots !== undefined,
  })
  sleep(0.002)   // 2ms think time = ~500 RPS per VU
}

function randomFutureDate() {
  const d = new Date()
  d.setDate(d.getDate() + Math.floor(Math.random() * 30) + 1)
  return d.toISOString().split('T')[0]
}
```

### Run Command

**File**: `tests/load/README.md` (run section)

```bash
# From repo root
k6 run \
  --env API_URL=http://localhost:8080 \
  tests/load/slot-availability.js \
  --out json=tests/load/results/slot-availability-$(date +%Y%m%d).json
```

---

## Integration Points

**Depends on**: Phase 2 atom-08 (Redis caching on SlotCalculatorService must be active); Spring Boot API running with seeded tenant fixtures

**Enables**: ATOM-PERF-503 (Redis cache warm-up — triggered only if p99 > 250ms); Phase 5 sign-off gate requires this test to pass

**NFR Gates satisfied**: NFR-1.1 (500 concurrent transactions/min), NFR-1.2 (slot generation < 300ms p99)

**Cascading updates required**:
- `tasks/MASTER-TASK-LIST.md` — mark atom complete and record p99 result
- `tests/load/README.md` — document run results and any remediation taken

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `tests/load/slot-availability.js` | New | k6 load test script — 500 RPS slot availability |
| `tests/load/fixtures/setup.js` | New | Fixture seeding: 10 tenants × 5 locations × 3 resources |
| `tests/load/results/` | New (dir) | Persisted JSON results from each run |
| `tests/load/README.md` | New | Install, seed, run, interpret, remediation guide |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] k6 exits with code 0 (all thresholds pass)
- [ ] Results JSON saved to `tests/load/results/slot-availability-{date}.json`
- [ ] Redis cache hit rate > 80% confirmed during test
- [ ] No OOM errors observed during 2-minute sustain window
- [ ] `tests/load/README.md` written with full run instructions
- [ ] Zero industry-specific terms in any fixture identifier or endpoint path
- [ ] NFR-1.2 gate confirmed (p99 < 300ms documented in results)
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: perf-load-testing | Phase: 5*
