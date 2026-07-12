# ATOM-AUTH-FLOW-009: Auth Controller and Integration Tests

**Status**: 🟡 Planned
**Feature**: auth-flow-controller
**Phase**: 1 (Foundation)
**Tags**: [AUTH] [TEST]
**Complexity**: High
**Agent**: coder
**Dependencies**: ATOM-OTP-REDIS-006, ATOM-JWT-BUILDER-007, ATOM-NOTIFICATION-DISPATCH-008
**Blocks**: None
**PR**: TBD

---

## Overview

This atom implements `AuthController` (two endpoints: `POST /api/v1/auth/request-otp` and `POST /api/v1/auth/verify-otp`), `AuthService` (orchestrates OTP, dispatch, and JWT issuance), and the full Testcontainers integration test suite covering 7 scenarios. `AuthController` intentionally never reveals whether an identifier exists — it returns `202 ACCEPTED` for `request-otp` regardless of whether the user is found. The key design decision is that dispatch failures silently log and return success to the caller — OTP generation has already succeeded, and dispatch is a best-effort delivery.

---

## User Story

```
As a Booking User
I want to request an OTP and verify it to receive a JWT
So that I can authenticate with the scheduling system without a password
```

---

## Acceptance Criteria

- [ ] **AC-01**: All 7 integration test scenarios pass (happy path email, happy path phone, expired OTP, already-used OTP, wrong OTP, rate limit 429, invalid format 400)
- [ ] **AC-02**: `POST /api/v1/auth/request-otp` accepts both email and phone identifiers
- [ ] **AC-03**: JWT returned on `SUCCESS` contains `tenantId`, `userId`, and `roleClaims` claims
- [ ] **AC-04**: Testcontainers PostgreSQL and Redis containers start and stop cleanly in CI
- [ ] **AC-05**: Test coverage ≥ 90% on `AuthController`, `AuthService`, `OtpService`, `JwtService`
- [ ] **AC-06**: Rate limit exceeded returns HTTP 429 (not 500 or 400)
- [ ] **AC-07**: Dispatch failure does NOT cause 5xx — `request-otp` returns `202 ACCEPTED`
- [ ] **AC-08**: Identifier masked in `RequestOtpResponse` — raw identifier never returned
- [ ] **AC-09 (Tenant isolation)**: All tenant lookups use `tenantSlug` — never accept a raw `tenantId` from the request body; JWT `tenantId` is authoritative after login
- [ ] **AC-10 (Domain abstraction)**: No industry-specific terms in any DTO field name, endpoint path, or response body

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `AuthControllerIT.java` | All auth classes | 🔜 Planned |
| AC-02 | `AuthControllerIT.java` | `AuthController.requestOtp()` | 🔜 Planned |
| AC-03 | `AuthControllerIT.java` | `AuthService.verifyOtp()` + `JwtService` | 🔜 Planned |
| AC-04 | `AuthControllerIT.java` | Testcontainers config | 🔜 Planned |
| AC-05 | JaCoCo report | `AuthController`, `AuthService`, `OtpService`, `JwtService` | 🔜 Planned |
| AC-06 | `AuthControllerIT.java` | `OtpService.checkRateLimit()` | 🔜 Planned |
| AC-07 | `AuthControllerIT.java` | `AuthService.requestOtp()` | 🔜 Planned |
| AC-08 | `AuthControllerIT.java` | `AuthController.maskIdentifier()` | 🔜 Planned |
| AC-09 | `AuthControllerIT.java` | `AuthService` tenant lookup | 🔜 Planned |
| AC-10 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 10 criteria rewritten, 10 marked TBD -->

---

## Technical Design

### Architecture

`AuthController` is a `@RestController` with no `@PreAuthorize` (auth endpoints are public by design — configured in `SecurityConfig`). It delegates all logic to `AuthService`, which orchestrates `TenantRepository`, `UserRepository`, `OtpService`, `DispatchService`, and `JwtService`. No business logic lives in the controller. `AuthService` uses `userRepository.findOrCreate()` to provision a user record on first successful OTP verification — the system is passwordless, so users are created lazily.

### Data Flow / Sequence (if applicable)

