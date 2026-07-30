# Load Tests (k6)

Phase 5 performance validation for the Multi-Tenant Scheduling Framework.
Two black-box k6 scripts encode the non-negotiable NFR gates from `CLAUDE.md`.

| Script | Gate | Threshold (must not weaken) |
|---|---|---|
| `slot-availability.js` | NFR-1.2 slot generation p99 | `http_req_duration: p(99)<300` at 500 RPS |
| `booking-checkout.js` | NFR-1.1 500 txn/min, 0 double-bookings | `p(99)<500` distributed + `verify-no-double-bookings.sh` = 0 rows |

## Prerequisites

- [k6](https://k6.io/docs/get-started/installation/) installed (or run the CI step which downloads the binary).
- API running and reachable at `API_URL` (default `http://localhost:8080`), with Redis caching active (Phase 2 atom-08) and PostgreSQL up.
- Fixtures seeded (see below).

## Fixtures

Both scripts share `fixtures/setup.js`. Two seeding modes:

- **`SEED_MODE=env`** (default, recommended for CI): pass pre-seeded IDs so the
  load phase never pays the seeding cost.

  ```bash
  export FIXTURES_JSON='{"tenants":[{"tenantId":"...","locationId":"...","resourceIds":["..."],"serviceTypeId":"...","token":"<jwt>"}]}'
  ```

  A single-tenant fallback also reads `TENANT_ID`, `LOCATION_ID`,
  `RESOURCE_IDS` (comma-separated), `SERVICE_TYPE_ID`, `JWT_TOKEN`.

- **`SEED_MODE=api`**: seed 10 tenants x 5 locations x 3 resources through the
  admin CRUD API. Requires `ADMIN_TOKEN` with tenant-admin scope.

  ```bash
  export SEED_MODE=api ADMIN_TOKEN=<tenant-admin-jwt>
  ```

All fixture identifiers use generic domain terms (`resource`, `service-type`,
`location`) — never industry-specific terms.

## Running

### Slot availability (NFR-1.2)

```bash
mkdir -p tests/load/results
k6 run \
  --env API_URL=http://localhost:8080 \
  tests/load/slot-availability.js \
  --out json=tests/load/results/slot-availability-$(date +%Y%m%d).json
```

Ramps 0->100 (30s), holds 500 RPS (2m), ramps down (30s). k6 exits non-zero if
p99 >= 300ms or error rate >= 1%.

### Booking checkout (NFR-1.1)

```bash
k6 run \
  --env API_URL=http://localhost:8080 \
  tests/load/booking-checkout.js \
  --out json=tests/load/results/booking-checkout-$(date +%Y%m%d).json

# MANDATORY correctness gate immediately after the run:
./tests/load/verify-no-double-bookings.sh    # must print PASS / exit 0
```

Runs `distributed` (450/min, different slots) and `concentrated` (50/min, same
5 slots) concurrently. ~50% 409 CONFLICT responses in the concentrated scenario
are expected and correct (ADR-002 pessimistic lock).

## Interpreting results & side checks

- **Redis cache hit rate (target > 80%)** during the slot run:
  `redis-cli INFO stats | grep -E 'keyspace_hits|keyspace_misses'`
- **JVM heap / no OOM**: watch `curl -s localhost:8080/actuator/metrics/jvm.memory.used`.
- **Kafka consumer lag returns to 0 within 2 min** post booking run:
  `kafka-consumer-groups.sh --bootstrap-server <broker> --describe --group notification-consumers`
- **PENDING_HOLD cleared within 11 min** post booking run:
  `SELECT COUNT(*) FROM bookings WHERE status='PENDING_HOLD' AND hold_expires_at < NOW();` -> 0

## Remediation

If `slot-availability.js` reports **p99 > 250ms**, activate
**ATOM-PERF-503 — Redis cache warm-up** (`CacheWarmUpService`): it pre-populates
the operating-matrix cache for the top-20% most-booked resources on startup and
every 30 minutes. Enable with `app.cache.warmup.enabled=true`, redeploy, and
re-run this script to confirm p99 < 300ms.

## Results log

| Date | Script | p99 | Error rate | Double-bookings | Warm-up needed? |
|---|---|---|---|---|---|
| _pending first run_ | slot-availability | — | — | n/a | TBD |
| _pending first run_ | booking-checkout | — | — | 0 (expected) | n/a |
