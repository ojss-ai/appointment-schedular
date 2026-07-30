# ATOM-JWT-BUILDER-007: JWT Issuance and Verification Service

**Status**: ✅ Complete
**Feature**: jwt-builder
**Phase**: 1 (Foundation)
**Tags**: [AUTH]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-SPRING-SECURITY-005
**Blocks**: None
**PR**: TBD

---

## Overview

This atom implements `JwtService` — the component responsible for generating signed JWTs on successful OTP verification and validating incoming tokens in `JwtAuthFilter`. All tokens use HS256 with a secret sourced exclusively from the `app.jwt.secret` environment variable. The key design decision is that the JWT carries both `sub` (userId) and a custom `tenantId` claim, making every token intrinsically scoped to a single tenant without requiring a database lookup on every request.

---

## User Story

```
As a Booking User
I want to receive a signed JWT after verifying my OTP
So that I can authenticate subsequent API requests without re-entering credentials
```

---

## Acceptance Criteria

- [ ] **AC-01**: Generated token contains all 9 required claims (`sub`, `iss`, `aud`, `iat`, `exp`, `jti`, `tenantId`, `userId`, `roleClaims`)
- [ ] **AC-02**: Valid token passes `validateToken()` and returns a `JwtClaims` record with correct field values
- [ ] **AC-03**: Token with invalid signature returns HTTP 401 (not 500)
- [ ] **AC-04**: Expired token returns HTTP 401
- [ ] **AC-05**: Token with wrong `aud` claim returns HTTP 401
- [ ] **AC-06**: Tampered payload (claims changed, signature not regenerated) returns HTTP 401
- [ ] **AC-07**: All 6 unit test scenarios pass
- [ ] **AC-08**: JWT secret sourced from `app.jwt.secret` environment variable — never hardcoded in source
- [ ] **AC-09**: Application startup fails if `app.jwt.secret` is shorter than 32 bytes
- [ ] **AC-10 (Tenant isolation)**: `JwtClaims.tenantId()` is always non-null for any successfully validated token
- [ ] **AC-11 (Domain abstraction)**: No industry-specific terms in any claim name, class name, or field name

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `JwtServiceTest.java` | `JwtService.generateToken()` | 🔜 Planned |
| AC-02 | `JwtServiceTest.java` | `JwtService.validateToken()` | 🔜 Planned |
| AC-03 | `JwtServiceTest.java` | `JwtService.validateToken()` | 🔜 Planned |
| AC-04 | `JwtServiceTest.java` | `JwtService.validateToken()` | 🔜 Planned |
| AC-05 | `JwtServiceTest.java` | `JwtService.validateToken()` | 🔜 Planned |
| AC-06 | `JwtServiceTest.java` | `JwtService.validateToken()` | 🔜 Planned |
| AC-07 | `JwtServiceTest.java` | All JwtService methods | 🔜 Planned |
| AC-08 | Code review + CI secret scan | `JwtService.java` | 🔜 Planned |
| AC-09 | `JwtPropertiesValidationTest.java` | `JwtProperties` validation | 🔜 Planned |
| AC-10 | TBD | TBD | 🔜 Planned |
| AC-11 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 11 criteria rewritten, 11 marked TBD -->

---

## Technical Design

### Architecture

`JwtService` is a stateless Spring service that wraps the JJWT 0.12.x library. `JwtProperties` is a `@ConfigurationProperties` record that binds `app.jwt.*` from `application.yml`. The service exposes three top-level methods: `generateToken()`, `validateToken()`, and convenience extractors. `JwtAuthFilter` (atom-05) calls `validateToken()` — this atom completes that stub.

### Data Flow / Sequence (if applicable)

```
AuthService.verifyOtp() → VerificationResult.SUCCESS
  → jwtService.generateToken(userId, tenantId, roleClaims)
      → Jwts.builder() ... .signWith(signingKey, HS256) .compact()
      → returns JWT string

Incoming HTTP request
  → JwtAuthFilter
      → jwtService.validateToken(token)
          → Jwts.parser().verifyWith(signingKey)...parseSignedClaims(token)
          → returns JwtClaims record
      → TenantContext.set(tenantId, userId, roleClaims)
```

### File Structure