```
POST /api/v1/auth/request-otp
  → AuthController.requestOtp(RequestOtpRequest)
  → AuthService.requestOtp(identifier, tenantSlug)
      → tenantRepository.findBySlug(tenantSlug) → Tenant
      → otpService.generateAndStore(identifier, tenantId, identifierType) → OtpRecord
      → dispatchService.dispatch(identifier, identifierType, rawOtp, tenantName) [fire-and-forget on failure]
  → ResponseEntity.accepted(RequestOtpResponse)

POST /api/v1/auth/verify-otp
  → AuthController.verifyOtp(VerifyOtpRequest)
  → AuthService.verifyOtp(identifier, tenantSlug, submittedOtp)
      → tenantRepository.findBySlug(tenantSlug) → Tenant
      → otpService.verify(identifier, submittedOtp, tenantId) → VerificationResult
      → switch SUCCESS: userRepository.findOrCreate() → jwtService.generateToken() → JWT
      → switch INVALID/EXPIRED: error VerifyOtpResponse
  → ResponseEntity.ok(VerifyOtpResponse)
```

### File Structure

```
apps/api/src/main/java/com/scheduler/api/auth/
├── AuthController.java
├── AuthService.java
└── dto/
    ├── RequestOtpRequest.java    ← record
    ├── RequestOtpResponse.java   ← record
    ├── VerifyOtpRequest.java     ← record
    └── VerifyOtpResponse.java    ← record

apps/api/src/test/java/com/scheduler/api/auth/
└── AuthControllerIT.java
```

### Interface Contracts

```java
// Request DTOs
public record RequestOtpRequest(
    @NotBlank @Size(max = 255) String identifier,
    @NotBlank @Size(max = 100) String tenantSlug
) {}

public record VerifyOtpRequest(
    @NotBlank String identifier,
    @NotBlank String tenantSlug,
    @NotBlank @Size(min = 6, max = 6) String otp
) {}

// Response DTOs
public record RequestOtpResponse(
    String status,              // "OTP_SENT"
    String maskedIdentifier,    // e.g. "us***@example.com"
    Instant expiresAt
) {}

public record VerifyOtpResponse(
    String status,   // "SUCCESS" | "OTP_INVALID" | "OTP_EXPIRED"
    String token,    // null on failure
    String message   // null on success
) {}

// AuthService — public contract
@Service
public class AuthService {
    @Transactional
    public void requestOtp(String identifier, String tenantSlug);
    @Transactional
    public VerifyOtpResponse verifyOtp(String identifier, String tenantSlug, String submittedOtp);
}
```

### Design Rationale

- **ADR-004**: Tenant is resolved by `slug` (a human-readable key from the request) — never by `tenantId` in the request body. Post-login, `tenantId` comes from the JWT only.
- `findOrCreate()` pattern for users supports the passwordless flow — the first successful OTP verification creates the user record atomically.
- Dispatch failure is logged but not surfaced to the caller to prevent timing attacks that could reveal whether a user/identifier exists.
- HTTP 429 (not 400) for rate limiting follows RFC 6585 and is distinct from validation errors.

---

## Test Strategy

**Test type**: Integration (Testcontainers PostgreSQL + Redis) and API (`@SpringBootTest` + `RANDOM_PORT`)

```
- happyPathEmail_requestAndVerify_returnsJwt:
    Given: tenant "mc-clinic" exists; email "user@example.com" submits OTP flow
    Assert: verify-otp returns HTTP 200 with non-null token; JWT contains tenantId and userId

- happyPathPhone_requestAndVerify_returnsJwt:
    Given: tenant "mc-clinic" exists; phone "+15551234567" submits OTP flow
    Assert: verify-otp returns HTTP 200 with non-null token

- expiredOtp_returns401WithOTP_EXPIRED:
    Given: OTP generated; Redis TTL forcibly expired; correct OTP submitted
    Assert: verify-otp returns HTTP 401; body.status = "OTP_EXPIRED"

- alreadyUsedOtp_returns401WithOTP_INVALID:
    Given: correct OTP verified once (Redis key deleted); same OTP submitted again
    Assert: second verify-otp returns HTTP 401; body.status = "OTP_EXPIRED" (key gone)

- wrongOtp_returns401WithOTP_INVALID:
    Given: OTP generated; wrong code submitted
    Assert: verify-otp returns HTTP 401; body.status = "OTP_INVALID"

- sixthRequest_returns429:
    Given: request-otp called 5 times for same identifier within 1 hour
    Assert: 6th request returns HTTP 429

- invalidIdentifierFormat_returns400:
    Given: request-otp body with blank identifier
    Assert: returns HTTP 400 with validation error
```

