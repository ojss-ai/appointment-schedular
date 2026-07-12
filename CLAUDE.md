# CLAUDE.md — Multi-Tenant Scheduling Framework
## Project Memory & Agent Coordination Instructions

> This file is the authoritative context document for Claude Code and all sub-agents working on this project.
> Every agent must read this file at the start of each session before performing any task.

---

## Project Identity

| Field | Value |
|---|---|
| Project name | Multi-Tenant Omni-Industry Scheduling Framework |
| Repo root | `C:\projects\appointment` |
| Status | Pre-development — spec phase complete |
| Agentic harness | Claude Code (Agent SDK — sub-agents in `.claude/agents/`) |
| Primary languages | TypeScript (Next.js 15), Java 21 (Spring Boot 3.x) |
| Database | PostgreSQL 15+ |
| Event mesh | Apache Kafka + Confluent Schema Registry (Avro) |
| Cache / lock | Redis |

---

## Repository Layout (target)

```
appointment/
├── CLAUDE.md                  ← this file
├── AGENTS.md                  ← agent roster
├── initial-requirement.md     ← seed SRS
├── docs/
│   ├── PRD.md
│   ├── ARCHITECTURE.md
│   ├── DATABASE-SCHEMA.md
│   ├── API-SPEC.md
│   ├── KAFKA-SPEC.md
│   ├── SECURITY-SPEC.md
│   └── ADR/
│       ├── ADR-001-slot-generation-strategy.md
│       ├── ADR-002-concurrency-locking-model.md
│       ├── ADR-003-transactional-outbox-pattern.md
│       ├── ADR-004-multi-tenancy-isolation-model.md
│       └── ADR-005-domain-abstraction-model.md
├── tasks/
│   ├── MASTER-TASK-LIST.md
│   ├── phase-1/               ← Foundation & scaffold
│   ├── phase-2/               ← Core booking engine
│   ├── phase-3/               ← Kafka event mesh
│   ├── phase-4/               ← Agentic intelligence layer
│   └── phase-5/               ← Scale & production hardening
├── apps/
│   ├── web/                   ← Next.js 15 (App Router)
│   └── api/                   ← Spring Boot 3.x
├── services/
│   ├── notification-service/  ← Kafka consumer: notifications
│   └── audit-service/         ← Kafka consumer: HIPAA audit ledger
└── infra/
    ├── docker-compose.yml
    ├── kafka/
    └── postgres/
```

---

## Core Architecture Principles (NEVER violate these)

1. **Domain abstraction is sacred.** Tables and APIs must never contain industry-specific terms. Use `Resource`, `Service`, `Booking`, `Location`, `Tenant` — never `Doctor`, `Patient`, `Vehicle`, `Mechanic`.

2. **Tenant isolation is non-negotiable.** Every database table carries `tenant_id UUID NOT NULL`. Every JPA repository query must be filtered by `tenant_id`. Spring AOP enforces this at the service layer.

3. **Slots are never stored.** The `SlotCalculatorService` computes availability on demand. There is no `slots` table. Availability = operating matrix − confirmed bookings − buffer windows.

4. **Outbox before Kafka.** Business state and the matching event payload are written in a single ACID transaction to the `outbox` table. A CDC reader (Debezium/Kafka Connect) relays events to Kafka. Never write directly to Kafka from a business transaction.

5. **Pessimistic lock first.** Use `SELECT ... FOR UPDATE` as the primary concurrency guard. Redis distributed lock is the horizontal-scale fallback only.

6. **JSONB for extension data only.** The `extension` JSONB column on `Booking` and `Resource` entities is for tenant-injected domain data. Core business logic must never read from it.

---

## Claude Code Agent Configuration

### Sub-agent setup
Sub-agent definitions live in `.claude/agents/`. Claude Code loads them automatically. Each file is a Markdown prompt that scopes the agent's role, constraints, and SPARC phases. See `AGENTS.md` for the full roster.

```
.claude/
├── agents/
│   ├── orchestrator.md      ← top-level router (reads CLAUDE.md + task file)
│   ├── coder.md             ← Spring Boot + Next.js implementation
│   ├── testgen.md           ← unit / integration / E2E / load tests
│   ├── security.md          ← CVE scan, tenant isolation audit, HIPAA check
│   ├── adr-docs.md          ← ADR generation + living docs sync
│   ├── migrations.md        ← Flyway SQL, dry-run, rollback
│   └── observability.md     ← Prometheus metrics, Grafana spec, cost log
├── commands/
│   ├── security-scan.md     ← /security-scan
│   ├── test-gap.md          ← /test-gap
│   ├── adr-check.md         ← /adr-check
│   ├── migration-validate.md← /migration-validate
│   └── cost-report.md       ← /cost-report
└── settings.json            ← hooks: PostToolUse commit checks
```

