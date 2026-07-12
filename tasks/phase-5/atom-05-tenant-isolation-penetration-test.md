---
description: Automated tenant isolation penetration tests — 7 attack scenarios, CI gate, blocks merge on any failure
---

# ATOM-SEC-505: Tenant Isolation Penetration Test Suite

**Status**: 🟡 Planned
**Feature**: security-pen-test
**Phase**: 5 (Production)
**Tags**: [SECURITY] [TEST]
**Complexity**: High
**Agent**: security
**Dependencies**: ATOM-SEC-504 (security audit must have passed — all tenant isolation violations remediated)
**Blocks**: ATOM-INFRA-507 (CI/CD pipeline — pen test suite runs as a merge-blocking gate)
**PR**: TBD

---

## Overview

This atom delivers an automated penetration test suite (`TenantIsolationPenTestIT.java`) that actively attempts to breach tenant isolation across seven distinct attack scenarios: tenant ID enumeration, JWT claim tampering, cross-tenant resource ID guessing, cross-tenant booking confirmation, role escalation, expired JWT replay, and OTP brute-force rate limiting. All seven scenarios run as a CI gate using a shared Testcontainers instance and must complete in under 2 minutes. Any single failure blocks the merge.

---

## User Story

```
As a System
I want automated penetration tests that actively attempt tenant isolation breaches to run on every pull request
So that no code change can accidentally introduce a cross-tenant data leak without being caught in CI
```

---

## Acceptance Criteria

- [ ] **AC-01**: All 7 penetration test scenarios pass — correct HTTP status code returned for each
- [ ] **AC-02**: Scenario 3 (cross-tenant resource ID guessing) returns `404 NOT_FOUND` — not `403 FORBIDDEN` (existence of the resource must not be revealed to a foreign tenant)
- [ ] **AC-03**: Scenario 2 (JWT claim tampering) returns `401 UNAUTHORIZED` — tampered signature rejected
- [ ] **AC-04**: No scenario returns actual data (response body) belonging to another tenant
- [ ] **AC-05**: Test suite runs as a CI gate in GitHub Actions — any failure blocks merge
- [ ] **AC-06**: Full test suite completes in < 2 minutes using a shared Testcontainers instance
- [ ] **AC-07**: Scenario 7 (OTP brute force) triggers `429 TOO_MANY_REQUESTS` on the 6th request within the rate window
- [ ] **AC-08 (Domain abstraction)**: Test uses generic API paths (`tenants`, `locations`, `resources`, `bookings`) — no industry-specific terms

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `TenantIsolationPenTestIT` — all 7 `@Test` methods | Spring Boot REST layer | 🔜 Planned |
| AC-02 | `scenario3_crossTenantResourceIdReturns404` | `ResourceController` + `ResourceRepository` | 🔜 Planned |
| AC-03 | `scenario2_tamperedJwtReturns401` | `JwtTokenValidator` | 🔜 Planned |
| AC-04 | All scenarios — `assertThat(res.getBody()).doesNotContain(tenantBId.toString())` | All controller responses | 🔜 Planned |
| AC-05 | `.github/workflows/ci.yml` — `Security isolation tests` step | ATOM-INFRA-507 | 🔜 Planned |
| AC-06 | `@Testcontainers` with `@Container` static shared instance | Testcontainers config | 🔜 Planned |
| AC-07 | `scenario7_otpBruteForceRateLimited` | `OtpService` rate limiter | 🔜 Planned |

<!-- AC validation passed: TBD, 7 criteria mapped, all TBD -->

---

## Technical Design

### Architecture

The test class is a Spring Boot integration test with `webEnvironment = RANDOM_PORT` and a shared Testcontainers PostgreSQL instance. Two tenant fixtures (`tenantA`, `tenantB`) are created in `@BeforeAll` with full admin JWTs. Each `@Test` method is a self-contained attack scenario: it sets up attacker context, fires the HTTP request, and asserts the correct defensive response. The `tamperTenantIdClaim()` helper modifies the JWT payload without re-signing — the signature validation layer must reject it.

