// [TASK: ATOM-PERF-502]
// k6 load test — booking checkout (hold + confirm). Validates NFR-1.1
// (500 concurrent transactions/min, zero double-bookings) under two arrival
// profiles:
//   distributed  — 450/min across different slots (throughput / happy path)
//   concentrated —  50/min fighting for the same 5 slots (lock contention)
//
// 409 CONFLICT from losing VUs in the concentrated scenario is CORRECT
// behaviour (ADR-002 pessimistic lock). Zero double-bookings is the
// correctness invariant, asserted post-run by verify-no-double-bookings.sh.
//
// Run:
//   k6 run \
//     --env API_URL=http://localhost:8080 \
//     --env FIXTURES_JSON="$(cat fixtures.json)" \
//     tests/load/booking-checkout.js \
//     --out json=tests/load/results/booking-checkout-$(date +%Y%m%d).json
//   ./tests/load/verify-no-double-bookings.sh   # MUST return 0 rows

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Counter } from 'k6/metrics';
import { loadTestFixtures, pickTenant } from './fixtures/setup.js';

const holdConflicts = new Counter('hold_conflicts');
const confirmed = new Counter('bookings_confirmed');

export const options = {
  scenarios: {
    distributed: {
      executor: 'constant-arrival-rate',
      rate: 450, // 450 iterations/min across different slots
      timeUnit: '1m',
      duration: '2m',
      preAllocatedVUs: 500,
      exec: 'distributed',
    },
    concentrated: {
      executor: 'constant-arrival-rate',
      rate: 50, // 50 iterations/min competing for the same 5 slots
      timeUnit: '1m',
      duration: '2m',
      preAllocatedVUs: 60,
      exec: 'concentrated',
    },
  },
  thresholds: {
    // NFR-1.2 still applies to any slot query made during checkout.
    'http_req_duration{scenario:distributed}': ['p(99)<500'],
    'http_req_failed{scenario:concentrated}': ['rate<0.6'], // ~50% expected 409s
    checks: ['rate>0.99'],
  },
};

// Five fixed slot offsets (hours from tomorrow 08:00 UTC) reused by the
// concentrated scenario to force lock contention. Configurable, not hardcoded
// per-run IDs.
const CONTENDED_SLOT_HOURS = [8, 9, 10, 11, 12];

export function setup() {
  return loadTestFixtures();
}

function headersFor(tenant) {
  return {
    Authorization: `Bearer ${tenant.token}`,
    'Content-Type': 'application/json',
  };
}

function slotStartFor(hourOffset, dayOffset) {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + dayOffset);
  d.setUTCHours(hourOffset, 0, 0, 0);
  return d.toISOString();
}

function checkout(tenant, slotStart, scenarioTag) {
  const headers = headersFor(tenant);
  group('hold', () => {
    const holdRes = http.post(
      `${__ENV.API_URL}/api/v1/tenants/${tenant.tenantId}/bookings/hold`,
      JSON.stringify({
        resourceId: tenant.resourceIds[0],
        serviceTypeId: tenant.serviceTypeId,
        slotStart,
      }),
      { headers, tags: { scenario: scenarioTag } }
    );

    check(holdRes, {
      'hold is 201 or 409': (r) => r.status === 201 || r.status === 409,
    });

    if (holdRes.status === 409) {
      holdConflicts.add(1);
      return; // expected under contention
    }
    if (holdRes.status !== 201) return;

    const { bookingId } = JSON.parse(holdRes.body);
    group('confirm', () => {
      const confirmRes = http.post(
        `${__ENV.API_URL}/api/v1/tenants/${tenant.tenantId}/bookings/${bookingId}/confirm`,
        JSON.stringify({ extensionData: { notes: 'k6 load test' } }),
        { headers, tags: { scenario: scenarioTag } }
      );
      check(confirmRes, { confirmed: (r) => r.status === 200 });
      if (confirmRes.status === 200) confirmed.add(1);
    });
  });
}

// Each iteration targets a unique future slot — minimal lock contention.
export function distributed(data) {
  const tenant = pickTenant(data);
  const dayOffset = 1 + Math.floor(Math.random() * 20);
  const hour = 8 + Math.floor(Math.random() * 9); // 08:00–16:00
  checkout(tenant, slotStartFor(hour, dayOffset), 'distributed');
  sleep(0.1);
}

// All iterations funnel onto the same 5 slots for one tenant — max contention.
export function concentrated(data) {
  const tenant = data.tenants[0];
  const hour = CONTENDED_SLOT_HOURS[Math.floor(Math.random() * CONTENDED_SLOT_HOURS.length)];
  checkout(tenant, slotStartFor(hour, 1), 'concentrated');
  sleep(0.1);
}