```
apps/api/src/main/java/com/scheduler/api/security/jwt/
├── JwtService.java
├── JwtClaims.java        ← record
├── JwtProperties.java    ← @ConfigurationProperties record
└── (JwtAuthFilter.java   ← scaffolded in atom-05; completed here)

apps/api/src/test/java/com/scheduler/api/security/jwt/
└── JwtServiceTest.java
```

### Interface Contracts

```java
// JwtProperties — config properties record
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
    String secret,      // minimum 256-bit base64 string
    int expiryHours     // default 24
) {}

// JwtClaims — parsed token data
public record JwtClaims(
    UUID         tenantId,
    UUID         userId,
    List<String> roleClaims,
    String       jti,
    Instant      issuedAt,
    Instant      expiresAt
) {}

// JwtService — public contract
@Service
public class JwtService {
    public String generateToken(UUID userId, UUID tenantId, List<String> roleClaims);
    public JwtClaims validateToken(String token);     // throws JwtException on any failure
    public UUID extractTenantId(String token);
    public UUID extractUserId(String token);
    public List<String> extractRoleClaims(String token);
}
```

### Design Rationale

- **ADR-004**: The `tenantId` custom claim means every validated request has the tenant available in-memory without a DB call — critical for the AOP tenant guard.
- HS256 is chosen over RS256 because this is an internal service (no third-party token consumers). RS256 would add key management complexity for no benefit at this stage.
- `jti` (JWT ID) is included to support future token revocation via a blocklist in Redis.
- The `aud` claim is validated on parse to prevent tokens issued for other services from being accepted.

---

## Test Strategy

**Test type**: Unit (JUnit 5 + Mockito)

```
- shouldGenerateToken_withAllRequiredClaims:
    Given: valid userId, tenantId, roleClaims
    Assert: decoded JWT contains sub, iss="scheduler-api", aud="scheduler-clients", iat, exp, jti, tenantId, userId, roleClaims

- shouldValidateToken_andReturnCorrectClaims:
    Given: token generated by generateToken()
    Assert: validateToken() returns JwtClaims with tenantId and userId matching inputs

- shouldThrowJwtException_forExpiredToken:
    Given: token with exp set to 1 second in the past
    Assert: validateToken() throws JwtException

- shouldThrowJwtException_forWrongAudience:
    Given: token built with aud = "wrong-clients"
    Assert: validateToken() throws JwtException

- shouldThrowJwtException_forTamperedPayload:
    Given: token with base64-decoded payload modified (tenantId changed) but original signature retained
    Assert: validateToken() throws JwtException

- shouldThrowJwtException_forMissingTenantIdClaim:
    Given: token built without tenantId claim
    Assert: validateToken() throws NullPointerException or JwtException
```

**Coverage requirements**:
- Line coverage ≥ 80% on `JwtService`
- All 6 test scenarios must pass before `JwtAuthFilter` is considered complete

---

## Implementation Constraints

- JWT library: JJWT 0.12.6 (`jjwt-api`, `jjwt-impl`, `jjwt-jackson`)
- Signing algorithm: HS256 only
- Secret sourced from `app.jwt.secret` — never hardcoded
- Minimum secret length: 32 bytes — throw on startup if shorter
- All 9 claims must be present in every generated token
- `validateToken()` must throw `JwtException` (not return null) for any invalid token
- No `System.out.println` — use SLF4J
- DTOs must be Java 21 records

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `JwtServiceTest.java` with all 6 test methods
2. Run tests — fail because `JwtService`, `JwtClaims`, `JwtProperties` don't exist yet

### GREEN — Minimum code to pass

1. Create `JwtProperties.java` configuration properties record
2. Create `JwtClaims.java` record
3. Implement `JwtService.java` — `generateToken()` and `validateToken()`
4. Add `@ConfigurationPropertiesScan` or `@EnableConfigurationProperties(JwtProperties.class)` to main application class
5. Run `JwtServiceTest` — all 6 pass

### REFACTOR — Quality pass

1. Add startup validation: throw `IllegalStateException` if `props.secret()` length < 32 bytes
2. Add `extractTenantId()`, `extractUserId()`, `extractRoleClaims()` convenience methods
3. Run `/security-scan` to confirm no hardcoded secrets