### Data Flow / Sequence

```
@BeforeAll:
  → create tenantA + tenantB via API
  → issue admin JWTs (tokenA, tokenB)
  → create resources, bookings for tenantB (attack targets)

Scenario 1 (tenant ID enumeration):
  tokenA → GET /api/v1/tenants/{tenantBId}/locations
  → Spring Security: @PreAuthorize tenantGuard.check(tenantBId) fails
  → 403 FORBIDDEN

Scenario 2 (JWT tampering):
  tampered JWT (tenantA payload + invalid signature) → GET /api/v1/tenants/{tenantBId}/locations
  → JwtTokenValidator rejects signature
  → 401 UNAUTHORIZED

Scenario 3 (resource ID guessing):
  tokenA + tenantAId path + tenantBResourceId → GET .../resources/{tenantBResourceId}
  → ResourceRepository.findByIdAndTenantId(tenantBResourceId, tenantAId) → empty
  → 404 NOT_FOUND (not 403 — no existence leakage)
```

### File Structure

```
apps/api/src/test/java/com/scheduler/
└── security/
    └── TenantIsolationPenTestIT.java    ← 7 penetration test scenarios
```

### Interface Contracts

```java
// Test class shape (method signatures only)
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class TenantIsolationPenTestIT {

    // Shared Testcontainers instance
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15");

    // Test state
    private static UUID tenantAId, tenantBId;
    private static String tokenA, tokenB;

    @BeforeAll
    static void setupTenants();

    @Test void scenario1_tenantACannotAccessTenantBLocations();
    @Test void scenario2_tamperedJwtReturns401();
    @Test void scenario3_crossTenantResourceIdReturns404();
    @Test void scenario4_crossTenantBookingConfirmReturns404();
    @Test void scenario5_customerRoleCannotAccessAdminEndpoint();
    @Test void scenario6_expiredJwtReturns401();
    @Test void scenario7_otpBruteForceRateLimited();

    // Helpers
    private HttpEntity<Void> entityWithToken(String token);
    private String tamperTenantIdClaim(String token, UUID newTenantId);
    private UUID createResource(UUID tenantId, String token);
    private UUID createHoldAsUser(UUID tenantId, String token);
    private String createCustomerToken(UUID tenantId);
    private String createExpiredToken(UUID tenantId);
    private ResponseEntity<String> requestOtp(String email, UUID tenantId);
}
```

### Design Rationale

- **ADR-004 (row-level multi-tenancy)**: All seven scenarios directly test the `tenant_id` discriminator enforcement. Scenario 3 returning 404 (not 403) is deliberate — resource existence must never be revealed to a foreign tenant, even as an error message.
- **Why Testcontainers (not H2)**: Multi-tenant isolation relies on PostgreSQL-specific row locking semantics. H2 would not faithfully reproduce the locking behaviour tested by scenario 4.
- **Why shared `@Container` (not per-test)**: The suite must complete in < 2 minutes. Spinning up a fresh PostgreSQL container per test would take 30–45 seconds per scenario.
- **GitHub Actions gate**: These tests run as `mvn test -Dtest="TenantIsolationPenTestIT"` in CI. Failure blocks merge — no exceptions. This is the last line of defence before production.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL + Spring Boot RandomPort)

