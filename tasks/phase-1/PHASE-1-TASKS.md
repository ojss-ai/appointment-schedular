# Phase 1 — Foundation & Scaffold
**Duration:** Weeks 1–3
**Milestone:** End-to-end auth works; all baseline DB migrations applied; local dev environment running

---

## P1-T01 — Monorepo Scaffold and Toolchain Setup
**Tags:** [INFRA]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Coder agent (DevOps mode)
**Depends on:** Nothing

### Specification
Create the top-level monorepo structure using a Turborepo or Nx workspace configuration.

**Target structure:**
```
appointment/
├── apps/
│   ├── web/          ← Next.js 15 (TypeScript)
│   └── api/          ← Spring Boot 3.x (Java 21, Maven)
├── services/
│   ├── notification-service/  ← Spring Boot (Java 21, Maven)
│   └── audit-service/         ← Spring Boot (Java 21, Maven)
├── infra/
│   ├── docker-compose.yml
│   ├── docker-compose.override.yml  ← local overrides
│   ├── kafka/
│   │   └── topics.sh
│   └── postgres/
│       └── init.sql
├── CLAUDE.md
├── AGENTS.md
└── package.json  ← root workspace
```

**Toolchain requirements:**
- Node 22 LTS (for Next.js)
- Java 21 (Eclipse Temurin / OpenJDK)
- Maven 3.9+
- Docker Desktop / Docker Engine with Compose v2
- pnpm 9+ as package manager for Node workspaces

**Acceptance criteria:**
- [ ] `pnpm install` succeeds at root
- [ ] `mvn compile` succeeds in `apps/api`
- [ ] `pnpm dev` in `apps/web` starts Next.js dev server
- [ ] Folder structure matches specification above
- [ ] `.gitignore` covers: `node_modules`, `target/`, `.env.local`, `*.class`

---

## P1-T02 — Claude Code Agent Configuration
**Tags:** [ORCHESTRATION]
**Priority:** P0
**Estimate:** 0.5 days
**Agent:** Orchestrator
**Depends on:** P1-T01

### Specification
Set up the `.claude/` directory with all sub-agent definitions, slash commands, hooks, and workflow templates.

**Atom file:** `atom-02-claude-code-agent-setup.md`

**Directory structure:**
```
.claude/
├── agents/         (7 sub-agent definitions)
├── commands/       (5 slash commands)
├── workflows/      (4 workflow templates)
└── settings.json   (PostToolUse/PreToolUse hooks)
```

**Memory namespace seed files** (in `docs/memory/`):
- `docs/memory/booking-patterns/.gitkeep`
- `docs/memory/README.md` — namespace conventions

**Acceptance criteria:**
- [ ] All 7 agent files present in `.claude/agents/`
- [ ] All 5 slash command files present in `.claude/commands/`
- [ ] All 4 workflow templates present in `.claude/workflows/`
- [ ] `settings.json` validates (no JSON syntax errors)
- [ ] `CLAUDE.md` loads correctly in a new Claude Code session
- [ ] `docs/memory/` directory initialized with README

---

## P1-T03 — Docker Compose Local Dev Environment
**Tags:** [INFRA]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Coder agent (DevOps mode)
**Depends on:** P1-T01

### Specification
Create a complete Docker Compose stack for local development that starts all infrastructure dependencies with a single command.

**Services required:**
```yaml
services:
  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: scheduler
      POSTGRES_USER: scheduler
      POSTGRES_PASSWORD: scheduler_dev
    ports: ["5432:5432"]
    volumes: ["postgres-data:/var/lib/postgresql/data"]

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    # KRaft mode (no ZooKeeper)
    ports: ["9092:9092"]

  schema-registry:
    image: confluentinc/cp-schema-registry:7.6.0
    ports: ["8081:8081"]
    depends_on: [kafka]

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    ports: ["8080:8080"]
    depends_on: [kafka, schema-registry]

  debezium:
    image: debezium/connect:2.6
    ports: ["8083:8083"]
    depends_on: [kafka, postgres]
    # Configure with postgres connector for outbox table
```

**Environment files:**
- `apps/api/src/main/resources/application-local.yml` — local Spring Boot config
- `apps/web/.env.local.example` — template for Next.js env vars

**Acceptance criteria:**
- [ ] `docker compose up -d` starts all 6 services without errors
- [ ] PostgreSQL accessible at `localhost:5432`
- [ ] Redis accessible at `localhost:6379`
- [ ] Kafka accessible at `localhost:9092`
- [ ] Schema Registry accessible at `http://localhost:8081`
- [ ] Kafka UI accessible at `http://localhost:8080`
- [ ] Debezium Connect REST API accessible at `http://localhost:8083`
- [ ] `docker compose down` cleans up all containers

---

## P1-T04 — PostgreSQL Baseline Schema Migrations (V001–V009)
**Tags:** [MIGRATION]
**Priority:** P0
**Estimate:** 1.5 days
**Agent:** Migrations agent
**Depends on:** P1-T03

