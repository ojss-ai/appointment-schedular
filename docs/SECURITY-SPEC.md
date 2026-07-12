# Security Specification
## Multi-Tenant Omni-Industry Scheduling Framework

**Version:** 1.0.0
**Status:** Approved

---

## 1. Authentication Architecture

### 1.1 OTP Flow

```
Client                    Spring Boot              Redis              SES/Twilio
  │                           │                      │                    │
  │── POST /auth/request-otp ─►│                      │                    │
  │                           │── INCR rate:{id} ───►│                    │
  │                           │◄── count ────────────│                    │
  │                           │  (reject if > 5/hr)  │                    │
  │                           │── generate OTP ──────│                    │
  │                           │── SET otp:{id} ─────►│ (TTL: 300s)        │
  │                           │── dispatch ──────────┼───────────────────►│
  │◄── 202 Accepted ──────────│                      │                    │
  │                           │                      │                    │
  │── POST /auth/verify-otp ──►│                      │                    │
  │                           │── GET otp:{id} ─────►│                    │
  │                           │◄── otp_hash ─────────│                    │
  │                           │  bcrypt.verify()      │                    │
  │                           │── DEL otp:{id} ──────►│ (single-use)       │
  │                           │── INSERT otp_records  │                    │
  │◄── 200 JWT ───────────────│                      │                    │
```

### 1.2 OTP Security Rules

| Rule | Value | Enforcement |
|---|---|---|
| OTP length | 6 alphanumeric chars (uppercase) | `SecureRandom` generation |
| OTP hash storage | bcrypt (cost factor 10) | Never store plaintext OTP |
| OTP TTL | 300 seconds (5 minutes) | Redis TTL; also stored in `otp_records.expires_at` |
| Single-use | Yes | DEL from Redis on first verification attempt (success or fail) |
| Rate limit | 5 OTP requests per identifier per hour | Redis INCR with 3600s TTL key |
| Invalidation on failure | Yes | After 1 failed attempt, record status = EXPIRED |
| Magic link token | UUID v4 (email channel only) | Separate from numeric OTP; same TTL |

---

## 2. JWT Specification

### 2.1 Token structure

**Algorithm:** HS256 (symmetric) for v1; RS256 (asymmetric) recommended for multi-service in v2

**Header:**
```json
{ "alg": "HS256", "typ": "JWT" }
```

**Required claims:**
```json
{
  "sub":        "user-uuid",
  "iss":        "scheduler-api",
  "aud":        "scheduler-api",
  "iat":        1751000000,
  "exp":        1751003600,
  "jti":        "unique-token-id",
  "tenantId":   "tenant-uuid",
  "userId":     "user-uuid",
  "roleClaims": ["customer"]
}
```

**Role values:** `customer`, `admin`, `super_admin`

**Token lifetime:** 1 hour (access token). Refresh token: 7 days (stored in `HttpOnly` cookie, not implemented in v1 — session re-auth via OTP).

### 2.2 JWT Verification Filter (Spring Security)

```
Every request (except /auth/*):
1. Extract Bearer token from Authorization header
2. Verify signature with secret key
3. Verify exp claim (not expired)
4. Verify aud claim = "scheduler-api"
5. Extract tenantId, userId, roleClaims
6. Set SecurityContext with TenantAwarePrincipal
7. Pass to controller
```

If any step fails → `401 UNAUTHORIZED`

---

## 3. Tenant Isolation Architecture

### 3.1 Data layer enforcement

Every JPA repository must include `tenantId` in all queries. The `TenantFilterAspect` intercepts all service-layer calls:

```
TenantFilterAspect (AOP)
    @Before any @Service method with @TenantScoped:
    1. Extract tenantId from SecurityContext
    2. Set TenantContext.setCurrentTenant(tenantId)
    3. All subsequent JPA queries auto-injected with WHERE tenant_id = :currentTenant
    @After: TenantContext.clear()
```

### 3.2 Controller layer enforcement

```java
// Applied to every controller method that touches tenant data:
@PreAuthorize("@tenantGuard.check(#tenantId)")
```

`TenantGuard.check(tenantId)`:
1. Get JWT claim `tenantId`
2. Compare with path variable `tenantId`
3. Return `true` only if they match → else throw `TenantMismatchException` → 403

### 3.3 Cross-tenant leakage prevention checklist