```
- scenario1_tenantACannotAccessTenantBLocations:
    Given: JWT for tenantA; path contains tenantBId
    Assert: 403 FORBIDDEN; response body contains no tenantB data

- scenario2_tamperedJwtReturns401:
    Given: valid tokenA with tenantId claim replaced by tenantBId (signature invalid)
    Assert: 401 UNAUTHORIZED

- scenario3_crossTenantResourceIdReturns404:
    Given: tokenA; path uses tenantAId; resource UUID belongs to tenantB
    Assert: 404 NOT_FOUND (not 403 — existence must not leak)

- scenario4_crossTenantBookingConfirmReturns404:
    Given: tokenA; path uses tenantAId; bookingId belongs to tenantB
    Assert: 404 NOT_FOUND

- scenario5_customerRoleCannotAccessAdminEndpoint:
    Given: JWT with ROLE_CUSTOMER for tenantA
    Assert: POST /api/v1/tenants/{tenantAId}/locations → 403 FORBIDDEN

- scenario6_expiredJwtReturns401:
    Given: JWT with exp = now() - 1 minute
    Assert: 401 UNAUTHORIZED

- scenario7_otpBruteForceRateLimited:
    Given: 5 OTP requests sent for same email within rate window
    Assert: 6th request → 429 TOO_MANY_REQUESTS
```

**Coverage requirements**:
- All 7 scenarios must pass — partial pass is treated as full failure in CI
- Test suite must run in < 2 minutes with shared Testcontainers instance

---

## Implementation Constraints

- Zero tolerance: any single scenario failure blocks merge in CI
- Scenario 3 must return `404` — returning `403` is considered an isolation violation (existence leakage)
- `tamperTenantIdClaim()` must modify the JWT payload without re-signing — testing signature validation, not just claim parsing
- Testcontainers PostgreSQL instance must be `@Container static` (shared across all tests in the class)
- No `console.log` or `System.out.println` — use SLF4J test logger
- All test data uses generic domain terms (`resource`, `booking`, `location`) — no industry-specific terms

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `TenantIsolationPenTestIT.java` with all 7 `@Test` stubs
2. Implement `@BeforeAll` fixture setup (create two tenants, JWTs)
3. Run all 7 tests — expect failures at the `@PreAuthorize` and `JwtTokenValidator` layers (not yet fully implemented)

### GREEN — Minimum code to pass

1. Confirm `@PreAuthorize("@tenantGuard.check(#tenantId)")` is on all targeted controller methods (Phase 1–3 requirement)
2. Confirm `JwtTokenValidator` rejects tampered signatures (scenario 2)
3. Confirm `ResourceRepository.findByIdAndTenantId()` is used (returns empty for cross-tenant lookup — scenario 3 returns 404)
4. Confirm OTP rate limiter is active in Redis (scenario 7)
5. Run all 7 tests — all must pass

### REFACTOR — Quality pass

1. Extract `TenantPenTestFixtures` helper class for JWT generation and entity creation
2. Add `@DisplayName` annotations to all scenarios for readable CI output
3. Verify test suite completes in < 2 minutes and document timing in PR description

---

## Implementation Reference

### Penetration Test Suite

**File**: `apps/api/src/test/java/com/scheduler/security/TenantIsolationPenTestIT.java`

