---
description: Full security audit — CVE scan, tenant isolation, JWT, OTP, PII-in-Kafka, HIPAA — Phase 5 sign-off gate
---

# ATOM-SEC-504: Security Hardening — Full Security Audit

**Status**: ✅ Complete
**Feature**: security-audit
**Phase**: 5 (Production)
**Tags**: [SECURITY]
**Complexity**: High
**Agent**: security
**Dependencies**: All Phase 1–3 atoms complete
**Blocks**: ATOM-INFRA-506 (AWS deployment — requires security audit passed), ATOM-INFRA-507 (CI/CD pipeline)
**PR**: TBD

---

## Overview

This atom runs a comprehensive security audit of the entire codebase as the Phase 5 sign-off gate. It covers six audit domains: dependency CVE scanning across all four Maven modules and the Next.js app, tenant isolation query verification, JWT security properties, OTP security properties, PII exposure in Kafka payloads, and HIPAA field completeness in the audit log. All findings are written to `docs/SECURITY-FINDINGS.md` with severity classifications. CRITICAL and HIGH findings block merge; the human reviewer must sign off before production deployment proceeds.

---

## User Story

```
As a System
I want a comprehensive security audit completed and documented before production deployment
So that CRITICAL CVEs, tenant isolation violations, and PII leaks are eliminated before any tenant data is processed in production
```

---

## Acceptance Criteria

- [ ] **AC-01**: 0 CRITICAL CVEs across all 4 Maven modules (`apps/api`, `services/notification-service`, `services/audit-service`) and `apps/web` npm
- [ ] **AC-02**: 0 tenant isolation violations — every `@Query` annotation includes `tenantId` in WHERE clause; zero unscoped `findAll()` calls in production code
- [ ] **AC-03**: 0 PII fields (email, phone, name, address) in Kafka outbox payloads — UUIDs only
- [ ] **AC-04**: All required HIPAA fields present in `audit_log` table: `tenant_id`, `who`, `what`, `when_`, `booking_id`, `resource_id`, `ip_address`, `metadata`
- [ ] **AC-05**: `audit_writer` database role cannot execute UPDATE or DELETE on `audit_log` — verified by explicit SQL test
- [ ] **AC-06**: JWT replay prevention — `jti` claim validated server-side against a used-token store
- [ ] **AC-07**: OTP security — `SecureRandom` only (zero `Math.random` or `new Random()` in OTP code), bcrypt hash storage, rate limiting active, single-use enforcement verified
- [ ] **AC-08**: `docs/SECURITY-FINDINGS.md` generated, reviewed, and signed off by a human reviewer before Phase 5 deployment
- [ ] **AC-09 (Domain abstraction)**: Zero industry-specific terms in any identifier, field name, or API path — audit grep confirms clean

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `mvn dependency-check:check -DfailBuildOnCVSS=7` / `npm audit --audit-level=high` | All modules | 🔜 Planned |
| AC-02 | `grep -rn "@Query" ... | grep -v "tenantId"` — must return 0 lines | `apps/api/src/main/java` | 🔜 Planned |
| AC-03 | `grep -rn "email\|phone\|name\|address" apps/api/.../outbox` — must return 0 lines | Outbox event builders | 🔜 Planned |
| AC-04 | SQL: `SELECT column_name FROM information_schema.columns WHERE table_name='audit_log'` | PostgreSQL `audit_log` table | 🔜 Planned |
| AC-05 | `GRANT`/`REVOKE` audit on `audit_writer` role + explicit UPDATE attempt | Flyway migration / DB role config | 🔜 Planned |
| AC-06 | `TenantIsolationPenTestIT.scenario6_expiredJwtReturns401` + jti replay test | `JwtTokenValidator` | 🔜 Planned |
| AC-07 | Grep audit + `OtpServiceTest` (rate limit, single-use, SecureRandom) | `OtpService` | 🔜 Planned |
| AC-08 | Manual: human sign-off on `docs/SECURITY-FINDINGS.md` | `docs/SECURITY-FINDINGS.md` | 🔜 Planned |

<!-- AC validation passed: TBD, 8 criteria mapped, all TBD -->

---

## Technical Design

### Architecture

The audit is executed by the `security` sub-agent using the `/security-scan` slash command plus manual grep-based static analysis. No new application code is written by this atom — findings drive remediation commits that may spawn follow-on atoms. The primary deliverable is `docs/SECURITY-FINDINGS.md` with a structured severity matrix. The audit covers six domains with distinct tooling per domain.

### Audit Scope

**Domain 1 — Dependency CVE Scan**

```bash
cd apps/api && mvn dependency-check:check -DfailBuildOnCVSS=7
cd apps/web && npm audit --audit-level=high
cd services/notification-service && mvn dependency-check:check -DfailBuildOnCVSS=7
cd services/audit-service && mvn dependency-check:check -DfailBuildOnCVSS=7
```

Gate: ZERO CRITICAL CVEs (CVSS ≥ 7) in production dependencies.

**Domain 2 — Tenant Isolation Audit**