- [ ] No `findAll()` calls without `tenant_id` filter — security agent enforces this
- [ ] No JPA `@Query` without `:tenantId` parameter — code review gate
- [ ] No global exception handler that exposes other-tenant entity IDs in error messages
- [ ] Pagination responses never include records from other tenants
- [ ] Admin endpoints check `role_claims: admin` AND matching `tenantId` (admins cannot access other tenants)
- [ ] `super_admin` role is the only role that can query across tenants (super-admin endpoints only)

---

## 4. API Security Controls

### 4.1 Rate limiting

| Endpoint | Limit | Window | Key |
|---|---|---|---|
| `POST /auth/request-otp` | 5 requests | Per hour | per identifier |
| `POST /auth/verify-otp` | 10 attempts | Per hour | per identifier |
| `POST /bookings/hold` | 20 requests | Per minute | per user |
| All other endpoints | 300 requests | Per minute | per JWT user |

Implementation: Redis sliding window counter with Spring `@RateLimiter` or custom `HandlerInterceptor`.

### 4.2 Input validation

All request bodies validated with Spring `@Valid` + Hibernate Validator:
- UUIDs validated as RFC 4122 format
- Dates validated as ISO 8601
- `tenantSlug` validated as `[a-z0-9-]{3,100}`
- Phone numbers validated as E.164 format: `^\+[1-9]\d{1,14}$`
- All string fields have max-length constraints
- JSONB `extensionData` validated against tenant's `intakeSchema` before storage

### 4.3 HTTPS / transport security

- All endpoints served over HTTPS (TLS 1.2 minimum; TLS 1.3 preferred)
- HSTS header: `Strict-Transport-Security: max-age=31536000; includeSubDomains`
- CORS: configured per tenant origin in `tenants.settings.allowedOrigins`
- No mixed-content; all assets served over HTTPS

---

## 5. HIPAA Compliance — Audit Trail

### 5.1 Required audit fields

Every `audit_log` record must contain:

| Field | Value | Required |
|---|---|---|
| `who` | `user_id` of actor | ✅ |
| `what` | `event_type` (state transition) | ✅ |
| `when` | `occurred_at` UTC timestamp | ✅ |
| `tenant_id` | owning tenant | ✅ |
| `booking_id` | affected booking | ✅ |
| `resource_id` | affected resource | ✅ |
| `ip_address` | client IP from request | ✅ |
| `user_agent` | client UA string | ✅ |
| `previous_status` | state before transition | ✅ |
| `new_status` | state after transition | ✅ |

### 5.2 Immutability enforcement

- `audit_log` table uses PostgreSQL Row-Level Security
- `audit_service` role has: INSERT privilege only (no UPDATE, DELETE)
- No `UPDATE` or `DELETE` triggers permitted on `audit_log`
- Periodic hash-chain verification: each row's SHA-256 hash includes previous row's hash (tamper detection)

### 5.3 PII handling in Kafka

- `tenant.bookings.lifecycle` events must NOT contain: patient names, email addresses, phone numbers, SSNs
- User identification in events: `userId` (UUID only) — PII resolved by consuming service from secure DB lookup
- Audit service resolves UUID to display data at read time, never at write time

---

## 6. Dependency Security

### 6.1 CVE scanning policy

- Ruflo `security-agent` runs on every git commit (pre-commit hook)
- Maven: `dependency-check-maven` plugin generates OWASP CVE report
- npm: `npm audit --audit-level=high` gates CI pipeline
- CRITICAL severity CVEs: block merge, must be resolved within 24 hours
- HIGH severity CVEs: block merge, must be resolved within 7 days
- MEDIUM/LOW: logged in `docs/SECURITY-FINDINGS.md`, resolved in next sprint

### 6.2 Secrets management

- No secrets in source code or `.env` committed to repository
- Local dev: `.env.local` (gitignored)
- CI: GitHub Actions secrets / environment variables
- Production: AWS Secrets Manager or HashiCorp Vault
- JWT signing key: rotated every 90 days; old key kept for 1 hour during rotation to honor in-flight tokens

---

## 7. Security Testing Requirements

| Test | Tool | Frequency | Pass Criteria |
|---|---|---|---|
| OWASP dependency scan | `dependency-check-maven` | Every build | 0 CRITICAL CVEs |
| Tenant isolation test | JUnit 5 | Every build | 0 cross-tenant leaks |
| JWT manipulation test | REST Assured | Every build | All tampered tokens rejected |
| OTP brute force test | REST Assured | Every build | Rate limiter blocks after 10 attempts |
| SQL injection test | `/security-scan` command | Weekly | No injectable parameters |
| Penetration test | Manual / Burp Suite | Phase 5 | No CRITICAL/HIGH findings |
