# Master Task List — Multi-Tenant Scheduling Framework
**Project:** Multi-Tenant Omni-Industry Scheduling Framework
**Agentic harness:** Claude Code (Agent SDK — sub-agents in `.claude/agents/`)
**Phases:** 5 | **Total tasks:** ~57 atoms across 5 phases

---

## Phase 1 — Foundation & Scaffold (Weeks 1–3)

| Task ID | Phase | Name | Priority | Estimate | Tags | Status |
|---|---|---|---|---|---|---|
| P1-T01 | 1 | Monorepo scaffold — pnpm workspace, Turborepo, Docker Compose | P0 | 1d | [INFRA] | ✅ Complete |
| P1-T02 | 1 | Claude Code agent configuration (`.claude/` setup) | P0 | 0.5d | [ORCHESTRATION] | ✅ Complete |
| P1-T03 | 1 | Local dev infrastructure — PostgreSQL, Kafka, Redis, Schema Registry | P0 | 1d | [INFRA] | ✅ Complete |
| P1-T04 | 1 | Core Flyway migrations V001–V009 (tenants → operating_matrix) | P0 | 1.5d | [MIGRATION] | ✅ Complete |
| P1-T05 | 1 | Spring Security — JWT filter, tenant context, AOP guard | P0 | 1.5d | [AUTH] [SECURITY] | ✅ Complete |
| P1-T06 | 1 | OTP generation and verification service | P0 | 1d | [AUTH] | ✅ Complete |
| P1-T07 | 1 | JWT builder — all 9 required claims | P0 | 1d | [AUTH] | ✅ Complete |
| P1-T08 | 1 | Notification dispatch adapter — SES + Twilio | P1 | 1d | [INFRA] | ✅ Complete |
| P1-T09 | 1 | Auth flow — controller + integration tests | P0 | 1.5d | [AUTH] [TEST] | ✅ Complete |
| P1-T10 | 1 | Next.js auth UI — OTP input, JWT cookie, auth middleware | P0 | 1.5d | [AUTH] | ✅ Complete |

---

## Phase 2 — Core Booking Engine (Weeks 4–7)

| Task ID | Phase | Name | Priority | Estimate | Tags |
|---|---|---|---|---|---|
| P2-T01 | 2 | Tenant management CRUD API | P1 | 1d | |
| P2-T02 | 2 | Location management CRUD API | P1 | 1d | |
| P2-T03 | 2 | Resource management CRUD API | P1 | 1d | |
| P2-T04 | 2 | Service type management CRUD API | P1 | 1d | |
| P2-T05 | 2 | SlotCalculatorService — on-demand availability engine | P0 | 2d | [SLOT] |
| P2-T06 | 2 | Operating matrix — shift schedule and break management | P0 | 1.5d | [SLOT] |
| P2-T07 | 2 | Holiday and closure management | P1 | 1d | [SLOT] |
| P2-T08 | 2 | Redis caching for schedules and holidays | P1 | 1d | [SLOT] |
| P2-T09 | 2 | GET /slots — slot availability REST endpoint | P0 | 1.5d | [SLOT] |
| P2-T10 | 2 | POST /bookings — create hold (PENDING_HOLD) | P0 | 1.5d | [CONCURRENCY] |
| P2-T11 | 2 | POST /bookings/{id}/confirm — confirm booking | P0 | 1.5d | [CONCURRENCY] |
| P2-T12 | 2 | POST /bookings/{id}/cancel — cancel booking | P0 | 1d | |
| P2-T13 | 2 | PENDING_HOLD GC scheduler (10-min expiry) | P0 | 1d | [CONCURRENCY] |
| P2-T14 | 2 | Booking concurrency integration tests (10-thread race) | P0 | 1.5d | [CONCURRENCY] [TEST] |
| P2-T15 | 2 | Admin portal UI — resource + schedule management | P1 | 2d | |
| P2-T16 | 2 | Customer booking UI — slot picker + checkout | P0 | 2d | |

---

## Phase 3 — Kafka Event Mesh (Weeks 8–10)

| Task ID | Phase | Name | Priority | Estimate | Tags | Status |
|---|---|---|---|---|---|---|
| P3-T01 | 3 | Flyway migrations V010–V013 (bookings, outbox, processed_events, audit_log) | P1 | 1d | [MIGRATION] | ✅ Complete |
| P3-T02 | 3 | Outbox entity and OutboxService (MANDATORY propagation) | P1 | 1d | [KAFKA] | ✅ Complete |
| P3-T03 | 3 | Debezium connector configuration | P1 | 1d | [KAFKA] [INFRA] | ✅ Complete |
| P3-T04 | 3 | Kafka topic creation and producer config | P1 | 0.5d | [KAFKA] | ✅ Complete |
| P3-T05 | 3 | Avro schemas and Schema Registry registration | P1 | 1d | [KAFKA] | ✅ Complete |
| P3-T06 | 3 | Integrate outbox write into BookingService | P1 | 1d | [KAFKA] | ✅ Complete |
| P3-T07 | 3 | Notification service scaffold | P1 | 0.5d | [KAFKA] | ✅ Complete |
| P3-T08 | 3 | NotificationConsumer — idempotency and dispatch | P1 | 1.5d | [KAFKA] | ✅ Complete |
| P3-T09 | 3 | Audit service scaffold | P1 | 0.5d | [KAFKA] | ✅ Complete |
| P3-T10 | 3 | AuditConsumer — append-only audit log write | P1 | 1d | [KAFKA] | ✅ Complete |
| P3-T11 | 3 | Outbox chaos test (Kafka down, DB rollback, Debezium restart) | P1 | 1d | [TEST] [KAFKA] | ✅ Complete |
| P3-T12 | 3 | Consumer idempotency tests | P1 | 1d | [TEST] [KAFKA] | ✅ Complete |

