# Phase 5 — Scale, Security Hardening, and Production
**Duration:** Weeks 14–16
**Milestone:** All NFR gates passed; production infrastructure live; CI/CD pipeline green

---

## P5-T01 — k6 Load Test — Slot Availability Endpoint (500 RPS)
**Tags:** [TEST] [PERF]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Test-gen agent
**Depends on:** P2-T08 (Redis caching active)

### Specification
Write and run k6 load test for the slot availability endpoint targeting the NFR-1.2 gate: p99 < 300ms.

**Test file:** `tests/load/slot-availability.js`

**Test configuration:**
```javascript
export const options = {
  stages: [
    { duration: '30s', target: 100 },   // Ramp up
    { duration: '2m', target: 500 },    // Sustain at 500 RPS
    { duration: '30s', target: 0 },     // Ramp down
  ],
  thresholds: {
    http_req_duration: ['p(99)<300'],   // NFR-1.2 gate
    http_req_failed: ['rate<0.01'],     // < 1% error rate
  },
};
```

**Test data setup:**
- 10 tenant fixtures with 5 locations each
- Each location has 3 resources with full weekly schedules
- 20% of slots pre-booked to simulate realistic load

**Metrics collected:**
- p50, p95, p99, p99.9 latency
- Error rate breakdown (4xx vs 5xx)
- Redis cache hit rate during load (via Redis INFO stats)
- PostgreSQL query count and avg duration

**Acceptance criteria:**
- [ ] p99 < 300ms sustained over 2 minutes at 500 RPS
- [ ] Error rate < 1%
- [ ] Redis cache hit rate > 80%
- [ ] No OOM errors on Spring Boot API instances
- [ ] Test results saved to `tests/load/results/slot-availability-{date}.json`

---

## P5-T02 — k6 Load Test — Concurrent Booking Checkout (500 req/min)
**Tags:** [TEST] [PERF]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Test-gen agent
**Depends on:** P2-T13 (concurrency tests passing)

### Specification
Load test the booking checkout path (hold + confirm) targeting NFR-1.1: 500 concurrent transactions/min.

**Test file:** `tests/load/booking-checkout.js`

**Scenario:**
```javascript
// Scenario 1: Distributed bookings (different slots — no lock contention)
// Scenario 2: Concentrated bookings (10% of users trying same slot — high contention)

export const options = {
  scenarios: {
    distributed: {
      executor: 'constant-arrival-rate',
      rate: 450,
      duration: '2m',
      preAllocatedVUs: 500,
    },
    concentrated: {
      executor: 'constant-arrival-rate',
      rate: 50,     // 50 users fighting for same slot
      duration: '2m',
    }
  },
  thresholds: {
    'http_req_duration{scenario:distributed}': ['p(99)<500'],
    'http_req_failed{scenario:concentrated}': ['rate<0.5'],  // 50% expected 409s
    checks: ['rate>0.99'],  // 99%+ of checks must pass
  }
};
```

**Verification checks:**
- No double-bookings (query DB after test: count bookings per slot, assert max = 1)
- GC job not overwhelmed by abandoned holds from load test
- Kafka consumer lag recovers within 2 minutes of test end

**Acceptance criteria:**
- [ ] 500 total transactions/min sustained for 2 minutes
- [ ] 0 duplicate bookings for any slot
- [ ] GC scheduler clears all holds within 11 minutes of test end
- [ ] Kafka consumer lag returns to 0 within 2 minutes

---

## P5-T03 — Performance Tuning — Redis Cache Warm-Up if p99 > 250ms
**Tags:** [PERF] [SLOT]
**Priority:** P0 (conditional — only if P5-T01 breaches 250ms threshold)
**Estimate:** 1 day
**Agent:** Coder agent + Observability agent
**Depends on:** P5-T01

### Specification
If P5-T01 load test results show slot availability p99 > 250ms, implement proactive cache warm-up for high-demand resources.

**Warm-up strategy:**
- Identify top 20% most-booked resources per tenant (from `scheduling:booking-patterns` memory)
- On application startup + every 30 minutes: pre-load their schedules and next 7 days of holidays into Redis
- Uses Spring `ApplicationReadyEvent` listener + `@Scheduled` job

**Additional optimizations (if warm-up alone insufficient):**
- Add DB connection pool size tuning (`maximumPoolSize`, `minimumIdle`)
- Add PostgreSQL query plan analysis for the slot query (check if index is being used with `EXPLAIN ANALYZE`)
- Add slot result caching for same (resource, date, serviceType) within 30 seconds (short TTL)

**Acceptance criteria:**
- [ ] p99 < 300ms after warm-up (re-run P5-T01)
- [ ] Warm-up job runs on startup and completes within 30 seconds
- [ ] Cache warm-up does not cause startup latency > 5 seconds

