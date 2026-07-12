# Appointment Scheduler

A multi-tenant, extensible, omni-industry scheduling framework — currently in the **specification phase**. This repo contains the full requirements, architecture, ADRs, and an atomized task breakdown, ready for AI-agent-driven implementation.

## The idea

A highly abstract scheduling core where booking mechanics are fully isolated from industry-specific business logic. The same engine can power a dental clinic, an auto shop, or a consulting firm:

- **Domain abstraction** — no industry terminology in the core; everything is a `Resource` (doctor, mechanic, booth) offering a `Service` (checkup, oil change, consultation).
- **Metadata flexibility** — tenant-specific domain data lives in PostgreSQL `JSONB`, no schema changes per industry.
- **Multi-tenancy isolation** — mandatory `tenant_id` enforced at the API and database levels.

## Target stack

| Layer | Technology |
| :--- | :--- |
| Frontend | Next.js 15 (App Router), TypeScript |
| Backend | Java 21, Spring Boot 3.x |
| Database | PostgreSQL 15+ (JSONB domain extensions) |
| Events | Apache Kafka + Confluent Schema Registry (Avro) |
| Cache / locking | Redis |

## Repository layout

```text
initial-requirement.md   Full SRS — functional & non-functional requirements
CLAUDE.md / AGENTS.md    Agent coordination memory & instructions
docs/                    PRD, architecture, API spec, DB schema, Kafka spec, security spec
docs/ADR/                Architecture decision records (slot generation, locking,
                         transactional outbox, tenancy isolation, domain abstraction)
specs/                   Spec templates for atomized work units
tasks/                   Master task list + phase 1-5 atomized implementation tasks
.claude/                 Claude Code agents, skills, commands, hooks, and workflows
```

## Workflow

The project is built for spec-first, agent-driven delivery: requirements → specs → atomized tasks → implementation, with custom sub-agents (orchestrator, coder, testgen, security, migrations, observability) and guard hooks (e.g. destructive DDL is blocked in favor of deprecation patterns).

## Status

Pre-development. Spec phase complete; implementation atoms defined in [tasks/](tasks/).
