# Multi-Tenant Omni-Industry Scheduling Framework

A multi-tenant, extensible scheduling platform whose booking core is fully isolated from industry-specific business logic. The same engine powers a dental office, an auto shop, or a consulting firm: everything is a generic `Resource` offering a `Service` at a `Location`, owned by a `Tenant`, with per-tenant domain data injected through PostgreSQL `JSONB` extension columns — never through schema changes or industry terminology in code. Availability ("slots") is computed on demand from the operating matrix minus confirmed bookings and buffers; slots are never stored. All five delivery phases are code-complete (see [Implementation status](#implementation-status)).

## Architecture summary

| Concern | Decision | Reference |
| :--- | :--- | :--- |
| Frontend | Next.js 15 (App Router, server components), TypeScript, Tanstack Query, RHF + Zod, `@rjsf` dynamic form builder | `apps/web` |
| Backend API | Java 21, Spring Boot 3.x, Spring Data JPA, Spring Security 6 (OTP + JWT) | `apps/api` |
| Database | PostgreSQL 15+, Flyway migrations V001–V015, JSONB `extension` columns | `docs/DATABASE-SCHEMA.md` |
| Slot generation | Computed on demand — no `slots` table (ADR-001) | `SlotCalculatorService` |
| Concurrency | Pessimistic `SELECT ... FOR UPDATE` primary; Redis lock is scale fallback (ADR-002) | `docs/ADR/ADR-002` |
| Eventing | Transactional outbox → Debezium CDC → Kafka, Avro + Confluent Schema Registry (ADR-003) | `docs/KAFKA-SPEC.md` |
| Multi-tenancy | Row-level `tenant_id` discriminator on every table, enforced by Spring AOP + `@PreAuthorize` tenant guard (ADR-004) | `docs/SECURITY-SPEC.md` |
| Domain abstraction | Generic `Resource`/`Service`/`Booking`/`Location`/`Tenant` model with JSONB extension (ADR-005) | `docs/ADR/ADR-005` |
| Consumers | `notification-service` (SES/Twilio dispatch) and `audit-service` (append-only HIPAA ledger), both idempotent via `processed_events` dedup | `services/` |
| Intelligence layer | Booking-pattern ingestion, peak/anomaly detection, AI slot-optimization suggestions (Anthropic API) | `apps/api .../analytics` |
| Observability | Micrometer → Prometheus, Grafana dashboard, Alertmanager rules | `infra/observability/` |
| Cloud | AWS CDK (TypeScript): VPC, RDS, ElastiCache, MSK, ECS Fargate, Secrets Manager | `infra/aws/cdk/` |

## Repository layout

```text
CLAUDE.md / AGENTS.md        Agent coordination memory & roster
initial-requirement.md       Seed SRS
docs/                        PRD, ARCHITECTURE, DATABASE-SCHEMA, API-SPEC, KAFKA-SPEC,
                             SECURITY-SPEC, ADR/ (5 records), memory/ (agent memory)
specs/ , tasks/              Spec templates + phase 1–5 atomized task breakdown
apps/web/                    Next.js 15 frontend (auth, booking flow, admin portal,
                             JSON-schema form builder)
apps/api/                    Spring Boot core API (auth, tenancy, slots, bookings,
                             outbox, analytics) + Flyway migrations (db/migration,
                             db/undo manual rollbacks)
services/notification-service/  Kafka consumer → email/SMS dispatch
services/audit-service/         Kafka consumer → append-only audit ledger
infra/                       docker-compose local stack (Postgres, Redis, Kafka KRaft,
                             Schema Registry, Kafka UI, Debezium, Prometheus, Grafana),
                             kafka/ schemas + connector, aws/cdk/, observability/
tests/load/                  k6 load tests (NFR-1.1 / NFR-1.2 gates)
scripts/security-audit.sh    Rerunnable 6-domain static security audit
.claude/                     Claude Code agents, commands, skills, workflows, hooks
.github/workflows/ci.yml     CI/CD pipeline
```

## Prerequisites

| Tool | Version |
| :--- | :--- |
| JDK | 21 (Temurin / OpenJDK) |
| Maven | 3.9+ |
| Node.js | 22 LTS |
| pnpm | 9 (pinned via `packageManager` in root `package.json`) |
| Docker + Compose | Engine 25+ / Compose v2 |
| k6 | latest (load tests only) |

## Quickstart

```bash
# 1. Infrastructure (Postgres, Redis, Kafka, Schema Registry, Debezium, Kafka UI)
docker compose -f infra/docker-compose.yml up -d
#    add -f infra/docker-compose.override.yml to also run Prometheus/Grafana/consumers

# 2. Core API — Flyway migrations (V001–V015) run automatically on startup
cd apps/api
mvn spring-boot:run -Dspring-boot.run.profiles=local     # http://localhost:8080

# 3. Kafka topics, Avro schemas, Debezium connector (idempotent; kafka-init/debezium-init
#    containers do this automatically — run manually only if needed)
./infra/kafka/topics.sh && ./infra/kafka/register-schemas.sh && ./infra/kafka/register-connector.sh

# 4. Web app
pnpm install
cd apps/web && cp .env.local.example .env.local
pnpm dev                                                  # http://localhost:3000
```

## Running tests

| Suite | Command | Notes |
| :--- | :--- | :--- |
| API unit tests | `mvn -f apps/api/pom.xml test` | |
| API integration tests (Testcontainers) | `mvn -f apps/api/pom.xml verify` | Docker required; includes booking-concurrency, outbox-chaos, and tenant-isolation penetration ITs |
| Consumer ITs | `mvn -f services/notification-service/pom.xml verify` (same for `audit-service`) | Idempotency / dedup tests |
| Web unit tests (Vitest) | `pnpm --filter web test` | |
| Web E2E (Playwright) | `pnpm --filter web test:e2e` | Needs API + web running |
| Typecheck web | `pnpm --filter web typecheck` | |
| Load tests (k6) | `k6 run tests/load/slot-availability.js` / `k6 run tests/load/booking-checkout.js` | NFR gates: p99 < 300 ms @ 500 RPS; 500 checkouts/min with zero double-bookings (`tests/load/verify-no-double-bookings.sh`). See `tests/load/README.md` for fixtures |
| Security audit | `./scripts/security-audit.sh` | Zero-tolerance static checks (tenant isolation, domain terms, secrets, logging) |

## CI/CD & deployment

- **CI** — `.github/workflows/ci.yml`: quality gates for the API (build, tests, security audit), matrix build for both consumer services, web build + typecheck + tests, then gated deploys to staging and production (OIDC to AWS).
- **AWS** — `infra/aws/cdk/` (TypeScript CDK app: VPC, Secrets, RDS PostgreSQL, ElastiCache Redis, MSK, ECS Fargate stacks):

  ```bash
  cd infra/aws/cdk && npm install
  npm run typecheck && npm run synth
  npm run deploy          # cdk deploy --all --require-approval broadening
  ```

## Implementation status

All **5 phases are code-complete**:

1. **Foundation & scaffold** — monorepo, local dev stack, Flyway baseline (V001–V009), Spring Security + tenant isolation, OTP/JWT auth, dispatch adapters, Next.js auth flow.
2. **Core booking engine** — location/resource/service-type/holiday CRUD, on-demand slot calculator, PENDING_HOLD → CONFIRMED lifecycle with pessimistic locking, hold GC, Redis caching, booking UI + admin portal + form builder.
3. **Kafka event mesh** — outbox (V012), Debezium CDC connector, Avro schemas, notification & audit consumer services with `processed_events` idempotency (V013), chaos/idempotency ITs.
4. **Agentic intelligence** — booking-pattern ingestion into agent memory, peak/anomaly detection, AI slot-optimization endpoint, orchestration workflow templates, auto-ADR hook.
5. **Production hardening** — k6 load suites for the NFR gates, full security audit + tenant-isolation pen test, cache warm-up, AWS CDK stacks, CI/CD pipeline, Prometheus/Grafana observability.

Verification notes (authoring sandbox):

- **Java modules have not been compiled in the authoring sandbox** (Maven Central is blocked there). Static conformance checks pass (package/dir consistency, import resolution, brace balance, tenant-isolation and domain-term greps, migration numbering V001–V015 with matching undo scripts). Run `mvn verify` on first checkout to compile and execute the full test suite.
- **Web app**: `tsc --noEmit` clean, `next build` clean, Vitest suite green.
- **AWS CDK app**: `tsc --noEmit` clean.
- Undo scripts in `apps/api/src/main/resources/db/undo/` are manual-rollback helpers (U001–U015), not registered with Flyway OSS.