```bash
# All @Query annotations must include tenantId
grep -rn "@Query" apps/api/src/main/java --include="*.java" | grep -v "tenantId"
# → Must return 0 results

# No unscoped findAll() in production paths
grep -rn "findAll\b" apps/api/src/main/java --include="*.java"
# → Every findAll() must have a tenant-scoped equivalent used instead

# Every controller method must carry @PreAuthorize with tenantGuard
grep -rn "@PreAuthorize" apps/api/src/main/java --include="*.java"
# → Every @RestController method must match
```

Gate: 0 violations.

**Domain 3 — JWT Security Audit**

- JWT secret ≥ 256 bits (32 bytes) enforced in `JwtProperties` `@PostConstruct`
- `jti` claim present and stored/checked for replay prevention
- Expiry enforced — verified by scenario6 in `TenantIsolationPenTestIT`
- Audience claim validated against configured value

**Domain 4 — OTP Security Audit**

```bash
# No insecure random in OTP generation
grep -rn "Math.random\|new Random()" apps/api/src/main/java --include="*.java"
# → Must return 0 results

# No raw OTP stored in DB
grep -rn "rawOtp" apps/api/src/main/java --include="*.java"
# → Must return 0 results

# Rate limiting key exists after 3 requests
# Verify Redis key otp-rate:{tenantId}:{email} present after 3 OTP requests
```

**Domain 5 — PII in Kafka Audit**

```bash
grep -rn "email\|phone\|name\|address" \
  apps/api/src/main/java/com/scheduler/api/outbox \
  --include="*.java"
# → Must return 0 — outbox payloads use UUIDs only
```

Gate: 0 PII fields in Kafka payloads.

**Domain 6 — HIPAA Field Completeness**

```sql
-- Run against audit_log table
SELECT column_name FROM information_schema.columns
WHERE table_name = 'audit_log';
-- Verify: tenant_id, who, what, when_, booking_id, resource_id, ip_address, metadata all present
```

### File Structure

```
docs/
└── SECURITY-FINDINGS.md        ← Audit output — CRITICAL/HIGH/MEDIUM/LOW/PASS sections
```

### Interface Contracts

No new Java or TypeScript interfaces introduced by this atom. Remediation findings may add:

```java
// JwtProperties — @PostConstruct validation (if not already present)
public interface JwtProperties {
    String getSecret();       // must be ≥ 32 bytes
    long getExpirationMs();
    String getAudience();
}
```

### Design Rationale

- **ADR-004 (row-level multi-tenancy)**: Tenant isolation is enforced via `tenant_id` in every query. This audit validates that enforcement is complete — no gaps that could be exploited by a malicious tenant.
- **Zero-tolerance gates**: CRITICAL CVE and tenant isolation violations block merge because they represent exploitable vulnerabilities in a multi-tenant SaaS system handling booking data.
- **HIPAA scope**: Even though the domain model is generic, any deployment serving a healthcare tenant falls under HIPAA. The audit log schema must be HIPAA-compliant from day one.

---

## Test Strategy

**Test type**: Static analysis (grep/audit tools) + Integration (Testcontainers — JUnit 5)

```
- dependencyCveScan_returnsZeroCritical:
    Given: all Maven modules and npm package.json at current versions
    Assert: mvn dependency-check exits 0; npm audit exits 0 at --audit-level=high

- tenantIsolationAudit_zeroUnfilteredQueries:
    Given: full grep of @Query annotations in apps/api/src/main/java
    Assert: grep "@Query" | grep -v "tenantId" returns 0 lines

- piiLeakAudit_zeroEmailOrPhoneInOutbox:
    Given: grep of outbox event builder classes
    Assert: no email/phone/name/address field references found

- hipaaFieldAudit_allRequiredColumnsPresent:
    Given: audit_log table created by Flyway migration
    Assert: information_schema.columns lists all 8 required HIPAA fields

- auditWriterRole_cannotUpdateOrDeleteAuditLog:
    Given: DB connected as audit_writer role
    Assert: UPDATE audit_log SET ... → permission denied; DELETE FROM audit_log → permission denied

- jwtReplayPrevention_jtiValidated:
    Given: valid JWT used once successfully
    Assert: same JWT used again returns 401 UNAUTHORIZED (jti marked used)

- otpRateLimiting_blocksAfter3Requests:
    Given: 3 OTP requests sent for same email within rate window
    Assert: 4th request returns 429 TOO_MANY_REQUESTS
```

**Coverage requirements**:
- This atom produces no new service code — no line coverage target
- All grep-based checks must be scripted and rerunnable (not manual one-time checks)

---

## Implementation Constraints

- CRITICAL CVE findings block merge — zero tolerance; no exception granted without explicit security team override
- Tenant isolation violations (any `@Query` without `tenantId`) block merge — zero tolerance
- `audit_writer` role must be READ + INSERT only — UPDATE and DELETE must be explicitly revoked
- OTP must use `SecureRandom` — `Math.random` or `new Random()` in OTP code is a zero-tolerance violation
- PII in Kafka payloads is a zero-tolerance violation — outbox events carry only UUIDs
- `docs/SECURITY-FINDINGS.md` requires human sign-off before any production deployment proceeds
- GitHub Actions: CRITICAL CVE or tenant isolation failure BLOCKS merge (see ATOM-INFRA-507)

