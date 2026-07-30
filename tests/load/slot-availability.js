// [TASK: ATOM-PERF-501]
// k6 load test — slot availability endpoint. Validates NFR-1.2 (p99 < 300ms)
// and NFR-1.1 (500 RPS sustained). Read-path only: JWT -> SlotController ->
// SlotCalculatorService (Redis-cached schedule/holiday reads, ADR-001).
//
// Run:
//   k6 run \
//     --env API_URL=http://localhost:8080 \
//     --env SEED_MODE=api --env ADMIN_TOKEN=<tenant-admin-jwt> \
//     tests/load/slot-availability.js \
//     --out json=tests/load/results/slot-availability-$(date +%Y%m%d).json
//
// Removing or weakening http_req_duration p(99)<300 is PROHIBITED — it is the
// NFR-1.2 sign-off gate.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Rate } from 'k6/metrics';
import { loadTestFixtures, randomResource, randomFutureDate, pickTenant } from './fixtures/setup.js';

const slotLatency = new Trend('slot_latency', true);
const errorRate = new Rate('error_rate');

export const options = {
  stages: [
    { duration: '30s', target: 100 }, // ramp up
    { duration: '2m', target: 500 },  // sustain at 500 RPS  — NFR-1.1 gate
    { duration: '30s', target: 0 },   // ramp down
  ],
  thresholds: {
    http_req_duration: ['p(99)<300'], // NFR-1.2 gate — BLOCKS if breached
    http_req_failed: ['rate<0.01'],   // < 1% error rate
    slot_latency: ['p(99)<300'],      // custom Trend metric
    error_rate: ['rate<0.01'],
    checks: ['rate>0.99'],
  },
};

export function setup() {
  return loadTestFixtures();
}

export default function (data) {
  const tenant = pickTenant(data);
  const params = {
    headers: { Authorization: `Bearer ${tenant.token}` },
    tags: { endpoint: 'slot-availability' },
  };

  const url =
    `${__ENV.API_URL}/api/v1/tenants/${tenant.tenantId}/slots` +
    `?locationId=${tenant.locationId}` +
    `&resourceId=${randomResource(tenant.resourceIds)}` +
    `&serviceTypeId=${tenant.serviceTypeId}` +
    `&date=${randomFutureDate()}`;

  const res = http.get(url, params);
  slotLatency.add(res.timings.duration);
  errorRate.add(res.status >= 400);

  check(res, {
    'status is 200': (r) => r.status === 200,
    'has slots key': (r) => {
      try {
        return JSON.parse(r.body).slots !== undefined;
      } catch (_e) {
        return false;
      }
    },
  });

  sleep(0.002); // ~2ms think time
}