**Coverage requirements**:
- Line coverage ≥ 90% on `AuthController`, `AuthService`, `OtpService`, `JwtService`
- All 7 integration test scenarios must pass in CI
- Testcontainers must use PostgreSQL 15 and Redis 7

---

## Implementation Constraints

- `AuthController` endpoints must be `permitAll()` in `SecurityConfig` (no `@PreAuthorize` on auth endpoints)
- `AuthService` methods must be `@Transactional`
- DTOs must be Java 21 records with Bean Validation annotations
- Raw identifier must never appear in `RequestOtpResponse` — always masked
- Dispatch failure must not cause 5xx — catch exception in `AuthService.requestOtp()` and log
- Rate limit returns HTTP 429 — map `OtpRateLimitException` to 429 in `@ExceptionHandler` or `@ControllerAdvice`
- JWT returned in `VerifyOtpResponse.token` — never in a cookie from the API layer (cookie storage is the Next.js concern)
- No `System.out.println` — use SLF4J

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `AuthControllerIT.java` with Testcontainers setup
2. Write all 7 test method stubs — fail (controller doesn't exist)
3. Confirm Testcontainers containers start and are healthy

### GREEN — Minimum code to pass

1. Create `RequestOtpRequest.java`, `VerifyOtpRequest.java`, `RequestOtpResponse.java`, `VerifyOtpResponse.java` DTOs
2. Implement `AuthService.java` with `requestOtp()` and `verifyOtp()`
3. Implement `AuthController.java` with both endpoints and `maskIdentifier()`
4. Add `OtpRateLimitException` → HTTP 429 mapping in `@ControllerAdvice`
5. Run `AuthControllerIT` — all 7 tests pass

### REFACTOR — Quality pass

1. Extract `maskIdentifier()` to a utility class for reuse
2. Add structured logging for auth events (OTP_REQUESTED, OTP_VERIFIED, OTP_FAILED)
3. Run JaCoCo — confirm ≥ 90% coverage on all 4 target classes
4. Run `/security-scan` on auth package

---

## Implementation Reference

### AuthController.java

**File**: `apps/api/src/main/java/com/scheduler/api/auth/AuthController.java`

```java
// TASK: P1-T09
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    /**
     * POST /api/v1/auth/request-otp
     * Body: { "identifier": "user@example.com", "tenantSlug": "mc-clinic" }
     */
    @PostMapping("/request-otp")
    public ResponseEntity<RequestOtpResponse> requestOtp(
            @Valid @RequestBody RequestOtpRequest req) {
        authService.requestOtp(req.identifier(), req.tenantSlug());
        // Never reveal whether the identifier exists
        return ResponseEntity.accepted()
            .body(new RequestOtpResponse("OTP_SENT",
                maskIdentifier(req.identifier()),
                Instant.now().plusSeconds(300)));
    }

    /**
     * POST /api/v1/auth/verify-otp
     * Body: { "identifier": "user@example.com", "tenantSlug": "mc-clinic", "otp": "A3K9PQ" }
     */
    @PostMapping("/verify-otp")
    public ResponseEntity<VerifyOtpResponse> verifyOtp(
            @Valid @RequestBody VerifyOtpRequest req) {
        VerifyOtpResponse response = authService.verifyOtp(
            req.identifier(), req.tenantSlug(), req.otp());
        return ResponseEntity.ok(response);
    }

    private String maskIdentifier(String identifier) {
        if (identifier.contains("@")) {
            int at = identifier.indexOf('@');
            return identifier.substring(0, Math.min(2, at)) + "***" + identifier.substring(at);
        }
        return identifier.substring(0, 3) + "***" + identifier.substring(identifier.length() - 2);
    }
}
```

### AuthService.java

**File**: `apps/api/src/main/java/com/scheduler/api/auth/AuthService.java`

```java
// TASK: P1-T09
@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final TenantRepository tenantRepository;
    private final UserRepository   userRepository;
    private final OtpService       otpService;
    private final DispatchService  dispatchService;
    private final JwtService       jwtService;

    public void requestOtp(String identifier, String tenantSlug) {
        Tenant tenant = tenantRepository.findBySlug(tenantSlug)
            .orElseThrow(() -> new TenantNotFoundException(tenantSlug));

        String identifierType = identifier.contains("@") ? "email" : "phone";
        OtpRecord record = otpService.generateAndStore(identifier, tenant.getId(), identifierType);

        // Dispatch but don't fail the request if dispatch fails
        dispatchService.dispatch(identifier, identifierType, record.getRawOtp(), tenant.getName());
    }

    public VerifyOtpResponse verifyOtp(String identifier, String tenantSlug, String submittedOtp) {
        Tenant tenant = tenantRepository.findBySlug(tenantSlug)
            .orElseThrow(() -> new TenantNotFoundException(tenantSlug));

        VerificationResult result = otpService.verify(identifier, submittedOtp, tenant.getId());

        return switch (result.status()) {
            case SUCCESS -> {
                User user = userRepository.findOrCreate(identifier, tenant.getId());
                String token = jwtService.generateToken(user.getId(), tenant.getId(),
                    List.of("ROLE_" + user.getRole().toUpperCase()));
                yield new VerifyOtpResponse("SUCCESS", token, null);
            }
            case INVALID  -> new VerifyOtpResponse("OTP_INVALID",  null, "The code you entered is incorrect.");
            case EXPIRED  -> new VerifyOtpResponse("OTP_EXPIRED",  null, "The code has expired. Please request a new one.");
        };
    }
}
```

### AuthControllerIT.java

**File**: `apps/api/src/test/java/com/scheduler/api/auth/AuthControllerIT.java`

```java
// TASK: P1-T09
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class AuthControllerIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("scheduler_test")
        .withUsername("scheduler")
        .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Test void happyPathEmail_requestAndVerify_returnsJwt() { ... }
    @Test void happyPathPhone_requestAndVerify_returnsJwt() { ... }
    @Test void expiredOtp_returns401WithOTP_EXPIRED() { ... }
    @Test void alreadyUsedOtp_returns401WithOTP_INVALID() { ... }
    @Test void wrongOtp_returns401WithOTP_INVALID() { ... }
    @Test void sixthRequest_returns429() { ... }
    @Test void invalidIdentifierFormat_returns400() { ... }
}
```

### DTO records

**File**: `apps/api/src/main/java/com/scheduler/api/auth/dto/`

```java
// TASK: P1-T09
public record RequestOtpRequest(
    @NotBlank @Size(max = 255) String identifier,
    @NotBlank @Size(max = 100) String tenantSlug
) {}

public record VerifyOtpRequest(
    @NotBlank String identifier,
    @NotBlank String tenantSlug,
    @NotBlank @Size(min = 6, max = 6) String otp
) {}

public record RequestOtpResponse(
    String status,
    String maskedIdentifier,
    Instant expiresAt
) {}

public record VerifyOtpResponse(
    String status,
    String token,      // null on failure
    String message     // null on success
) {}
```

---

## Integration Points

**Depends on**: ATOM-OTP-REDIS-006 (`OtpService` complete); ATOM-JWT-BUILDER-007 (`JwtService` complete); ATOM-NOTIFICATION-DISPATCH-008 (`DispatchService` complete)

**Enables**: ATOM-NEXTJS-AUTH-010 (Next.js auth flow calls these two endpoints)

**Cascading updates required**:
- `docs/memory/api-contracts.md` — add `POST /api/v1/auth/request-otp` and `POST /api/v1/auth/verify-otp`
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/.../auth/AuthController.java` | New | REST endpoints for auth flow |
| `apps/api/src/main/java/.../auth/AuthService.java` | New | Auth orchestration logic |
| `apps/api/src/main/java/.../auth/dto/RequestOtpRequest.java` | New | Request DTO |
| `apps/api/src/main/java/.../auth/dto/VerifyOtpRequest.java` | New | Request DTO |
| `apps/api/src/main/java/.../auth/dto/RequestOtpResponse.java` | New | Response DTO |
| `apps/api/src/main/java/.../auth/dto/VerifyOtpResponse.java` | New | Response DTO |
| `apps/api/src/test/java/.../auth/AuthControllerIT.java` | New | 7-scenario integration test suite |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] All 7 integration test scenarios pass
- [ ] Zero JPA queries without `tenant_id` in WHERE clause
- [ ] Zero industry-specific terms in any identifier or API path
- [ ] `@PreAuthorize` present on every new `@RestController` method (N/A for auth endpoints — `permitAll()` documented in SecurityConfig)
- [ ] JaCoCo coverage ≥ 90% on AuthController, AuthService, OtpService, JwtService
- [ ] Rate limit returns HTTP 429 (not 400 or 500)
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: auth-flow-controller | Phase: 1*