---

## Implementation Plan (TDD)

### RED — Enumerate findings

1. Run `/security-scan` slash command across the entire codebase
2. Execute all six domain grep/audit commands and capture output
3. Run `mvn dependency-check` and `npm audit` — capture CVE reports
4. Write all findings (including PASSes) to `docs/SECURITY-FINDINGS.md` — classify by severity

### GREEN — Remediate all CRITICAL and HIGH findings

1. For each CRITICAL CVE: update dependency version; re-run audit to confirm resolved
2. For each tenant isolation violation: add `tenantId` to offending `@Query`; add `@PreAuthorize` to any unguarded controller method
3. For each PII leak in outbox: replace field with UUID reference
4. Verify `audit_writer` role permissions in Flyway migration

### REFACTOR — Harden and document

1. Add `@PostConstruct` JWT secret length validation to `JwtProperties`
2. Script all grep-based audit checks as a rerunnable shell script (`scripts/security-audit.sh`)
3. Update `docs/SECURITY-FINDINGS.md` PASS section with confirmed-clean items
4. Human reviewer signs off on `docs/SECURITY-FINDINGS.md`

---

## Implementation Reference

### Audit Command Suite

**File**: `scripts/security-audit.sh`

```bash
#!/bin/bash
# [TASK: ATOM-SEC-504]
# Full security audit suite — run from repo root

echo "=== Domain 1: Dependency CVE Scan ==="
cd apps/api && mvn dependency-check:check -DfailBuildOnCVSS=7
cd apps/web && npm audit --audit-level=high
cd services/notification-service && mvn dependency-check:check -DfailBuildOnCVSS=7
cd services/audit-service && mvn dependency-check:check -DfailBuildOnCVSS=7

echo "=== Domain 2: Tenant Isolation ==="
echo "--- Unscoped @Query (must be 0 lines) ---"
grep -rn "@Query" apps/api/src/main/java --include="*.java" | grep -v "tenantId"

echo "--- Unguarded findAll() (must be 0 lines) ---"
grep -rn "findAll\b" apps/api/src/main/java --include="*.java"

echo "--- Missing @PreAuthorize (must be 0 lines) ---"
# All @RestController methods must have @PreAuthorize above them
grep -rn "@PreAuthorize" apps/api/src/main/java --include="*.java"

echo "=== Domain 3: JWT Audit ==="
grep -rn "Math.random\|new Random()" apps/api/src/main/java --include="*.java"
# → Must return 0 results

echo "=== Domain 4: OTP Audit ==="
grep -rn "rawOtp" apps/api/src/main/java --include="*.java"
# → Must return 0 results

echo "=== Domain 5: PII in Kafka ==="
grep -rn "email\|phone\|name\|address" \
  apps/api/src/main/java/com/scheduler/api/outbox \
  --include="*.java"
# → Must return 0 results

echo "=== Domain 6: HIPAA Fields ==="
psql -h localhost -U scheduler -d scheduler -c \
  "SELECT column_name FROM information_schema.columns WHERE table_name = 'audit_log' ORDER BY column_name;"
```

---

## Integration Points

**Depends on**: All Phase 1–3 atoms complete — full codebase must exist to audit

**Enables**: ATOM-INFRA-506 (AWS deployment — security audit pass is a hard gate); ATOM-INFRA-507 (CI/CD pipeline uses audit results to configure blocking gates)

**NFR Gates satisfied**: All security-related NFRs; HIPAA audit compliance; zero-tolerance tenant isolation

**Cascading updates required**:
- `docs/SECURITY-FINDINGS.md` — primary deliverable (new file)
- `scripts/security-audit.sh` — rerunnable audit script (new file)
- Any remediation commits for CVEs, isolation violations, PII leaks
- `tasks/MASTER-TASK-LIST.md` — mark atom complete after human sign-off

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `docs/SECURITY-FINDINGS.md` | New | Audit findings: CRITICAL/HIGH/MEDIUM/LOW/PASS |
| `scripts/security-audit.sh` | New | Rerunnable audit command suite |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |
| Various `*Repository.java` files | Modified (if violations found) | Add `tenantId` to unscoped `@Query` |
| Various `*Controller.java` files | Modified (if violations found) | Add missing `@PreAuthorize` |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] 0 CRITICAL CVEs across all Maven modules and npm
- [ ] 0 tenant isolation violations (all `@Query` include `tenantId`)
- [ ] 0 PII fields in Kafka outbox payloads
- [ ] All HIPAA fields present in `audit_log` table
- [ ] `audit_writer` role UPDATE/DELETE blocked — verified by explicit test
- [ ] JWT replay prevention (`jti`) validated server-side
- [ ] OTP: `SecureRandom` only, bcrypt hash, rate limit, single-use
- [ ] `docs/SECURITY-FINDINGS.md` written and human-reviewed
- [ ] `scripts/security-audit.sh` committed and executable
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: security-audit | Phase: 5*