---

## P5-T04 — Security Hardening — Full Security Audit
**Tags:** [SECURITY]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Security agent
**Depends on:** All Phase 1–3 tasks complete

### Specification
Run a comprehensive security audit of the entire codebase using the `/security-scan` slash command and security agent.

**Audit scope:**

1. **Dependency CVE scan**
   - Maven: `dependency-check-maven` OWASP report
   - npm: `npm audit --audit-level=high`
   - Gate: ZERO CRITICAL CVEs in production dependencies

2. **Tenant isolation audit**
   - Security agent scans all `@Repository` interfaces
   - Verifies every `@Query` annotation includes `:tenantId` parameter
   - Verifies no `findAll()` calls without tenant filter
   - Gate: 0 violations

3. **JWT security audit**
   - Verify JWT signing key meets minimum length (256 bits for HS256)
   - Verify `jti` claim present and validated (prevent JWT replay)
   - Verify token expiry enforced
   - Verify audience claim validated

4. **OTP security audit**
   - Verify OTP generated with `SecureRandom` (not `java.util.Random`)
   - Verify OTP stored as bcrypt hash (not plaintext)
   - Verify rate limiting active
   - Verify single-use enforcement

5. **PII in Kafka audit**
   - Scan outbox payload builder for any field containing: email, phone, name, address
   - Gate: 0 PII fields in Kafka payloads (user resolution is by UUID only)

6. **HIPAA field checklist**
   - Verify `audit_log` has all required fields
   - Verify `audit_writer` role cannot UPDATE/DELETE
   - Test RLS policy is enforced

**Output:** `docs/SECURITY-FINDINGS.md` — all findings with severity and remediation steps.

**Acceptance criteria:**
- [ ] 0 CRITICAL CVEs
- [ ] 0 tenant isolation violations
- [ ] 0 PII fields in Kafka payloads
- [ ] All HIPAA fields present in audit_log
- [ ] `docs/SECURITY-FINDINGS.md` generated and reviewed

---

## P5-T05 — Tenant Isolation Penetration Test
**Tags:** [SECURITY] [TEST]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Test-gen agent (security mode)
**Depends on:** P5-T04

### Specification
Write automated tests that actively attempt to breach tenant isolation.

**Test scenarios:**

1. **Direct ID enumeration**
   - Tenant A user attempts `GET /tenants/tenant-b-id/bookings` with Tenant A JWT
   - Assert: `403 TENANT_MISMATCH`

2. **JWT crafting attack**
   - Create valid JWT for Tenant A; manually modify `tenantId` claim to Tenant B
   - Assert: `401 UNAUTHORIZED` (signature invalid)

3. **Resource ID guessing**
   - Tenant A attempts `GET /tenants/tenant-a-id/locations/tenant-b-resource-id/...`
   - Assert: `404 RESOURCE_NOT_FOUND` (not `403` — no information leakage about existence)

4. **Booking ID cross-tenant**
   - Tenant A attempts to confirm Tenant B's booking by ID
   - Assert: `404 BOOKING_NOT_FOUND`

5. **Admin role escalation**
   - Customer role JWT attempts admin-only endpoint
   - Assert: `403 INSUFFICIENT_ROLE`

6. **Expired JWT**
   - Set token `exp` to past; attempt any authenticated endpoint
   - Assert: `401 UNAUTHORIZED`

7. **OTP brute force**
   - Submit 11 OTP verification attempts for same identifier
   - Assert: rate limiter blocks after 10 attempts

**Acceptance criteria:**
- [ ] All 7 scenarios produce correct error responses
- [ ] No scenario leaks data from another tenant
- [ ] Test suite runs in CI as part of security gate

---

## P5-T06 — Production Infrastructure — AWS Deployment
**Tags:** [INFRA]
**Priority:** P0
**Estimate:** 2 days
**Agent:** Coder agent (DevOps mode)
**Depends on:** All Phase 1–4 complete, P5-T04 passed

### Specification
Provision and configure the production AWS infrastructure.

**Services to provision:**

| Service | AWS Product | Config |
|---|---|---|
| Spring Boot API | ECS Fargate | 2 tasks, 1vCPU / 2GB RAM each, ALB |
| Next.js | Vercel or AWS Amplify | CDN, auto-deploy from main branch |
| PostgreSQL | RDS PostgreSQL 15 | Multi-AZ, db.t3.medium (initial), automated backups |
| Redis | ElastiCache Redis 7 | Single node (initial), cluster mode for scale |
| Kafka | Amazon MSK | kafka.t3.small, 2 brokers, 3 days retention |
| Schema Registry | Confluent Cloud or self-hosted on ECS | — |
| Debezium | ECS Fargate | 1 task, 0.5vCPU / 1GB RAM |
| notification-service | ECS Fargate | 2 tasks |
| audit-service | ECS Fargate | 2 tasks |
| Secrets | AWS Secrets Manager | All credentials |