---

## Implementation Reference

### JwtProperties.java

**File**: `apps/api/src/main/java/com/scheduler/api/security/jwt/JwtProperties.java`

```java
// TASK: P1-T07
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
    String secret,          // minimum 256-bit base64 string
    int expiryHours         // default 24
) {}
```

### JwtClaims.java

**File**: `apps/api/src/main/java/com/scheduler/api/security/jwt/JwtClaims.java`

```java
// TASK: P1-T07
public record JwtClaims(
    UUID   tenantId,
    UUID   userId,
    List<String> roleClaims,
    String jti,
    Instant issuedAt,
    Instant expiresAt
) {}
```

### JwtService.java

**File**: `apps/api/src/main/java/com/scheduler/api/security/jwt/JwtService.java`

```java
// TASK: P1-T07
@Service
@RequiredArgsConstructor
public class JwtService {

    private final JwtProperties props;

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(
            Decoders.BASE64.decode(props.secret()));
    }

    /** Generate a signed JWT with all required claims. */
    public String generateToken(UUID userId, UUID tenantId, List<String> roleClaims) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant exp = now.plus(props.expiryHours(), ChronoUnit.HOURS);

        return Jwts.builder()
            .id(jti)
            .subject(userId.toString())
            .issuer("scheduler-api")
            .audience().add("scheduler-clients").and()
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .claim("tenantId",   tenantId.toString())
            .claim("userId",     userId.toString())
            .claim("roleClaims", roleClaims)
            .signWith(signingKey(), Jwts.SIG.HS256)
            .compact();
    }

    /** Validate and parse a JWT. Throws JwtException on any failure. */
    public JwtClaims validateToken(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(signingKey())
            .requireIssuer("scheduler-api")
            .requireAudience("scheduler-clients")
            .build()
            .parseSignedClaims(token)
            .getPayload();

        return new JwtClaims(
            UUID.fromString(claims.get("tenantId", String.class)),
            UUID.fromString(claims.get("userId",   String.class)),
            claims.get("roleClaims", List.class),
            claims.getId(),
            claims.getIssuedAt().toInstant(),
            claims.getExpiration().toInstant()
        );
    }

    public UUID extractTenantId(String token) { return validateToken(token).tenantId(); }
    public UUID extractUserId(String token)   { return validateToken(token).userId(); }
    public List<String> extractRoleClaims(String token) { return validateToken(token).roleClaims(); }
}
```

### Required JWT claims

| Claim | Type | Description |
|---|---|---|
| `sub` | String (UUID) | User ID |
| `iss` | String | `"scheduler-api"` |
| `aud` | String | `"scheduler-clients"` |
| `iat` | NumericDate | Issued at |
| `exp` | NumericDate | Expiry (now + expiryHours) |
| `jti` | String (UUID) | Unique token ID |
| `tenantId` | String (UUID) | Tenant identifier |
| `userId` | String (UUID) | User identifier (mirrors sub) |
| `roleClaims` | Array of strings | e.g. `["ROLE_ADMIN"]` |

---

## Integration Points

**Depends on**: ATOM-SPRING-SECURITY-005 (`JwtAuthFilter` stub is present; this atom completes it)

**Enables**: ATOM-AUTH-CONTROLLER-009 (`AuthService.verifyOtp()` calls `jwtService.generateToken()`)

**Cascading updates required**:
- `docs/memory/security-rules.md` — add JWT claim structure
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/.../security/jwt/JwtProperties.java` | New | Config properties record |
| `apps/api/src/main/java/.../security/jwt/JwtClaims.java` | New | Parsed token data record |
| `apps/api/src/main/java/.../security/jwt/JwtService.java` | New | Token generation and validation |
| `apps/api/src/main/java/.../security/JwtAuthFilter.java` | Modified | Wired to JwtService (completing atom-05 stub) |
| `apps/api/src/test/java/.../security/jwt/JwtServiceTest.java` | New | All 6 JWT scenarios |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] All 9 JWT claims present (verified by decoding token in test)
- [ ] Zero industry-specific terms in any identifier or claim name
- [ ] JWT secret not hardcoded — sourced from environment variable
- [ ] Startup validation for minimum secret length in place
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: jwt-builder | Phase: 1*
