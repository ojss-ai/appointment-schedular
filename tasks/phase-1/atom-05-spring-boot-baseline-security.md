# ATOM-SPRING-SECURITY-005: Spring Boot Baseline and Security Config

**Status**: 🟡 Planned
**Feature**: spring-security
**Phase**: 1 (Foundation)
**Tags**: [AUTH]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-MONOREPO-SCAFFOLD-001, ATOM-LOCAL-DEV-003
**Blocks**: None
**PR**: TBD

---

## Overview

This atom wires Spring Boot 3.x's security layer: `SecurityConfig` (stateless JWT filter chain), `TenantContext` (ThreadLocal principal holder), `TenantGuard` (SpEL bean for `@PreAuthorize`), `TenantFilterAspect` (AOP service-layer guard), and `JwtAuthFilter` (OncePerRequestFilter). Together these components form the runtime enforcement boundary that every subsequent service-layer atom depends on. The key design decision is that tenant isolation is enforced at two levels — the Spring Security filter populates `TenantContext`, and the AOP aspect rejects any service call where `TenantContext` is absent.

---

## User Story

```
As a Tenant Admin
I want all API requests to be authenticated and tenant-scoped before reaching business logic
So that cross-tenant data access is structurally impossible at runtime
```

---

## Acceptance Criteria

- [ ] **AC-01**: `mvn spring-boot:run -Dspring-boot.run.profiles=local` starts with no errors
- [ ] **AC-02**: `GET /actuator/health` returns `{"status":"UP"}` with HTTP 200
- [ ] **AC-03**: `GET /api/v1/anything` without a JWT returns HTTP 401
- [ ] **AC-04**: A valid JWT causes `TenantContext` to be populated with correct `tenantId`, `userId`, and `roleClaims`
- [ ] **AC-05**: An invalid or expired JWT returns HTTP 401 (not 500)
- [ ] **AC-06**: `TenantFilterAspect` throws `IllegalStateException` when any `@Service` method is called with no `TenantContext` set
- [ ] **AC-07**: Application logs are structured JSON — `grep "tenantId" <logfile>` finds MDC-injected tenant context
- [ ] **AC-08**: `@WebMvcTest` for `SecurityConfig` verifies public endpoints permit unauthenticated access and protected endpoints reject it
- [ ] **AC-09 (Tenant isolation)**: `TenantContext.getTenantId()` returns non-null for every authenticated request; `TenantContext.clear()` is called in the `finally` block of `JwtAuthFilter`
- [ ] **AC-10 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) in any class name, field name, or endpoint path

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | Manual startup | `SchedulerApiApplication.java` | 🔜 Planned |
| AC-02 | `@WebMvcTest` / manual curl | `SecurityConfig.java` | 🔜 Planned |
| AC-03 | `@WebMvcTest` SecurityConfigTest | `SecurityConfig.java` | 🔜 Planned |
| AC-04 | Unit test JwtAuthFilterTest | `JwtAuthFilter.java` | 🔜 Planned |
| AC-05 | Unit test JwtAuthFilterTest | `JwtAuthFilter.java` | 🔜 Planned |
| AC-06 | Unit test TenantFilterAspectTest | `TenantFilterAspect.java` | 🔜 Planned |
| AC-07 | Manual log grep | `application.yml` logging pattern | 🔜 Planned |
| AC-08 | `SecurityConfigTest.java` | `SecurityConfig.java` | 🔜 Planned |
| AC-09 | Unit test JwtAuthFilterTest | `JwtAuthFilter.java` | 🔜 Planned |
| AC-10 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 10 criteria rewritten, 10 marked TBD -->

---

## Technical Design

### Architecture

The security pipeline flows: `JwtAuthFilter` (extracts and validates Bearer token) → `TenantContext.set()` (populates ThreadLocal) → `SecurityContextHolder` (Spring Authentication object) → `TenantFilterAspect` (AOP before-advice on all `@Service` classes). `TenantGuard` is a Spring bean registered under the name `"tenantGuard"` for use in SpEL `@PreAuthorize` expressions on controllers. `TenantContext.clear()` is always called in the `JwtAuthFilter` finally block to prevent ThreadLocal leaks across pooled threads.

### Data Flow / Sequence (if applicable)

```
HTTP request with Bearer token
  → JwtAuthFilter.doFilterInternal()
      → jwtService.validateToken(token) → JwtClaims
      → TenantContext.set(tenantId, userId, roleClaims)
      → SecurityContextHolder.setAuthentication(TenantAwarePrincipal)
  → DispatcherServlet → Controller
      → @PreAuthorize("@tenantGuard.check(#tenantId)") — path variable check
  → @Service method
      → TenantFilterAspect @Before — null-context guard
  → finally: TenantContext.clear()
```

### File Structure

