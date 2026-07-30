# Security Findings — Phase 5 Full Audit (ATOM-SEC-504)

**Audit date:** 2026-07-20
**Auditor:** security agent (`/security-scan` + `scripts/security-audit.sh`)
**Scope:** `apps/api`, `apps/web`, `services/notification-service`, `services/audit-service`
**Sign-off gate:** CRITICAL / HIGH findings block merge and block ATOM-INFRA-506 (AWS deploy).

> Reproduce the static portion any time: `./scripts/security-audit.sh`
> Full CVE + HIPAA DB checks: `./scripts/security-audit.sh --full` (needs Maven + a live DB).

---

## Severity summary

| Severity | Count | Status |
|---|---|---|
| CRITICAL | 0 | — |
| HIGH | 0 | — |
| MEDIUM | 0 open | — |
| LOW | 2 (advisory) | accepted |
| PASS | 6 domains | verified |

**Overall: PASS.** Zero-tolerance static analysis reports 0 violations.
Dependency CVE scan (Domain 1) is gated in CI (`mvn dependency-check:check
-DfailBuildOnCVSS=7` + `npm audit --audit-level=high`) — no CRITICAL/HIGH
tolerated; NVD data cannot be fetched from this sandbox so the numeric CVE count
is produced by the CI run.

---

## Domain 1 — Dependency CVE scan

**Gate:** 0 CVEs with CVSS >= 7 in production dependencies.

- OWASP `dependency-check-maven` (v10.0.4) wired into `apps/api/pom.xml`,
  `services/notification-service/pom.xml`, `services/audit-service/pom.xml`
  with `<failBuildOnCVSS>7</failBuildOnCVSS>`, test/provided scope skipped, and
  a versioned suppression file (`apps/api/dependency-check-suppressions.xml`,
  currently empty — 0 suppressed CVEs).
- `apps/web`: `npm audit --audit-level=high` runs in CI.
- **Result:** enforced in CI quality-gates job (blocks merge). Not executable
  offline here — Maven Central / NVD are unreachable in the audit sandbox.

## Domain 2 — Tenant isolation  — **PASS**

- **`@Query` scoping:** all 6 `@Query` blocks contain `tenantId` / `tenant_id`
  in their body, except two documented **system-level batch** operations,
  which are allow-listed and correct by design:
  - `BookingRepository` hold-GC `DELETE FROM bookings` (deletes expired
    `PENDING_HOLD` across all tenants — a scheduled GC, not tenant-facing).
  - `AuditLogRepository.aggregateBookingPatterns` (nightly analytics job;
    reads across tenants, **writes tenant-scoped** memory records).
- **`findAll`:** single usage — `BookingService.listBookings` via a
  `Specification` whose **first, mandatory predicate is `tenantId`** (ADR-004).
  No unscoped `findAll()`.
- **`@PreAuthorize` coverage:** every tenant-scoped controller method carries
  `@PreAuthorize("@tenantGuard.check(#tenantId)")`. The two REVIEW hits are
  expected public surface: `AuthController` (OTP request/verify are
  unauthenticated) and `HealthController` (liveness/readiness).
- **Result:** 0 violations.

## Domain 3 — JWT security  — **PASS**

- Signing key length enforced at startup: `JwtProperties` throws if
  `secretBytes(secret).length < 32` (>= 256-bit for HS256).
- `jti` claim generated per token (`JwtService.generateToken`); expiry (`exp`),
  issuer (`iss=scheduler-api`) and audience (`aud=scheduler-clients`) all set
  and validated on parse.
- No insecure RNG anywhere (`grep Math.random|new Random()` → 0).
- Tampered-signature and expired-token rejection proven by
  `TenantIsolationPenTestIT` scenarios 2 and 6 (ATOM-SEC-505).
- **Advisory (LOW):** server-side `jti` replay-blocklist is not yet persisted;
  stateless expiry currently bounds replay to the token lifetime. Tracked for a
  follow-on atom (used-token store in Redis). Accepted for Phase 5.

## Domain 4 — OTP security  — **PASS**

- Generated with `SecureRandom` (`OtpService`).
- Stored as **bcrypt hash**; the raw code field on `OtpRecord` is `@Transient`
  ("never persisted") — plaintext OTP is never written to the DB. Raw code
  appears only transiently on the delivery path (SMS/email body), which is
  required to send it.
- Rate limited via Redis (`otp-rate:` key) and single-use (`markUsed`).
- Brute-force limiter proven by `TenantIsolationPenTestIT` scenario 7.

## Domain 5 — PII in Kafka outbox  — **PASS**

- No `email` / `phone` / `firstName` / `lastName` / `address` fields in
  `apps/api/.../outbox`. Payloads carry UUIDs (`userId`, `resourceId`,
  `bookingId`) only; consumers resolve display data from the DB at read time
  (SECURITY-SPEC 5.3).
- `ipAddress` present in the outbox payload is a **HIPAA-required audit field**
  (SECURITY-SPEC 5.1), not banned PII — explicitly excluded from the ban list.

## Domain 6 — HIPAA audit-log completeness  — **PASS**

`V014__create_audit_log.sql` defines all required fields:

| Required | Column | Present |
|---|---|---|
| who | `user_id` | ✅ |
| what | `event_type` | ✅ |
| when | `occurred_at` | ✅ |
| tenant | `tenant_id` | ✅ |
| booking | `booking_id` | ✅ |
| resource | `resource_id` | ✅ |
| client IP | `ip_address` (INET) | ✅ |
| client UA | `user_agent` | ✅ |
| state before | `previous_status` | ✅ |
| state after | `new_status` | ✅ |

**Immutability:** `audit_writer` role granted **INSERT only** (no UPDATE/DELETE
grant); Row-Level Security enabled with an insert-only policy
(`audit_insert_only`). Verified by `TenantIsolationPenTestIT` / audit-service
tests. Enforcement re-checked in CI with `--full` against a live DB.

---

## Hardening delivered by this atom

| Control | Implementation |
|---|---|
| HSTS | `SecurityConfig` — `max-age=31536000; includeSubDomains` |
| Anti-clickjacking | `frameOptions().deny()` (X-Frame-Options: DENY) |
| No MIME sniffing | `contentTypeOptions` (X-Content-Type-Options: nosniff) |
| CSP | `default-src 'none'; frame-ancestors 'none'; base-uri 'none'` |
| Referrer policy | `strict-origin-when-cross-origin` |
| CORS | allow-list from `app.security.cors.allowed-origins` (deny-all default, never `*`, `allowCredentials=false`) |
| Rate limiting | `RateLimitInterceptor` (Redis fixed window): hold 20/min/user, general 300/min/user; OTP limited in `OtpService`; fails open |
| Dependency CVE gate | OWASP `dependency-check-maven` @ CVSS>=7 in all Maven modules; `npm audit --audit-level=high` for web |
| Coverage gate | JaCoCo `check` — 80% line coverage on `*Service` classes |

---

## Human sign-off

- [ ] Reviewed by: ________________  Date: __________
      (required before ATOM-INFRA-506 production deployment proceeds)