### Specification
Create all Flyway migration files for the baseline schema (tables V001–V009 as defined in `docs/DATABASE-SCHEMA.md`). This excludes `bookings` and event-related tables (those are Phase 3).

**Migration files to create (in `apps/api/src/main/resources/db/migration/`):**
- `V001__create_tenants.sql`
- `V002__create_users.sql`
- `V003__create_otp_records.sql`
- `V004__create_locations.sql`
- `V005__create_branch_holidays.sql`
- `V006__create_resources.sql`
- `V007__create_resource_schedules.sql`
- `V008__create_resource_breaks.sql`
- `V009__create_service_types.sql`

Each migration must match the DDL spec in `docs/DATABASE-SCHEMA.md` exactly.

**Acceptance criteria:**
- [ ] `mvn flyway:migrate` runs clean from empty DB
- [ ] All 9 tables exist in the `scheduler` database
- [ ] All indexes from `docs/DATABASE-SCHEMA.md` section 3 exist
- [ ] `mvn flyway:validate` passes (no checksum mismatches)
- [ ] Re-running migration is idempotent (Flyway enforces this natively)
- [ ] Migrations agent dry-run result documented and approved

---

## P1-T05 — Spring Boot Project Baseline and Security Config
**Tags:** [AUTH]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Coder agent
**Depends on:** P1-T01, P1-T03

### Specification
Set up the Spring Boot 3.x API project with all required dependencies and the Spring Security baseline configuration.

**Maven dependencies to add:**
```xml
spring-boot-starter-web
spring-boot-starter-data-jpa
spring-boot-starter-security
spring-boot-starter-validation
spring-boot-starter-data-redis
spring-boot-starter-actuator
flyway-core
postgresql driver
jjwt-api / jjwt-impl / jjwt-jackson (0.12.x)
spring-kafka
lombok
```

**Spring Security configuration:**
- Disable CSRF (stateless REST API)
- Configure `JwtAuthFilter` as `OncePerRequestFilter`
- Whitelist: `POST /api/v1/auth/**`, `GET /health/**`
- All other endpoints require valid JWT
- Configure `TenantFilterAspect` AOP bean
- Configure `TenantGuard` SpEL bean

**application.yml sections:**
- Database connection (with HikariCP pool settings)
- Redis connection
- JWT secret key (from env variable `JWT_SECRET`)
- Actuator endpoints: health, info only

**Acceptance criteria:**
- [ ] Spring Boot application starts and `/health` returns `{"status":"UP"}`
- [ ] Request to any protected endpoint without JWT returns `401`
- [ ] JWT filter correctly extracts `tenantId`, `userId`, `roleClaims` from valid token
- [ ] `TenantContext` correctly populated on each authenticated request
- [ ] Application logs show structured JSON (not plain text)

---

## P1-T06 — OTP Generation and Redis TTL Service
**Tags:** [AUTH]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Coder agent
**Depends on:** P1-T04, P1-T05

### Specification
Implement `OtpService` handling OTP generation, Redis storage, verification, and invalidation.

**OtpService methods:**
- `generateAndStore(identifier, tenantId, channel) → OtpRecord` — generates 6-char alphanumeric OTP, bcrypt-hashes it, stores hash in Redis with 300s TTL, inserts `otp_records` row
- `verify(identifier, submittedOtp, tenantId) → VerificationResult` — fetches hash from Redis, bcrypt.verify, invalidates on any result (success or failure)
- `checkRateLimit(identifier)` — Redis INCR with 3600s TTL; throws `OtpRateLimitException` if count > 5
- `invalidate(identifier)` — explicit invalidation (for magic link flow)

**OTP generation rules:**
- 6 characters, uppercase alphanumeric, excluding ambiguous chars (0, O, I, 1)
- Generated using `SecureRandom` (cryptographically secure)

**Redis key patterns:**
- OTP hash: `otp:{identifier}` (TTL: 300s)
- Rate limit counter: `otp-rate:{identifier}` (TTL: 3600s)

**Acceptance criteria:**
- [ ] OTP stored in Redis with exactly 300s TTL
- [ ] Submitting correct OTP returns success and deletes Redis key
- [ ] Submitting incorrect OTP returns failure and deletes Redis key (single-use)
- [ ] Submitting OTP after TTL returns `OTP_EXPIRED`
- [ ] 6th OTP request within 1 hour throws `OtpRateLimitException`
- [ ] Unit tests cover all 5 scenarios above

---

## P1-T07 — JWT Issuance and Verification Filter
**Tags:** [AUTH]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Coder agent
**Depends on:** P1-T05

### Specification
Implement `JwtService` for token generation and `JwtAuthFilter` for request-level verification.

**JwtService methods:**
- `generateToken(userId, tenantId, roleClaims) → String` — builds JWT with all required claims; signs with HS256
- `validateToken(token) → JwtClaims` — verifies signature, exp, aud; returns parsed claims
- `extractTenantId(token) → String`
- `extractUserId(token) → String`
- `extractRoleClaims(token) → List<String>`