### Orchestrator routing rules
- Tasks tagged `[AUTH]` → delegate to **coder** agent with Spring Security context
- Tasks tagged `[SLOT]` → delegate to **coder** agent with domain-abstraction guard active
- Tasks tagged `[CONCURRENCY]` → delegate to **coder** + **testgen** agents (always paired)
- Tasks tagged `[KAFKA]` → delegate to **coder** + **migrations** agents
- Tasks tagged `[SECURITY]` → delegate to **security** agent first, then **coder**
- Tasks tagged `[ADR]` → delegate to **adr-docs** agent for automatic record generation
- Tasks tagged `[TEST]` → delegate to **testgen** agent exclusively
- Tasks tagged `[MIGRATION]` → delegate to **migrations** agent with dry-run flag

### Memory namespaces (CLAUDE.md section headers used as context anchors)
- `scheduling:domain-model` — entity definitions and field contracts
- `scheduling:api-contracts` — REST endpoint signatures
- `scheduling:kafka-topology` — topic names, Avro schemas, consumer groups
- `scheduling:security-rules` — tenant isolation patterns, JWT claim structure
- `scheduling:task-progress` — completed atom task IDs and outcomes

---

## Key Technical Decisions (summary — full ADRs in docs/ADR/)

| ID | Decision | Rationale |
|---|---|---|
| ADR-001 | Slots computed on-demand, never stored | Eliminates stale-slot race conditions; single source of truth is booking records |
| ADR-002 | Pessimistic lock (SELECT FOR UPDATE) primary, Redis fallback | Simpler correctness guarantee; Redis only added when horizontal scale demands it |
| ADR-003 | Transactional outbox pattern for Kafka | Prevents dual-write failure between DB commit and Kafka publish |
| ADR-004 | Row-level multi-tenancy via tenant_id discriminator | Lower operational cost than schema-per-tenant; enforced via Spring AOP |
| ADR-005 | Generic domain model (Resource/Service) with JSONB extension | Allows omni-industry use without schema changes |

---

## Non-Negotiable NFRs (performance gates)

| NFR | Gate | Enforcement |
|---|---|---|
| NFR-1.1 | 500+ concurrent reservations/min | k6 load test must pass before Phase 5 sign-off |
| NFR-1.2 | Slot generation endpoint < 300ms p99 | Verified by load test; Redis cache added if breached |
| NFR-1.3 | Compound B-tree index on (tenant_id, location_id, start_time) | Migration must exist before any slot-query code is merged |
| NFR-2.1 | Idempotent Kafka consumers | Dedup table checked before every consumer action |
| NFR-2.2 | Avro schema registry | All Kafka payloads validated against registry schema before publish |

---

## Coding Standards

### Java / Spring Boot
- Java 21 with records for DTOs
- Spring Boot 3.x, Spring Data JPA, Spring Security 6
- All service classes annotated `@Transactional` where state is mutated
- Repository interfaces extend `JpaRepository`; never write raw JPQL without `tenant_id` in WHERE clause
- Use `@PreAuthorize("@tenantGuard.check(#tenantId)")` on all controller methods
- Flyway for all database migrations; migration files named `V{n}__{description}.sql`

### TypeScript / Next.js
- Next.js 15 App Router; server components by default
- React Hook Form + Zod for all form validation
- Tanstack Query for server state (slot availability polling)
- `react-jsonschema-form` for the dynamic tenant form builder
- All API calls go through `apps/web/lib/api-client.ts`; no direct fetch in components

### General
- Every commit triggers Claude Code hooks (`.claude/settings.json`): security scan, test-gap detection, ADR check
- No `console.log` in production code; use structured logging (SLF4J / pino)
- All environment secrets in `.env.local` (never committed); Docker secrets for infra

---

## Glossary

| Term | Definition |
|---|---|
| Resource | A bookable asset: human (staff) or physical (room, equipment) |
| Service | A type of appointment: defines duration, buffer rules, allowed resource types |
| Booking | A confirmed or pending reservation of a Resource for a Service at a time slot |
| Location | A physical branch with its own timezone, hours, and resource pool |
| Tenant | An organization that owns a set of Locations, Resources, Services, and Bookings |
| Operating Matrix | Computed time-block grid: base shift − breaks − holidays = available windows |
| PENDING_HOLD | Transient booking state: slot reserved for 10 min during checkout; GC'd if abandoned |
| Outbox | ACID-safe intermediate table; CDC relays rows to Kafka after DB commit |
| Extension | The `JSONB` column on Booking/Resource; stores tenant-specific domain metadata |