```
apps/api/src/main/java/com/scheduler/api/
├── config/
│   ├── SecurityConfig.java
│   ├── RedisConfig.java
│   └── KafkaConfig.java
├── security/
│   ├── JwtAuthFilter.java
│   ├── TenantAwarePrincipal.java
│   └── TenantContext.java
├── tenant/
│   ├── TenantGuard.java
│   └── TenantFilterAspect.java
└── SchedulerApiApplication.java

apps/api/src/main/resources/
└── application.yml

apps/api/src/test/java/com/scheduler/api/
└── security/
    └── SecurityConfigTest.java
```

### Interface Contracts

```java
// TenantContext — static ThreadLocal holder
public class TenantContext {
    public static void set(UUID tenantId, UUID userId, List<String> roles);
    public static UUID getTenantId();
    public static UUID getUserId();
    public static List<String> getRoles();
    public static void clear();
}

// TenantGuard — SpEL bean
@Component("tenantGuard")
public class TenantGuard {
    public boolean check(UUID requestedTenantId);
}

// TenantAwarePrincipal — Spring Authentication principal
public record TenantAwarePrincipal(JwtClaims claims) {}

// JwtAuthFilter — extends OncePerRequestFilter
@Component
public class JwtAuthFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException;
}
```

### Design Rationale

- **ADR-004**: Row-level multi-tenancy requires that `tenant_id` is always available in the service layer — `TenantContext` provides this without passing it as a parameter to every method.
- The AOP aspect (`TenantFilterAspect`) is a second enforcement line: even if a future developer forgets `@PreAuthorize` on a controller method, the service layer will reject the call.
- `@EnableMethodSecurity` enables `@PreAuthorize` on controller methods; `STATELESS` session policy ensures no `HttpSession` is created.

---

## Test Strategy

**Test type**: Unit (JUnit 5 + Mockito) and API (`@WebMvcTest`)

```
- shouldReturn401_forUnauthenticatedRequest:
    Given: no Authorization header
    Assert: GET /api/v1/bookings returns HTTP 401

- shouldReturn200_forActuatorHealth:
    Given: no Authorization header
    Assert: GET /actuator/health returns HTTP 200

- shouldPopulateTenantContext_forValidJwt:
    Given: valid signed JWT with tenantId claim
    Assert: TenantContext.getTenantId() equals the tenantId in the token after filter runs

- shouldClearTenantContext_afterRequestCompletes:
    Given: request processed with valid JWT
    Assert: TenantContext.getTenantId() is null after filter's finally block executes

- shouldThrow_whenServiceCalledWithoutContext:
    Given: TenantFilterAspect active, TenantContext not set
    Assert: calling any @Service method throws IllegalStateException
```

**Coverage requirements**:
- Line coverage ≥ 80% on `JwtAuthFilter`, `TenantGuard`, `TenantFilterAspect`
- All security tests must pass before any controller atom begins

---

## Implementation Constraints

- Session policy must be `STATELESS` — no `HttpSession` created
- `TenantContext.clear()` must be in a `finally` block — never skipped
- `@EnableMethodSecurity` required on `SecurityConfig`
- All controller endpoints must carry `@PreAuthorize("@tenantGuard.check(#tenantId)")`
- JWT auth endpoints (`/api/v1/auth/**`) and `/actuator/health` must be `permitAll()`
- No direct Kafka writes — use `outboxService.writeBookingEvent()` within the caller's transaction
- No `console.log` in Next.js; no `System.out.println` in Java — use pino / SLF4J
- Structured logging pattern must inject `tenantId` from MDC

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `SecurityConfigTest.java` with `@WebMvcTest(SecurityConfig.class)`
2. Write `shouldReturn401_forUnauthenticatedRequest` — fails (no filter wired yet)
3. Write `shouldReturn200_forActuatorHealth` — fails
4. Create `JwtAuthFilterTest.java`, write `shouldPopulateTenantContext_forValidJwt` — fails

### GREEN — Minimum code to pass

1. Create `SecurityConfig.java` with filter chain, `permitAll` rules, `STATELESS` session
2. Create `TenantContext.java` with ThreadLocal holders and clear()
3. Create `TenantGuard.java` with `check()` method
4. Create `TenantFilterAspect.java` with `@Before` advice on `@Service` classes
5. Create `JwtAuthFilter.java` wired to `JwtService` (stub at this stage)
6. Create `application.yml` with full base config
7. Run tests — all pass

### REFACTOR — Quality pass

1. Add structured logging MDC setup (`MDC.put("tenantId", ...)`) in `JwtAuthFilter`
2. Add Javadoc to all `public` methods in security classes
3. Run `/security-scan` on the security package
4. Verify `TenantContext.clear()` is in `finally` via code review

---

## Implementation Reference

### SecurityConfig.java

**File**: `apps/api/src/main/java/com/scheduler/api/config/SecurityConfig.java`

