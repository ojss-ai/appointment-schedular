# scheduling:task-progress
> Maintained by: orchestrator agent
> Last updated: 2026-07-20

## Setup Complete
- Agent SDK: Claude Code native (no external MCP required)
- Slash commands: `.claude/commands/` registered
- Sub-agents: `.claude/agents/` (7 agents + spec-agent)
- Hooks: `.claude/settings.json` (PreToolUse + PostToolUse)

## Migrations dry-run (AC-01, ATOM-FLYWAY-MIGRATIONS-004)
Dry-run reviewed 2026-07-20: V001–V009 are schema-only CREATE TABLE + CREATE INDEX,
zero-downtime, all non-tenant tables carry `tenant_id UUID NOT NULL` FK to
`tenants(id)`, no slots table, undo scripts U001–U009 are pure DROP TABLE.
Approved for apply.

| Atom ID | Title | Status | Completed |
|---|---|---|---|
| ATOM-MONOREPO-SCAFFOLD-001 | Monorepo scaffold + toolchain | ✅ Complete | 2026-07-20 |
| ATOM-CLAUDE-AGENT-SETUP-002 | Claude Code agent configuration | ✅ Complete | 2026-07-20 |
| ATOM-LOCAL-DEV-003 | Docker Compose local dev stack | ✅ Complete | 2026-07-20 |
| ATOM-FLYWAY-MIGRATIONS-004 | Baseline migrations V001–V009 | ✅ Complete | 2026-07-20 |
| ATOM-SPRING-SECURITY-005 | Spring Boot baseline + security | ✅ Complete | 2026-07-20 |
| ATOM-OTP-REDIS-006 | OTP generation + Redis TTL | ✅ Complete | 2026-07-20 |
| ATOM-JWT-BUILDER-007 | JWT issuance + verification | ✅ Complete | 2026-07-20 |
| ATOM-NOTIFICATION-DISPATCH-008 | SES + Twilio dispatch adapters | ✅ Complete | 2026-07-20 |
| ATOM-AUTH-FLOW-009 | Auth controller + integration tests | ✅ Complete | 2026-07-20 |
| ATOM-NEXTJS-AUTH-010 | Next.js auth flow UI | ✅ Complete | 2026-07-20 |
| ATOM-ANALYTICS-001 | Booking pattern nightly memory ingestion (V015 index, 02:00 UTC job) | ✅ Complete | 2026-07-20 |
| ATOM-ORCHESTRATION-002 | Orchestration workflow templates (4 new in .claude/workflows/) | ✅ Complete | 2026-07-20 |
| ATOM-ANALYTICS-003 | AI slot optimization endpoint (Claude API via RestClient, feature-flagged off; Redis 24h cache) | ✅ Complete | 2026-07-20 |
| ATOM-ANALYTICS-004 | Peak window + anomaly detectors (02:30/02:45 UTC, Prometheus gauge) | ✅ Complete | 2026-07-20 |
| ATOM-ADR-005 | Auto ADR generation triggers + hook (/adr-check extended) | ✅ Complete | 2026-07-20 |