```java
// [TASK: ATOM-SEC-505]
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class TenantIsolationPenTestIT {

    // Two separate tenants set up in @BeforeAll
    private static UUID tenantAId, tenantBId;
    private static String tokenA, tokenB;   // admin JWTs

    // Scenario 1: Direct tenant ID enumeration
    @Test
    void scenario1_tenantACannotAccessTenantBLocations() {
        // JWT belongs to Tenant A; path uses Tenant B's ID
        var res = restTemplate.exchange(
            "/api/v1/tenants/{id}/locations", GET,
            entityWithToken(tokenA), String.class, tenantBId);
        assertThat(res.getStatusCode()).isEqualTo(FORBIDDEN);  // 403
    }

    // Scenario 2: JWT crafting — modify tenantId claim
    @Test
    void scenario2_tamperedJwtReturns401() {
        String tampered = tamperTenantIdClaim(tokenA, tenantBId);
        var res = restTemplate.exchange(
            "/api/v1/tenants/{id}/locations", GET,
            entityWithToken(tampered), String.class, tenantBId);
        assertThat(res.getStatusCode()).isEqualTo(UNAUTHORIZED);  // 401
    }

    // Scenario 3: Resource ID guessing (cross-tenant resource)
    @Test
    void scenario3_crossTenantResourceIdReturns404() {
        UUID tenantBResourceId = createResource(tenantBId, tokenB);
        var res = restTemplate.exchange(
            "/api/v1/tenants/{tid}/locations/{lid}/resources/{rid}",
            GET, entityWithToken(tokenA), String.class,
            tenantAId, anyLocationId(tenantAId), tenantBResourceId);
        assertThat(res.getStatusCode()).isEqualTo(NOT_FOUND);  // 404, not 403
    }

    // Scenario 4: Cross-tenant booking confirmation
    @Test
    void scenario4_crossTenantBookingConfirmReturns404() {
        UUID tenantBBookingId = createHoldAsUser(tenantBId, tokenB);
        var res = restTemplate.postForEntity(
            "/api/v1/tenants/{tid}/bookings/{bid}/confirm",
            entityWithToken(tokenA), String.class,
            tenantAId, tenantBBookingId);
        assertThat(res.getStatusCode()).isEqualTo(NOT_FOUND);
    }

    // Scenario 5: Admin role escalation
    @Test
    void scenario5_customerRoleCannotAccessAdminEndpoint() {
        String customerToken = createCustomerToken(tenantAId);
        var res = restTemplate.postForEntity(
            "/api/v1/tenants/{id}/locations", entityWithToken(customerToken),
            String.class, tenantAId);
        assertThat(res.getStatusCode()).isEqualTo(FORBIDDEN);
    }

    // Scenario 6: Expired JWT
    @Test
    void scenario6_expiredJwtReturns401() {
        String expiredToken = createExpiredToken(tenantAId);
        var res = restTemplate.exchange(
            "/api/v1/tenants/{id}/locations", GET,
            entityWithToken(expiredToken), String.class, tenantAId);
        assertThat(res.getStatusCode()).isEqualTo(UNAUTHORIZED);
    }

    // Scenario 7: OTP brute force rate limiting
    @Test
    void scenario7_otpBruteForceRateLimited() {
        for (int i = 0; i < 5; i++) {
            requestOtp("victim@example.com", tenantAId);
        }
        var sixthRes = requestOtp("victim@example.com", tenantAId);
        assertThat(sixthRes.getStatusCode()).isEqualTo(TOO_MANY_REQUESTS);  // 429
    }
}
```

---

## Integration Points

**Depends on**: ATOM-SEC-504 (all tenant isolation violations remediated before pen tests run); Spring Security `@PreAuthorize` + `tenantGuard` active on all controllers; `JwtTokenValidator` rejecting tampered signatures; OTP rate limiter active in Redis

**Enables**: ATOM-INFRA-507 (CI/CD pipeline — this test class is wired as a merge-blocking gate)

**NFR Gates satisfied**: Zero tenant isolation violations (security NFR); all 7 scenarios must pass before production deployment

**Cascading updates required**:
- `tasks/MASTER-TASK-LIST.md` — mark atom complete
- `.github/workflows/ci.yml` — add `mvn test -Dtest="TenantIsolationPenTestIT"` step (ATOM-INFRA-507)

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/test/java/com/scheduler/security/TenantIsolationPenTestIT.java` | New | 7 penetration test scenarios — merge-blocking CI gate |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] All 7 scenarios pass (correct HTTP status codes)
- [ ] Scenario 3 returns `404` (not `403`) — existence leakage test passes
- [ ] Scenario 2 rejects tampered JWT with `401` — signature validation confirmed
- [ ] No scenario returns data belonging to another tenant
- [ ] Test suite completes in < 2 minutes
- [ ] CI gate configured — failure blocks merge
- [ ] Zero industry-specific terms in any test fixture, path, or assertion
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: security-pen-test | Phase: 5*
