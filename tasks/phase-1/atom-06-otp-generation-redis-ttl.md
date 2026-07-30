# ATOM-OTP-REDIS-006: OTP Generation and Redis TTL Service

**Status**: ✅ Complete
**Feature**: otp-generation
**Phase**: 1 (Foundation)
**Tags**: [AUTH]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-FLYWAY-MIGRATIONS-004, ATOM-SPRING-SECURITY-005
**Blocks**: None
**PR**: TBD

---

## Overview

This atom implements `OtpService` — the cryptographically secure, single-use, rate-limited OTP subsystem. OTP codes are generated using `SecureRandom` over an unambiguous alphabet, bcrypt-hashed before storage in Redis with a 5-minute TTL, and invalidated on first use regardless of correctness. A rate limiter in Redis enforces a maximum of 5 OTP requests per identifier per hour. The key design decision is that the raw OTP is attached to the `OtpRecord` entity transiently (never persisted) so the caller can deliver it to the user, while only the bcrypt hash survives in Redis.

---

## User Story

```
As a Booking User
I want to receive a one-time code that expires quickly and cannot be reused
So that my account access is secure even if the code is intercepted
```

---

## Acceptance Criteria

- [ ] **AC-01**: OTP stored in Redis with TTL of exactly 300 seconds (verified with `TTL otp:{identifier}`)
- [ ] **AC-02**: Submitting the correct OTP returns `VerificationResult.SUCCESS` and deletes the Redis key
- [ ] **AC-03**: Submitting an incorrect OTP returns `VerificationResult.INVALID` and deletes the Redis key (single-use enforced regardless of correctness)
- [ ] **AC-04**: Submitting an OTP after the Redis key has expired returns `VerificationResult.EXPIRED`
- [ ] **AC-05**: The 6th `generateAndStore()` call within 1 hour for the same identifier throws `OtpRateLimitException`
- [ ] **AC-06**: All 5 unit test scenarios pass
- [ ] **AC-07**: OTP generated with `SecureRandom` — no `Math.random()` or `java.util.Random`
- [ ] **AC-08**: OTP stored as bcrypt hash in Redis — never plaintext
- [ ] **AC-09**: `rawOtp` field is `@Transient` — verified it does not appear in the `otp_records` table
- [ ] **AC-10 (Tenant isolation)**: `OtpRepository.markVerified()` includes `tenantId` in WHERE clause — zero cross-tenant updates
- [ ] **AC-11 (Domain abstraction)**: No industry-specific terms in any class name, method name, or field name

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `OtpServiceTest.java` | `OtpService.generateAndStore()` | 🔜 Planned |
| AC-02 | `OtpServiceTest.java` | `OtpService.verify()` | 🔜 Planned |
| AC-03 | `OtpServiceTest.java` | `OtpService.verify()` | 🔜 Planned |
| AC-04 | `OtpServiceTest.java` | `OtpService.verify()` | 🔜 Planned |
| AC-05 | `OtpServiceTest.java` | `OtpService.checkRateLimit()` | 🔜 Planned |
| AC-06 | `OtpServiceTest.java` | All OtpService methods | 🔜 Planned |
| AC-07 | Code review | `OtpService.generateOtp()` | 🔜 Planned |
| AC-08 | Code review + Redis inspection | `OtpService.generateAndStore()` | 🔜 Planned |
| AC-09 | Schema inspection / IT | `OtpRecord.rawOtp` | 🔜 Planned |
| AC-10 | `OtpRepositoryTest.java` | `OtpRepository.markVerified()` | 🔜 Planned |
| AC-11 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 11 criteria rewritten, 11 marked TBD -->

---

## Technical Design

### Architecture

`OtpService` coordinates three resources: `StringRedisTemplate` (hash storage + rate limiting), `OtpRepository` (JPA persistence to `otp_records`), and `BCryptPasswordEncoder` (hash/verify). Redis is the primary TTL enforcement mechanism — when `getAndDelete` returns null, the code is treated as expired regardless of DB state. The DB record is an audit trail, not a verification source.

### Data Flow / Sequence (if applicable)

