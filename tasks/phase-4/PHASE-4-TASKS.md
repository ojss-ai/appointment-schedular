# Phase 4 — Agentic Intelligence Layer
**Duration:** Weeks 11–13
**Milestone:** Analytics pipeline active; booking patterns stored in memory files; AI optimization suggestions live; ADR automation running

---

## P4-T01 — Booking Analytics Memory and Nightly Ingestion
**Tags:** [ANALYTICS]
**Priority:** P2
**Estimate:** 1 day
**Agent:** coder + observability agent
**Depends on:** P3-T10 (audit data flowing)
**Atom file:** `atom-01-booking-analytics-memory-ingestion.md`

Nightly `@Scheduled` job aggregates booking patterns from `audit_log` into structured JSON files in `docs/memory/booking-patterns/`. Used by the AI optimization endpoint and peak detector.

---

## P4-T02 — Orchestration Workflow Templates
**Tags:** [ORCHESTRATION]
**Priority:** P2
**Estimate:** 1 day
**Agent:** orchestrator + adr-docs agent
**Depends on:** P1-T02
**Atom file:** `atom-02-orchestration-workflow-templates.md`

Reusable workflow Markdown files in `.claude/workflows/` define step-by-step plans for: implementing a new API endpoint, applying a DB migration, onboarding a tenant, and debugging slot availability.

---

## P4-T03 — AI Slot Optimization Suggestions
**Tags:** [SLOT] [ANALYTICS]
**Priority:** P2
**Estimate:** 2 days
**Agent:** coder + orchestrator agent
**Depends on:** P4-T01
**Atom file:** `atom-03-ai-slot-optimization-suggestions.md`

`GET /api/v1/tenants/{tenantId}/analytics/slot-optimization` — reads booking pattern files, applies utilization heuristics, calls Claude API (Haiku model) to generate human-readable suggestions. Results cached in Redis for 24 hours.

---

## P4-T04 — Analytics Scheduler — Peak Booking Detection
**Tags:** [ANALYTICS]
**Priority:** P2
**Estimate:** 1.5 days
**Agent:** coder + observability agent
**Depends on:** P4-T01
**Atom file:** `atom-04-analytics-scheduler-peak-detection.md`

`PeakWindowDetector` and `AnomalyDetector` run nightly via `@Scheduled`. Results written to `docs/memory/booking-patterns/peak-windows.json` and `anomalies.json`. Anomalies emit a Prometheus metric that triggers AlertManager.

---

## P4-T05 — Auto ADR Generation Hook for Architectural Changes
**Tags:** [ADR]
**Priority:** P2
**Estimate:** 0.5 days
**Agent:** adr-docs agent
**Depends on:** P1-T02
**Atom file:** `atom-05-auto-adr-generation-hook.md`

Extends the `/adr-check` slash command with project-specific trigger rules. `adr-docs` agent generates ADR stubs with status `Proposed` whenever a triggering change is detected. Human must change to `Accepted` before merging.