```java
// TASK: P1-T05
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtFilter) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(POST, "/api/v1/auth/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
```

### TenantContext.java

**File**: `apps/api/src/main/java/com/scheduler/api/security/TenantContext.java`

```java
// TASK: P1-T05
public class TenantContext {
    private static final ThreadLocal<UUID> TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<UUID> USER_ID   = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> ROLES = new ThreadLocal<>();

    public static void set(UUID tenantId, UUID userId, List<String> roles) {
        TENANT_ID.set(tenantId);
        USER_ID.set(userId);
        ROLES.set(roles);
    }

    public static UUID getTenantId() { return TENANT_ID.get(); }
    public static UUID getUserId()   { return USER_ID.get(); }
    public static List<String> getRoles() { return ROLES.get(); }

    public static void clear() {
        TENANT_ID.remove();
        USER_ID.remove();
        ROLES.remove();
    }
}
```

### TenantGuard.java

**File**: `apps/api/src/main/java/com/scheduler/api/tenant/TenantGuard.java`

```java
// TASK: P1-T05
@Component("tenantGuard")
public class TenantGuard {
    public boolean check(UUID requestedTenantId) {
        UUID contextTenantId = TenantContext.getTenantId();
        if (contextTenantId == null) return false;
        return contextTenantId.equals(requestedTenantId);
    }
}
```

### TenantFilterAspect.java

**File**: `apps/api/src/main/java/com/scheduler/api/tenant/TenantFilterAspect.java`

```java
// TASK: P1-T05
@Aspect
@Component
public class TenantFilterAspect {

    @Before("@within(org.springframework.stereotype.Service)")
    public void enforceTenantContext(JoinPoint jp) {
        if (TenantContext.getTenantId() == null) {
            throw new IllegalStateException(
                "TenantContext not initialized before service call: " + jp.getSignature());
        }
    }
}
```

### JwtAuthFilter.java

**File**: `apps/api/src/main/java/com/scheduler/api/security/JwtAuthFilter.java`

```java
// TASK: P1-T05
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                JwtClaims claims = jwtService.validateToken(token);
                TenantContext.set(claims.tenantId(), claims.userId(), claims.roleClaims());
                UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                        new TenantAwarePrincipal(claims), null,
                        claims.roleClaims().stream()
                              .map(SimpleGrantedAuthority::new).toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException e) {
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
        }
    }
}
```

### application.yml

**File**: `apps/api/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: scheduler-api
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/scheduler}
    username: ${DB_USER:scheduler}
    password: ${DB_PASSWORD:scheduler_dev}
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:10}
      minimum-idle: 2
      connection-timeout: 30000
      idle-timeout: 600000
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
  flyway:
    enabled: true
    locations: classpath:db/migration
    validate-on-migrate: true
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}

app:
  jwt:
    secret: ${JWT_SECRET}
    expiry-hours: ${JWT_EXPIRY_HOURS:24}

management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: never

logging:
  pattern:
    console: '{"time":"%d{ISO8601}","level":"%p","logger":"%logger{36}","msg":"%msg","tenant":"%X{tenantId}"}%n'
```

---

## Integration Points

**Depends on**: ATOM-MONOREPO-SCAFFOLD-001 (Spring Boot project must exist); ATOM-LOCAL-DEV-003 (`application-local.yml` in place); ATOM-FLYWAY-MIGRATIONS-004 (schema needed for `ddl-auto: validate`)

**Enables**: ATOM-OTP-006, ATOM-JWT-007, ATOM-NOTIFICATION-008, ATOM-AUTH-CONTROLLER-009 — all depend on `TenantContext`, `TenantGuard`, and `JwtAuthFilter` being in place

**Cascading updates required**:
- `docs/memory/security-rules.md` — add tenant isolation pattern and JWT claim structure
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/.../config/SecurityConfig.java` | New | Filter chain, method security |
| `apps/api/src/main/java/.../security/TenantContext.java` | New | ThreadLocal tenant holder |
| `apps/api/src/main/java/.../security/TenantAwarePrincipal.java` | New | Authentication principal record |
| `apps/api/src/main/java/.../security/JwtAuthFilter.java` | New | Bearer token extraction and validation |
| `apps/api/src/main/java/.../tenant/TenantGuard.java` | New | SpEL PreAuthorize bean |
| `apps/api/src/main/java/.../tenant/TenantFilterAspect.java` | New | AOP service-layer guard |
| `apps/api/src/main/resources/application.yml` | New | Base application config |
| `apps/api/src/test/java/.../security/SecurityConfigTest.java` | New | WebMvcTest for security rules |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] Zero JPA queries without `tenant_id` in WHERE clause
- [ ] Zero industry-specific terms in any identifier or API path
- [ ] `@PreAuthorize` present on every new `@RestController` method
- [ ] `TenantContext.clear()` confirmed in `finally` block
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: spring-security | Phase: 1*
