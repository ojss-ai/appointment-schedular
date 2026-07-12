# AGENTS.md — Agent Roster & Responsibilities

> Claude Code sub-agent configuration for the Multi-Tenant Scheduling Framework.
> The orchestrator agent reads this file to route tasks and coordinate workers.
> Sub-agent definitions live in `.claude/agents/`; this file is the human-readable roster.

---

## Swarm Topology

```
┌──────────────────────────────────────────────────────────┐
│                    SPEC PIPELINE                         │
│   /specify → /clarify → /atomize                         │
│                    ▼                                     │
│             Spec Agent                                   │
│   (specs/<slug>/ → tasks/phase-N/atom-*.md)             │
└──────────────────────────┬───────────────────────────────┘
                           │ atom files feed
                    ┌──────▼──────────┐
                    │  Orchestrator   │
                    │     Agent       │
                    └────────┬────────┘
           ┌─────────────────┼──────────────────┐
           │                 │                  │
    ┌──────▼──────┐   ┌──────▼──────┐   ┌──────▼──────┐
    │   Coder     │   │  Test-Gen   │   │  Security   │
    │   Agent     │   │   Agent     │   │   Agent     │
    └──────┬──────┘   └─────────────┘   └─────────────┘
           │
    ┌──────┴──────────────────────┐
    │                             │
┌───▼──────┐   ┌──────────┐   ┌──▼────────┐
│  ADR /   │   │Migrations│   │Observabil-│
│  Docs    │   │  Agent   │   │ity Agent  │
│  Agent   │   └──────────┘   └───────────┘
└──────────┘
```

---

## Agent Definitions

### 1. Orchestrator Agent
**Definition file:** `.claude/agents/orchestrator.md`
**Role:** Top-level task router and swarm coordinator. Reads `CLAUDE.md` and the active task file at session start, assigns subtasks to specialist agents via the Claude Agent SDK `Task` tool, monitors completion, and triggers background checks on commit hooks.

**Triggers:**
- Session start → read `CLAUDE.md` + current phase task list
- New task created → analyse tags and delegate to appropriate agent
- Git commit hook (via `.claude/settings.json`) → trigger security scan + test-gap check
- Phase completion → run full integration test suite

**Responsibilities:**
- Maintain task state via task atom files in `tasks/phase-N/`
- Escalate to human if confidence < 70% on any architectural decision
- Never proceed with a `[MIGRATION]` task without explicit human confirmation

---

### 2. Coder Agent
**Definition file:** `.claude/agents/coder.md`
**Role:** Primary code generation and implementation agent. Handles Spring Boot services, JPA entities, Next.js components, and API routes.

**Scope (what it generates):**
- Spring Boot: `@Entity` classes, `@Repository` interfaces, `@Service` classes, `@RestController` classes, Flyway migration SQL
- Next.js: App Router page components, server actions, API route handlers, React Hook Form schemas, Zod validators
- Configuration: `application.yml`, `docker-compose.yml` service definitions

**Constraints (hard rules):**
- Must never generate a JPA query without `tenant_id` in the WHERE clause
- Must never create a `slots` table or any entity that stores pre-computed availability
- Must use `@Transactional` on all methods that mutate booking state
- Must apply `@PreAuthorize` with tenant guard on all controller endpoints
- All DTOs must be Java records (Java 21)
- Must tag generated code blocks with the source atom ID as a comment (e.g., `// [TASK: ATOM-BOOKING-003]`)

**SPARC phases applied:**
1. Specification — review task spec and acceptance criteria
2. Pseudocode — outline logic before writing implementation
3. Architecture — identify affected layers and cross-cutting concerns
4. Refinement — apply coding standards from `CLAUDE.md`
5. Completion — verify against acceptance criteria; flag for testgen

---

### 3. Test-Gen Agent
**Definition file:** `.claude/agents/testgen.md`
**Role:** Automatically detects untested code paths and generates comprehensive test suites.

**Test types generated:**
- **Unit tests** (JUnit 5): pure business logic — SlotCalculator, OTP lifecycle, JWT builder
- **Integration tests** (Testcontainers + PostgreSQL + Kafka): BookingService concurrency, outbox relay
- **API tests** (MockMvc / REST Assured): all controller endpoints with tenant isolation assertions
- **E2E tests** (Playwright): booking flow, admin portal, OTP auth
- **Load tests** (k6 scripts): slot availability endpoint, concurrent booking checkout

**Auto-triggers:**
- After coder agent completes any task → scan for missing test coverage
- Before Phase 5 sign-off → generate full k6 load test suite
- On `[CONCURRENCY]` tasks → always generate race-condition simulation tests

**Coverage gates:**
- Line coverage ≥ 80% on all service classes
- Concurrency tests must simulate ≥ 10 simultaneous booking requests on same slot
- Every Kafka consumer must have an idempotency test (duplicate message delivery scenario)