**IaC approach:** AWS CDK (TypeScript) or Terraform — defined in `infra/aws/`

**Environment variables:**
- All secrets sourced from AWS Secrets Manager (not hard-coded in task definitions)
- `JWT_SECRET`, `DB_PASSWORD`, `REDIS_URL`, `TWILIO_AUTH_TOKEN`, `SES_*` all from Secrets Manager

**Acceptance criteria:**
- [ ] All services deployed and healthy in AWS
- [ ] API reachable at `https://api.scheduler.io/health` → `{"status":"UP"}`
- [ ] Next.js booking flow works end-to-end in production
- [ ] Kafka producing and consuming events in MSK
- [ ] RDS automated daily backups configured
- [ ] No secrets in source code or Docker images

---

## P5-T07 — CI/CD Pipeline — GitHub Actions
**Tags:** [INFRA]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Coder agent (DevOps mode)
**Depends on:** P5-T04, P5-T05

### Specification
Create the full CI/CD pipeline with all quality gates.

**Pipeline file:** `.github/workflows/ci.yml`

**Pipeline stages:**

```yaml
on: [push, pull_request]

jobs:
  quality-gates:
    steps:
      - Security scan (dependency-check-maven + npm audit)  # BLOCKS on CRITICAL
      - Compile (mvn compile + pnpm build)
      - Unit tests (mvn test)
      - Integration tests (Testcontainers)
      - Concurrency tests (P2-T13)
      - Security isolation tests (P5-T05)
      - Code coverage report (≥ 80% line coverage on service classes)
      - Test gap check (`/test-gap` command — advisory, does not block)

  deploy-staging:
    needs: quality-gates
    if: branch == 'main'
    steps:
      - Build Docker images
      - Push to ECR
      - Deploy to ECS staging environment
      - Run smoke test suite

  deploy-production:
    needs: deploy-staging
    if: manual approval
    steps:
      - Deploy to ECS production
      - Run production smoke tests
      - Alert observability agent
```

**Quality gates that BLOCK merge:**
- CRITICAL CVE in any dependency
- Test coverage < 80% on any service class
- Any tenant isolation test failure
- Any concurrency test failure

**Acceptance criteria:**
- [ ] Pipeline runs on every push to every branch
- [ ] All quality gates enforced (CRITICAL CVE blocks merge)
- [ ] Staging auto-deploys on push to main
- [ ] Production requires manual approval
- [ ] Build time < 15 minutes for full pipeline

---

## P5-T08 — Observability Dashboard
**Tags:** [INFRA]
**Priority:** P1
**Estimate:** 1 day
**Agent:** Observability agent
**Depends on:** P5-T06

### Specification
Configure production observability: metrics, alerting, and Grafana dashboard.

**Metrics to instrument (Spring Boot Actuator → Prometheus → Grafana):**

| Metric | Alert threshold |
|---|---|
| `slot_calculation_latency_p99` | > 250ms |
| `booking_hold_count_active` | > 500 per tenant |
| `booking_hold_expired_count_rate` | > 100/min (abandoned holds surge) |
| `kafka_consumer_lag{group="notification-consumers"}` | > 1000 |
| `kafka_consumer_lag{group="audit-consumers"}` | > 500 |
| `kafka_dlq_depth` | > 0 |
| `otp_dispatch_failure_rate` | > 5% |
| `db_connection_pool_active` | > 90% of pool size |
| `jvm_memory_used` | > 80% of max heap |

**Grafana dashboard panels:**
1. Slot calculation latency histogram (p50/p95/p99)
2. Active PENDING_HOLD count per tenant (bar chart)
3. Kafka consumer lag per group (time series)
4. OTP dispatch success/failure rate per channel
5. Booking confirmation rate (confirmations / holds per hour)
6. DLQ depth (should always be 0)
7. Claude Code session cost tracker (token usage per session via `/cost-report` and `docs/COST-LOG.md`)

**Alerting:**
- PagerDuty / Slack integration for CRITICAL alerts (DLQ > 0, slot latency > 250ms)
- Slack-only for WARNING alerts (consumer lag > 500)

**Acceptance criteria:**
- [ ] All 9 metrics visible in Grafana
- [ ] DLQ > 0 fires Slack alert within 2 minutes
- [ ] Slot latency > 250ms fires Slack alert
- [ ] Dashboard accessible to the team via Grafana Cloud or self-hosted