```
OtpService.generateAndStore(identifier, tenantId, channel)
  → checkRateLimit(identifier)         [Redis INCR on rate key]
  → generateOtp()                      [SecureRandom over OTP_ALPHABET]
  → passwordEncoder.encode(rawOtp)     [bcrypt hash]
  → redis.opsForValue().set(key, hash, 300s)
  → otpRepository.save(OtpRecord)      [@Transactional — DB + Redis in same tx scope]
  → record.setRawOtp(rawOtp)           [@Transient — not persisted]
  → return OtpRecord (with rawOtp attached)

OtpService.verify(identifier, submittedOtp, tenantId)
  → redis.opsForValue().getAndDelete(key)   [atomic get + delete]
  → if null → VerificationResult.EXPIRED
  → passwordEncoder.matches(submitted, hash)
  → if false → VerificationResult.INVALID
  → otpRepository.markVerified(tenantId, identifier)
  → return VerificationResult.SUCCESS
```

### File Structure

```
apps/api/src/main/java/com/scheduler/api/auth/otp/
├── OtpService.java
├── OtpRecord.java             ← JPA entity → otp_records table
├── OtpRepository.java
├── VerificationResult.java    ← record
├── OtpRateLimitException.java
└── OtpConstants.java

apps/api/src/test/java/com/scheduler/api/auth/otp/
└── OtpServiceTest.java
```

### Interface Contracts

```java
// OtpConstants — all magic numbers in one place
public final class OtpConstants {
    public static final int TTL_SECONDS       = 300;
    public static final int RATE_LIMIT_WINDOW = 3600;
    public static final int RATE_LIMIT_MAX    = 5;
    public static final int OTP_LENGTH        = 6;
    public static final String OTP_ALPHABET   = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
}

// VerificationResult — sealed record
public record VerificationResult(Status status) {
    public enum Status { SUCCESS, INVALID, EXPIRED }
    public static VerificationResult success();
    public static VerificationResult invalid();
    public static VerificationResult expired();
    public boolean isSuccess();
}

// OtpRepository
public interface OtpRepository extends JpaRepository<OtpRecord, UUID> {
    @Modifying
    @Query("""
        UPDATE OtpRecord o SET o.status = 'verified', o.verifiedAt = NOW()
        WHERE o.tenantId = :tenantId AND o.identifier = :identifier
          AND o.status = 'pending'
        """)
    void markVerified(@Param("tenantId") UUID tenantId,
                      @Param("identifier") String identifier);
}

// OtpService — public contract
@Service
public class OtpService {
    @Transactional
    public OtpRecord generateAndStore(String identifier, UUID tenantId, String channel);
    @Transactional
    public VerificationResult verify(String identifier, String submittedOtp, UUID tenantId);
    public void invalidate(String identifier);
}
```

### Design Rationale

- **ADR-004**: `OtpRepository.markVerified()` includes `tenantId` in the WHERE clause — a cross-tenant identifier collision cannot mark a different tenant's OTP as verified.
- `getAndDelete` (atomic Redis operation) ensures the key is consumed on first verification attempt regardless of success or failure, preventing replay attacks.
- BCrypt is used over HMAC-SHA for OTP hashing because the code space is small enough that timing-safe comparison matters more than throughput.

---

## Test Strategy

**Test type**: Unit (JUnit 5 + Mockito) and Integration (Testcontainers Redis)

```
- shouldStoreHashedOtp_inRedisWithCorrectTTL:
    Given: generateAndStore() called for identifier X
    Assert: Redis key "otp:X" exists; TTL is between 295 and 300 seconds; value is not equal to rawOtp

- shouldReturnSuccess_andDeleteKey_forCorrectOtp:
    Given: OTP generated and stored; correct rawOtp submitted to verify()
    Assert: VerificationResult.status == SUCCESS; Redis key "otp:X" no longer exists

- shouldReturnInvalid_andDeleteKey_forWrongOtp:
    Given: OTP generated and stored; wrong code submitted to verify()
    Assert: VerificationResult.status == INVALID; Redis key "otp:X" no longer exists

- shouldReturnExpired_whenKeyAbsent:
    Given: Redis key "otp:X" does not exist (TTL elapsed or never set)
    Assert: VerificationResult.status == EXPIRED

- shouldThrowRateLimitException_onSixthRequest:
    Given: generateAndStore() called 5 times for same identifier within 1 hour
    Assert: 6th call throws OtpRateLimitException
```