---

### 4. Security Agent
**Definition file:** `.claude/agents/security.md`
**Role:** Security analysis, vulnerability scanning, and tenant isolation verification.

**Responsibilities:**
- CVE scan on all Java (Maven) and Node (npm) dependencies on every commit
- Verify `tenant_id` isolation in every generated JPA query (no cross-tenant data leakage)
- Audit JWT claim construction — ensure `tenant_id`, `user_id`, `role_claims` are always present
- Validate OTP lifecycle (5-min TTL, single-use invalidation)
- Check for SQL injection vectors in any dynamic query construction
- HIPAA audit readiness check on audit-service output format
- PII detection in Kafka event payloads (no raw PII in `tenant.bookings.lifecycle` topic)

**Auto-triggers:**
- Git pre-commit hook (`.claude/settings.json`) → dependency CVE scan
- New `@RestController` or `@Repository` created → tenant isolation audit
- Any change to `SecurityConfig` → full auth flow re-audit
- Phase 5 start → comprehensive penetration test simulation

**Output:** Security findings written to `docs/SECURITY-FINDINGS.md` with severity (CRITICAL / HIGH / MEDIUM / LOW) and remediation steps.

---

### 5. ADR + Docs Agent
**Definition file:** `.claude/agents/adr-docs.md`
**Role:** Maintains living documentation. Auto-generates Architecture Decision Records when the coder agent makes a significant design choice.

**ADR triggers (auto-generate record when):**
- A new design pattern is introduced (e.g., adding a new locking strategy)
- An existing ADR decision is revisited or reversed
- A new Kafka topic or consumer is defined
- A schema migration changes a core entity structure
- A new external dependency is added (library, service, third-party API)

**Docs responsibilities:**
- Keep `docs/API-SPEC.md` in sync with actual controller endpoints after each coder agent task
- Keep `docs/KAFKA-SPEC.md` updated when topics or schemas change
- Generate inline Javadoc / JSDoc stubs for all public service methods
- Maintain `CHANGELOG.md` from git history

**Naming convention for ADRs:**
`ADR-{NNN}-{short-kebab-description}.md`
Status values: `Proposed` → `Accepted` → `Deprecated` → `Superseded by ADR-{NNN}`

---

### 6. Migrations Agent
**Definition file:** `.claude/agents/migrations.md`
**Role:** Safe database schema evolution across all tenants.

**Responsibilities:**
- Generate Flyway migration SQL files for every schema change
- Validate that JSONB structural changes are backward-compatible
- Run dry-run migration against a shadow schema before confirming
- Ensure all new columns are nullable or have defaults (zero-downtime deployments)
- Generate rollback SQL counterpart for every forward migration

**Hard rules:**
- Never generate `DROP TABLE` or `DROP COLUMN` directly — always use deprecation + deferred removal
- Never write raw SQL that lacks `WHERE tenant_id = :tenantId`
- Always add indexes in a separate migration step from column addition (PostgreSQL locks)
- Confirm with human before executing any migration touching > 1M estimated rows

---

### 7. Observability Agent
**Definition file:** `.claude/agents/observability.md`
**Role:** Monitoring, alerting, performance visibility, and session cost logging.

**Instrumentation scope:**
- Kafka consumer lag per consumer group
- Slot calculation latency (p50/p95/p99) — alert if p99 > 250ms
- `PENDING_HOLD` count per tenant — alert if > 100 expired holds accumulate
- OTP dispatch success/failure rate per channel (email vs SMS)
- Dead-letter queue depth on all Kafka topics
- Claude Agent SDK token usage and cost per session (logged to `docs/COST-LOG.md`)

**Output:** Prometheus metrics endpoint + Grafana dashboard spec in `infra/observability/`

---

### 8. Spec Agent
**Definition file:** `.claude/agents/spec-agent.md`
**Role:** Spec pipeline coordinator. Drives the specify → clarify → atomize workflow for all new feature work. Converts raw feature ideas into production-ready atom task files that the orchestrator and coder agents can execute.

**Pipeline stages:**

| Stage | Command | Input | Output |
|---|---|---|---|
| Specify | `/specify <description>` | Free-text feature idea | `specs/<slug>/spec.md` |
| Clarify | `/clarify` | `spec.md` | `specs/<slug>/clarifications.md` |
| Atomize | `/atomize` | `spec.md` + `clarifications.md` | `tasks/phase-N/atom-NN-*.md` files |

**Domain abstraction enforcement (runs on every spec):**

| User language | Translated to |
|---|---|
| doctor / patient | Resource / Booking |
| mechanic / vehicle | Resource / Booking |
| room / class | Resource / Service |
| appointment / reservation | Booking |
| clinic / shop / studio | Location |
| customer / client | Booking initiator |