---

## Phase 4 — Agentic Intelligence Layer (Weeks 11–13)

| Task ID | Phase | Name | Priority | Estimate | Tags | Status |
|---|---|---|---|---|---|---|
| P4-T01 | 4 | Booking analytics memory — nightly ingestion to `docs/memory/` | P2 | 1d | [ANALYTICS] | ✅ Complete |
| P4-T02 | 4 | Orchestration workflow templates (`.claude/workflows/`) | P2 | 1d | [ORCHESTRATION] | ✅ Complete |
| P4-T03 | 4 | AI slot optimization suggestions (Claude API + Redis cache) | P2 | 2d | [SLOT] [ANALYTICS] | ✅ Complete |
| P4-T04 | 4 | Analytics scheduler — peak booking detection | P2 | 1.5d | [ANALYTICS] | ✅ Complete |
| P4-T05 | 4 | Auto ADR generation hook for architectural changes | P2 | 0.5d | [ADR] | ✅ Complete |

---

## Phase 5 — Scale & Production Hardening (Weeks 14–16)

| Task ID | Phase | Name | Priority | Estimate | Tags | Status |
|---|---|---|---|---|---|---|
| P5-T01 | 5 | k6 load test — slot availability endpoint (500 RPS, p99<300ms) | P0 | 1d | [TEST] [PERF] | ✅ Complete |
| P5-T02 | 5 | k6 load test — concurrent booking checkout (500 req/min) | P0 | 1d | [TEST] [PERF] | ✅ Complete |
| P5-T03 | 5 | Performance tuning — Redis cache warm-up if p99>250ms | P0* | 1d | [PERF] [SLOT] | ✅ Complete |
| P5-T04 | 5 | Security hardening — full audit sweep (`/security-scan`) | P0 | 1d | [SECURITY] | ✅ Complete |
| P5-T05 | 5 | Tenant isolation penetration tests | P0 | 1d | [SECURITY] [TEST] | ✅ Complete |
| P5-T06 | 5 | Production infrastructure — AWS CDK deployment | P0 | 2d | [INFRA] | ✅ Complete |
| P5-T07 | 5 | CI/CD pipeline — GitHub Actions with all quality gates | P0 | 1d | [INFRA] | ✅ Complete |
| P5-T08 | 5 | Observability dashboard — Prometheus, Grafana, AlertManager | P1 | 1d | [INFRA] | ✅ Complete |

*P5-T03 only triggered if P5-T01 breaches 250ms threshold. Code delivered
(`CacheWarmUpService`, `app.cache.warmup.enabled`), disabled by default; enable
and re-run P5-T01 only if the observed p99 > 250ms.

**Phase 5 deliverable note:** P5-T01/P5-T02 load scripts, the pen-test suite, and
the observability stack are code-complete and syntax-validated; live execution of
the k6 runs and alert firing requires a running environment (staging/production).

---

## Phase Milestones

| Phase | Tasks | Weeks | Milestone |
|---|---|---|---|
| Phase 1 — Foundation | P1-T01 to P1-T10 | Weeks 1–3 | Auth flow working end-to-end; local stack healthy |
| Phase 2 — Booking engine | P2-T01 to P2-T16 | Weeks 4–7 | Slot computation live; bookings confirmed; concurrency tested |
| Phase 3 — Kafka event mesh | P3-T01 to P3-T12 | Weeks 8–10 | Notifications sent; audit trail immutable; chaos tests passing |
| Phase 4 — Agentic layer | P4-T01 to P4-T05 | Weeks 11–13 | Analytics pipeline active; AI suggestions live; ADR automation running |
| Phase 5 — Production | P5-T01 to P5-T08 | Weeks 14–16 | All NFR gates passed; production live; CI/CD green |

---

## Task Tag Reference

| Tag | Meaning |
|---|---|
| `[AUTH]` | Authentication / JWT / OTP flows |
| `[SLOT]` | Slot computation — domain abstraction guard active |
| `[CONCURRENCY]` | Pessimistic lock / race condition scenarios — always paired with testgen |
| `[KAFKA]` | Event mesh — outbox, consumers, Avro schemas |
| `[SECURITY]` | Security audit — run security agent first |
| `[ADR]` | Architectural decision — adr-docs agent generates record |
| `[TEST]` | Test-only tasks — testgen agent exclusively |
| `[MIGRATION]` | DB schema change — migrations agent with dry-run; human approval required |
| `[INFRA]` | Infrastructure / DevOps tasks |
| `[PERF]` | Performance gate tasks |
| `[ANALYTICS]` | Booking pattern analysis and AI optimization |
| `[ORCHESTRATION]` | Workflow and agent coordination |

---

## Atom File Index

Each phase folder contains individual atom task files with complete implementation details:
- `tasks/phase-1/atom-01-monorepo-scaffold.md` through `atom-10-nextjs-auth-ui.md`
- `tasks/phase-2/atom-01-tenant-crud-api.md` through `atom-16-customer-booking-ui.md`
- `tasks/phase-3/atom-01-bookings-flyway-migrations.md` through `atom-12-consumer-idempotency-tests.md`
- `tasks/phase-4/atom-01-booking-analytics-memory-ingestion.md` through `atom-05-auto-adr-generation-hook.md`
- `tasks/phase-5/atom-01-k6-slot-availability-load-test.md` through `atom-08-observability-dashboard.md`