**Coverage requirements**:
- Line coverage ≥ 80% on `OtpService`
- Integration test must use Testcontainers Redis (not mocked `StringRedisTemplate`)

---

## Implementation Constraints

- OTP must be generated with `SecureRandom` — never `Math.random()` or `java.util.Random`
- OTP must be stored as bcrypt hash in Redis — never plaintext
- `rawOtp` field on `OtpRecord` must be `@Transient`
- Every JPA query must include `tenant_id` in WHERE clause
- DTOs must be Java 21 records
- `generateAndStore()` and `verify()` must be `@Transactional`
- Redis key format: `otp:{identifier}` for OTP hash; `otp-rate:{identifier}` for rate counter
- Rate limit window: 3600 seconds; max requests: 5
- OTP alphabet excludes ambiguous characters (0, O, 1, I)
- No `System.out.println` — use SLF4J

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `OtpServiceTest.java` with Mockito mocks for `StringRedisTemplate` and `OtpRepository`
2. Write all 5 test scenarios — assert they fail (classes don't exist yet)

### GREEN — Minimum code to pass

1. Create `OtpConstants.java`
2. Create `VerificationResult.java` record
3. Create `OtpRateLimitException.java`
4. Create `OtpRecord.java` JPA entity with `@Transient rawOtp`
5. Create `OtpRepository.java` with `markVerified()` query
6. Implement `OtpService.java` — all three public methods
7. Run `OtpServiceTest` — all 5 pass

### REFACTOR — Quality pass

1. Extract Redis key construction to private helper methods
2. Add structured logging for generate and verify events
3. Run integration tests with Testcontainers Redis
4. Run `/security-scan` to verify no plaintext OTP leaks in logs

---

## Implementation Reference

### OtpConstants.java

**File**: `apps/api/src/main/java/com/scheduler/api/auth/otp/OtpConstants.java`

```java
// TASK: P1-T06
public final class OtpConstants {
    public static final int TTL_SECONDS          = 300;   // 5 minutes
    public static final int RATE_LIMIT_WINDOW    = 3600;  // 1 hour
    public static final int RATE_LIMIT_MAX       = 5;
    public static final int OTP_LENGTH           = 6;
    // Exclude ambiguous characters: 0, O, 1, I
    public static final String OTP_ALPHABET =
        "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private OtpConstants() {}
}
```

### OtpService.java

**File**: `apps/api/src/main/java/com/scheduler/api/auth/otp/OtpService.java`

```java
// TASK: P1-T06
@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

    private final StringRedisTemplate redis;
    private final OtpRepository otpRepository;
    private final PasswordEncoder passwordEncoder;  // BCryptPasswordEncoder

    /** Generate OTP, store bcrypt hash in Redis with TTL, persist record to DB. */
    @Transactional
    public OtpRecord generateAndStore(String identifier, UUID tenantId, String channel) {
        checkRateLimit(identifier);

        String rawOtp   = generateOtp();
        String hashedOtp = passwordEncoder.encode(rawOtp);

        // Store hash in Redis (caller needs raw OTP to deliver to user)
        String redisKey = "otp:" + identifier;
        redis.opsForValue().set(redisKey, hashedOtp, TTL_SECONDS, TimeUnit.SECONDS);

        // Persist record (status = pending)
        OtpRecord record = OtpRecord.builder()
            .tenantId(tenantId)
            .identifier(identifier)
            .identifierType(identifier.contains("@") ? "email" : "phone")
            .channel(channel)
            .status("pending")
            .expiresAt(Instant.now().plusSeconds(TTL_SECONDS))
            .build();
        otpRepository.save(record);

        // Attach raw OTP transiently for caller to deliver
        record.setRawOtp(rawOtp);
        return record;
    }

    /** Verify submitted OTP. Always invalidates (single-use). */
    @Transactional
    public VerificationResult verify(String identifier, String submittedOtp, UUID tenantId) {
        String redisKey = "otp:" + identifier;
        String storedHash = redis.opsForValue().getAndDelete(redisKey);

        if (storedHash == null) {
            return VerificationResult.expired();
        }
        if (!passwordEncoder.matches(submittedOtp, storedHash)) {
            return VerificationResult.invalid();
        }
        // Mark DB record verified
        otpRepository.markVerified(tenantId, identifier);
        return VerificationResult.success();
    }

    /** Explicit invalidation (e.g. magic link flow). */
    public void invalidate(String identifier) {
        redis.delete("otp:" + identifier);
    }

    // --- Private ---

    private void checkRateLimit(String identifier) {
        String rateKey = "otp-rate:" + identifier;
        Long count = redis.opsForValue().increment(rateKey);
        if (count == 1) {
            redis.expire(rateKey, RATE_LIMIT_WINDOW, TimeUnit.SECONDS);
        }
        if (count > RATE_LIMIT_MAX) {
            throw new OtpRateLimitException("OTP rate limit exceeded for: " + identifier);
        }
    }

    private String generateOtp() {
        SecureRandom rng = new SecureRandom();
        StringBuilder otp = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) {
            otp.append(OTP_ALPHABET.charAt(rng.nextInt(OTP_ALPHABET.length())));
        }
        return otp.toString();
    }
}
```

### VerificationResult.java

**File**: `apps/api/src/main/java/com/scheduler/api/auth/otp/VerificationResult.java`

```java
// TASK: P1-T06
public record VerificationResult(Status status) {
    public enum Status { SUCCESS, INVALID, EXPIRED }

    public static VerificationResult success()  { return new VerificationResult(Status.SUCCESS); }
    public static VerificationResult invalid()  { return new VerificationResult(Status.INVALID); }
    public static VerificationResult expired()  { return new VerificationResult(Status.EXPIRED); }

    public boolean isSuccess() { return status == Status.SUCCESS; }
}
```

### OtpRepository.java

**File**: `apps/api/src/main/java/com/scheduler/api/auth/otp/OtpRepository.java`

```java
// TASK: P1-T06
public interface OtpRepository extends JpaRepository<OtpRecord, UUID> {

    @Modifying
    @Query("""
        UPDATE OtpRecord o SET o.status = 'verified', o.verifiedAt = NOW()
        WHERE o.tenantId = :tenantId AND o.identifier = :identifier
          AND o.status = 'pending'
        """)
    void markVerified(@Param("tenantId") UUID tenantId,
                      @Param("identifier") String identifier);
}
```

---

## Integration Points

**Depends on**: ATOM-FLYWAY-MIGRATIONS-004 (`otp_records` table must exist); ATOM-SPRING-SECURITY-005 (`TenantContext` must be available); Redis running (ATOM-LOCAL-DEV-003)

**Enables**: ATOM-AUTH-CONTROLLER-009 (`AuthService.requestOtp()` and `verifyOtp()` call `OtpService`)

**Cascading updates required**:
- `docs/memory/domain-model.md` — add `OtpRecord` entity definition
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/.../auth/otp/OtpConstants.java` | New | OTP configuration constants |
| `apps/api/src/main/java/.../auth/otp/OtpService.java` | New | OTP generation and verification |
| `apps/api/src/main/java/.../auth/otp/OtpRecord.java` | New | JPA entity for otp_records |
| `apps/api/src/main/java/.../auth/otp/OtpRepository.java` | New | Data access with tenant-scoped update |
| `apps/api/src/main/java/.../auth/otp/VerificationResult.java` | New | Sealed result record |
| `apps/api/src/main/java/.../auth/otp/OtpRateLimitException.java` | New | Rate limit exception |
| `apps/api/src/test/java/.../auth/otp/OtpServiceTest.java` | New | Unit + integration tests |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] Zero JPA queries without `tenant_id` in WHERE clause
- [ ] Zero industry-specific terms in any identifier or API path
- [ ] OTP hash confirmed as bcrypt (not plaintext) via Redis inspection
- [ ] `rawOtp` confirmed `@Transient` — not in `otp_records` columns
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: otp-generation | Phase: 1*
