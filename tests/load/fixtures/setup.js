// [TASK: ATOM-PERF-501 / ATOM-PERF-502]
// Shared k6 fixture helpers: tenant/location/resource seeding + randomisers.
// Generic domain terms ONLY (resource, service-type, location) — never
// industry-specific identifiers (doctor, patient, vehicle, room ...).
//
// Two seeding modes:
//   1. Pre-seeded (default in CI): pass fixture IDs via __ENV so the load
//      phase never pays the seeding cost. Set SEED_MODE=env.
//   2. API-seeded: call the admin CRUD endpoints from setup() to create
//      10 tenants x 5 locations x 3 resources. Set SEED_MODE=api and provide
//      ADMIN_TOKEN with tenant-admin scope.
//
// setup() returns:
//   { tenants: [ { tenantId, locationId, resourceIds[], serviceTypeId, token } ] }

import http from 'k6/http';

const API_URL = __ENV.API_URL || 'http://localhost:8080';
const SEED_MODE = __ENV.SEED_MODE || 'env';

// ---- randomisers ---------------------------------------------------------

export function randomFutureDate() {
  const d = new Date();
  d.setDate(d.getDate() + Math.floor(Math.random() * 30) + 1);
  return d.toISOString().split('T')[0];
}

export function randomResource(resourceIds) {
  return resourceIds[Math.floor(Math.random() * resourceIds.length)];
}

export function pickTenant(data) {
  return data.tenants[Math.floor(Math.random() * data.tenants.length)];
}

// ---- fixture loading -----------------------------------------------------

// Mode 1 — fixtures supplied as JSON via __ENV.FIXTURES_JSON (pre-seeded).
function loadFromEnv() {
  if (__ENV.FIXTURES_JSON) {
    return JSON.parse(__ENV.FIXTURES_JSON);
  }
  // Minimal single-tenant fallback for a local smoke run. All IDs must be
  // seeded out-of-band (Flyway seed data or the api mode below).
  return {
    tenants: [
      {
        tenantId: __ENV.TENANT_ID,
        locationId: __ENV.LOCATION_ID,
        resourceIds: (__ENV.RESOURCE_IDS || '').split(',').filter(Boolean),
        serviceTypeId: __ENV.SERVICE_TYPE_ID,
        token: __ENV.JWT_TOKEN,
      },
    ],
  };
}

// Mode 2 — seed 10 tenants x 5 locations x 3 resources through the API.
function loadFromApi() {
  const adminToken = __ENV.ADMIN_TOKEN;
  const headers = {
    Authorization: `Bearer ${adminToken}`,
    'Content-Type': 'application/json',
  };
  const tenants = [];

  for (let t = 0; t < 10; t++) {
    const tenantRes = http.post(
      `${API_URL}/api/v1/tenants`,
      JSON.stringify({ name: `load-tenant-${t}`, slug: `load-tenant-${t}` }),
      { headers }
    );
    if (tenantRes.status !== 201) continue;
    const tenantId = JSON.parse(tenantRes.body).id;

    const serviceTypeRes = http.post(
      `${API_URL}/api/v1/tenants/${tenantId}/service-types`,
      JSON.stringify({
        name: `service-type-${t}`,
        durationMinutes: 30,
        bufferBeforeMin: 0,
        bufferAfterMin: 15,
        allowedResourceTypes: ['GENERAL'],
      }),
      { headers }
    );
    const serviceTypeId =
      serviceTypeRes.status === 201 ? JSON.parse(serviceTypeRes.body).id : null;

    // Only the first location/resource set per tenant is exercised by the
    // read path; the remaining four locations exist to widen the key-space.
    let firstLocationId = null;
    const resourceIds = [];
    for (let l = 0; l < 5; l++) {
      const locRes = http.post(
        `${API_URL}/api/v1/tenants/${tenantId}/locations`,
        JSON.stringify({
          name: `location-${t}-${l}`,
          addressLine1: '1 Load Way',
          city: 'Loadville',
          postalCode: '00000',
          countryCode: 'US',
          timezone: 'UTC',
        }),
        { headers }
      );
      if (locRes.status !== 201) continue;
      const locationId = JSON.parse(locRes.body).id;
      if (!firstLocationId) firstLocationId = locationId;

      for (let r = 0; r < 3; r++) {
        const resRes = http.post(
          `${API_URL}/api/v1/tenants/${tenantId}/locations/${locationId}/resources`,
          JSON.stringify({
            name: `resource-${t}-${l}-${r}`,
            resourceType: 'GENERAL',
          }),
          { headers }
        );
        if (resRes.status === 201 && l === 0) {
          resourceIds.push(JSON.parse(resRes.body).id);
        }
      }
    }

    tenants.push({
      tenantId,
      locationId: firstLocationId,
      resourceIds,
      serviceTypeId,
      token: adminToken,
    });
  }

  return { tenants };
}

export function loadTestFixtures() {
  return SEED_MODE === 'api' ? loadFromApi() : loadFromEnv();
}