**JWT claims (required):** `sub`, `iss`, `aud`, `iat`, `exp`, `jti`, `tenantId`, `userId`, `roleClaims`

**JwtAuthFilter:**
- Extends `OncePerRequestFilter`
- Extracts `Authorization: Bearer {token}` header
- Calls `JwtService.validateToken()`
- Sets `SecurityContextHolder` with `TenantAwarePrincipal`

**Acceptance criteria:**
- [ ] Token generated with all required claims
- [ ] Valid token passes filter; invalid token returns `401`
- [ ] Expired token returns `401`
- [ ] Token with wrong audience returns `401`
- [ ] Tampered token (modified payload) returns `401`
- [ ] Unit tests cover all token scenarios

---

## P1-T08 — SES and Twilio Dispatch Adapters
**Tags:** [AUTH]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Coder agent
**Depends on:** P1-T05

### Specification
Implement the notification dispatch adapters used by OTP email/SMS delivery.

**EmailDispatchAdapter:**
- Uses AWS SDK v2 `SesClient`
- Method: `sendOtp(recipientEmail, otp, tenantName)` — sends formatted OTP email
- Method: `sendMagicLink(recipientEmail, magicLink, tenantName)` — sends magic link email
- Templates: simple text + HTML templates (no external template engine in v1)
- Config: `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `SES_FROM_ADDRESS` from env

**SmsDispatchAdapter:**
- Uses Twilio Java SDK
- Method: `sendOtp(recipientPhone, otp, tenantName)` — sends SMS via Twilio
- Config: `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM_NUMBER` from env

**DispatchStrategy (Strategy pattern):**
- `DispatchService.dispatch(identifier, identifierType, otp)` selects `EmailDispatchAdapter` or `SmsDispatchAdapter` based on `identifierType`

**Acceptance criteria:**
- [ ] Email dispatch sends via SES (verified in local dev with SES Sandbox or LocalStack)
- [ ] SMS dispatch sends via Twilio (verified with Twilio test credentials)
- [ ] Strategy correctly routes based on identifier type
- [ ] Dispatch failures are caught and logged; do NOT throw 500 to client (return graceful error)
- [ ] Credentials never hardcoded; all from environment variables

---

## P1-T09 — Auth Controller and Integration Tests
**Tags:** [AUTH] [TEST]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Coder agent + Test-gen agent
**Depends on:** P1-T06, P1-T07, P1-T08

### Specification
Implement `AuthController` exposing the two auth endpoints and write comprehensive integration tests.

**AuthController endpoints:**
- `POST /api/v1/auth/request-otp` — calls `OtpService.generateAndStore()` + `DispatchService.dispatch()`
- `POST /api/v1/auth/verify-otp` — calls `OtpService.verify()`, on success calls `JwtService.generateToken()`

**Integration tests (Testcontainers with PostgreSQL + Redis):**
- Happy path email OTP: request → verify → receive JWT
- Happy path phone OTP: request → verify → receive JWT
- Expired OTP returns `401 OTP_EXPIRED`
- Already-used OTP returns `401 OTP_ALREADY_USED`
- Wrong OTP returns `401 OTP_INVALID`
- Rate limit (6th request) returns `429`
- Invalid identifier format returns `400`

**Acceptance criteria:**
- [ ] All 7 integration test scenarios pass
- [ ] JWT returned on success contains `tenantId`, `userId`, `roleClaims`
- [ ] Testcontainers PostgreSQL and Redis start cleanly in CI
- [ ] Test coverage ≥ 90% for `AuthController`, `OtpService`, `JwtService`

---

## P1-T10 — Next.js Auth Flow
**Tags:** [UI] [AUTH]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Coder agent
**Depends on:** P1-T09

### Specification
Implement the auth flow in Next.js 15 App Router.

**Pages:**
- `app/(auth)/login/page.tsx` — identifier input (email or phone); form submits to `POST /auth/request-otp`
- `app/(auth)/verify/page.tsx` — 6-digit OTP input (auto-advance between cells); submits to `POST /auth/verify-otp`
- On success: store JWT in `HttpOnly` cookie via server action; redirect to tenant booking home

**Components:**
- `IdentifierInput` — single input with auto-detection (email regex vs. E.164 phone)
- `OtpInput` — 6-cell OTP entry with auto-focus and paste support
- `AuthStatus` — shows maskedIdentifier and countdown timer (5-minute OTP expiry)

**Middleware:**
- `middleware.ts` — checks JWT cookie on every request; redirects to `/login` if missing or expired

**Acceptance criteria:**
- [ ] User can complete full auth flow from login page to protected route
- [ ] OTP countdown timer visible and counts down from 5:00
- [ ] "Resend OTP" available after 60 seconds
- [ ] Invalid OTP shows inline error without page reload
- [ ] JWT stored in `HttpOnly` cookie (not localStorage)
- [ ] Middleware redirects unauthenticated requests to `/login`