**Quality gates (all 6 must pass before atomize is allowed):**
1. Feature slug is kebab-case ≤ 20 chars
2. Zero industry-specific terms in spec (domain abstraction)
3. All ACs are testable and measurable (no vague language)
4. Tenant isolation impact is documented
5. Concurrency/slot impact is assessed
6. NFR impact vs. 500 RPS / p99 < 300ms gates is declared

**Escalation rules:**
- New Kafka topic or Avro schema change → must trigger `/adr-check` before atomize
- Slot algorithm modification → must trigger `/adr-check` (ADR-001 scope)
- Change to tenant isolation model → escalate to human before proceeding

**Session start protocol:** Read `CLAUDE.md`, `AGENTS.md`, and `specs/` directory. Detect whether an active spec is in Specify, Clarify, or Atomize stage and resume from the correct point.

---

## Background Workers (triggered by `.claude/settings.json` hooks)

| Worker | Trigger | Action |
|---|---|---|
| `security-scan` | Every git commit | CVE scan on all dependencies |
| `test-gap-detector` | After coder agent task | Identify uncovered code paths |
| `adr-checker` | Architectural code change | Prompt for ADR if none exists |
| `migration-validator` | Schema file change | Dry-run migration check |
| `kafka-lag-monitor` | Every 5 minutes (production) | Alert if consumer lag > 1000 messages |
| `pending-hold-gc` | Every 1 minute | Revert expired PENDING_HOLD bookings |
| `cost-tracker` | End of each Claude session | Log token usage per agent and task |

---

## Slash Commands (`.claude/commands/`)

### Spec Pipeline Commands

| Command | File | Purpose |
|---|---|---|
| `/specify <description>` | `specify.md` | Transform a feature idea into a structured spec → `specs/<slug>/spec.md` |
| `/clarify` | `clarify.md` | Audit active spec for gaps; ask one question per run → `specs/<slug>/clarifications.md` |
| `/atomize` | `atomize.md` | Decompose clarified spec into atom task files → `tasks/phase-N/atom-NN-*.md` |

### DevOps / Quality Commands

| Command | File | Purpose |
|---|---|---|
| `/security-scan` | `security-scan.md` | Run CVE + tenant isolation audit now |
| `/test-gap` | `test-gap.md` | Detect untested code paths in changed files |
| `/adr-check` | `adr-check.md` | Check if recent design changes need an ADR |
| `/migration-validate` | `migration-validate.md` | Dry-run all pending Flyway migrations |
| `/cost-report` | `cost-report.md` | Print token usage summary for this session |

---

## Spec Pipeline Workflow (`.claude/workflows/`)

The `new-feature.md` workflow in `.claude/workflows/` is the canonical guide for creating new features end-to-end:

```
/specify "Add SMS reminders for upcoming bookings"
    ↓  spec.md written to specs/sms-reminders/
/clarify
    ↓  clarifications.md written (run until score = 10/10)
/atomize
    ↓  atom files written to tasks/phase-N/
/adr-check          ← required if new Kafka topic introduced
    ↓
Coder + TestGen agents implement atoms
```

**Key directories:**
- `specs/<feature-slug>/spec.md` — feature specification
- `specs/<feature-slug>/clarifications.md` — resolved ambiguities
- `specs/_template/` — atom and spec templates
- `.claude/workflows/new-feature.md` — step-by-step workflow guide

---

## Technology Skills (`.claude/skills/`)

Skill files are read-only reference documents loaded by the Coder and TestGen agents before generating code. They encode the exact patterns, annotations, and anti-patterns for this project derived from the ADRs and coding standards in `CLAUDE.md`.

| Skill file | Technology | Key topics |
|---|---|---|
| `java-21.md` | Java 21 | Records (DTOs), sealed classes, text blocks, pattern matching, switch expressions, structured logging |
| `spring-boot.md` | Spring Boot 3.x | Entity/Repository/Service/Controller patterns, JWT security, tenant guard, transactional outbox, config properties |
| `nextjs-react.md` | Next.js 15 + React | App Router, server vs. client components, api-client.ts, RHF + Zod forms, Tanstack Query, server actions |
| `kafka-avro.md` | Kafka + Avro | Outbox → CDC topology, Avro schema conventions, idempotent consumer pattern, topic naming, processed_events table |
| `postgresql-jpa.md` | PostgreSQL 15 + JPA | Flyway migration rules, required indexes, RLS for audit table, JSONB extension column, pessimistic locking, Testcontainers |
| `testing.md` | JUnit 5 / Playwright / k6 | Unit/integration/API/E2E test patterns, Given/Assert format, Kafka idempotency tests, k6 load test template, coverage gates |

**Usage:** Agents reference skill files via the path `.claude/skills/<name>.md`. All six skills are registered in `.claude/settings.json` under the `skills` key.
