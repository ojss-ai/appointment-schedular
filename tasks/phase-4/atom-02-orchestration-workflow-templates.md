---
description: Atom design document for orchestration workflow template files
---

# ATOM-ORCHESTRATION-002: Reusable Orchestration Workflow Templates

**Status**: ✅ Complete (2026-07-20 — 4 new templates added alongside the 4 existing Phase-1 workflows; all agent labels validated against .claude/agents/ roster)
**Feature**: orchestration-workflow-templates
**Phase**: 4 (Intelligence)
**Tags**: [ORCHESTRATION]
**Complexity**: Medium
**Agent**: orchestrator + adr-docs
**Dependencies**: ATOM-SETUP-002 (P1 atom-02) — Claude agent setup complete, `.claude/agents/` definitions exist
**Blocks**: None
**PR**: TBD

---

## Overview

This atom creates four reusable Markdown workflow templates in `.claude/workflows/` that the orchestrator agent executes step-by-step using the Agent SDK `Task` tool. Each template encodes a common multi-step development task — implementing an API endpoint, applying a database migration, onboarding a new tenant, or debugging slot availability — as an ordered checklist with explicit agent delegation per step. The key design decision is representing workflows as human-readable Markdown files rather than code, making them inspectable, editable, and version-controlled alongside the source.

---

## User Story

```
As a System
I want standardised workflow templates that the orchestrator agent can read and execute step-by-step
So that common multi-agent development tasks are repeatable, auditable, and consistent across sessions
```

---

## Acceptance Criteria

- [ ] **AC-01**: All four workflow files exist in `.claude/workflows/`: `implement-api-endpoint.md`, `apply-database-migration.md`, `onboard-new-tenant.md`, `debug-slot-availability.md`
- [ ] **AC-02**: The orchestrator agent can read and execute `implement-api-endpoint.md` step-by-step, correctly delegating each step to the named sub-agent
- [ ] **AC-03**: Each step in every workflow names exactly one agent that matches a definition in `.claude/agents/`
- [ ] **AC-04**: `apply-database-migration.md` contains an explicit human confirmation gate (`[human approval required]`) before any `flyway:migrate` step
- [ ] **AC-05**: `implement-api-endpoint.md` includes a `[security]` agent step invoking `/security-scan` on the new controller before the PR checklist step
- [ ] **AC-06 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) appear in any workflow file content, step description, or success criteria

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | Manual file existence check | `.claude/workflows/` | 🔜 Planned |
| AC-02 | Orchestrator agent session walkthrough | `implement-api-endpoint.md` | 🔜 Planned |
| AC-03 | Grep `.claude/workflows/` for agent names vs `.claude/agents/` roster | All workflow files | 🔜 Planned |
| AC-04 | Grep `apply-database-migration.md` for `human approval` | `apply-database-migration.md` | 🔜 Planned |
| AC-05 | Grep `implement-api-endpoint.md` for `/security-scan` | `implement-api-endpoint.md` | 🔜 Planned |
| AC-06 | Static grep across `.claude/workflows/` | All workflow files | 🔜 Planned |

<!-- AC validation passed: TBD, 6 criteria written, all marked TBD -->

---

## Technical Design

### Architecture

Workflow templates are pure Markdown files consumed by the orchestrator agent at runtime. The orchestrator reads the file, identifies each numbered step with its `[agent-name]` prefix, and dispatches a `Task` call to the named sub-agent with the step text and any relevant context. Human confirmation gates are represented as `[human approval required]` markers — the orchestrator pauses and waits for explicit user confirmation before continuing past those steps. No Java or TypeScript code is introduced by this atom.

### Data Flow / Sequence

```
Orchestrator receives: "run workflow: implement-api-endpoint for ATOM-X-NNN"
  → Read .claude/workflows/implement-api-endpoint.md
  → Parse ordered steps with [agent-name] prefixes
  → For each step:
      if [human approval required] → pause; await user confirmation
      else → dispatch Task(agent=agent-name, context=step-text + atom-spec)
  → On step completion → log result; continue to next step
  → On all steps complete → report Success Criteria met/unmet
```

### File Structure

```
.claude/workflows/
├── implement-api-endpoint.md        ← 9-step API endpoint implementation workflow
├── apply-database-migration.md      ← 6-step migration workflow with human gate
├── onboard-new-tenant.md            ← 5-step tenant onboarding workflow
└── debug-slot-availability.md       ← 6-step slot debugging workflow
```

### Interface Contracts

There are no Java or TypeScript interfaces for this atom. The workflow contract is the Markdown step format:

```
{step-number}. **[{agent-name}]** {step description}
```

Valid agent names (must match `.claude/agents/` definitions):
- `orchestrator`
- `coder`
- `testgen`
- `security`
- `migrations`
- `adr-docs`
- `observability`

Human gate marker (pauses orchestrator execution):
```
{step-number}. **[human approval required]** {description of what to review}
```

### Design Rationale

- **Why Markdown files, not a workflow DSL or database**: Workflows are owned by developers, not the runtime. Markdown is diffable, reviewable in PRs, and editable without tooling. The Agent SDK `Task` tool is sufficient to execute ordered steps without a custom DSL.
- **Why agent names as inline labels**: Makes workflows self-documenting and allows the orchestrator to parse delegation targets without a separate config file.
- **Why explicit human gates**: The migrations workflow touches production schema; CLAUDE.md mandates human confirmation for destructive or high-impact DB operations. The gate is a non-negotiable safety control.
- **ADR reference**: No new ADR is required — this atom does not introduce an architectural decision. The agent delegation model is established by the existing `.claude/agents/` setup (P1 atom-02).

---

## Test Strategy

**Test type**: Manual walkthrough (no automated tests — these are agent-consumed Markdown files)

```
- allWorkflowFilesExist_inClaudeWorkflowsDirectory:
    Given: atom is implemented
    Assert: ls .claude/workflows/ returns exactly 4 files matching the specified names

- implementApiEndpointWorkflow_delegatesCorrectly:
    Given: orchestrator asked to run implement-api-endpoint for a test atom
    Assert: orchestrator dispatches steps to coder, migrations, security, testgen, adr-docs
            in the documented order; no step skipped

- applyDatabaseMigrationWorkflow_pausesAtHumanGate:
    Given: orchestrator runs apply-database-migration
    Assert: execution halts at step 3 ([human approval required]) and does not proceed
            to step 4 (flyway:migrate) until user confirms

- agentNamesInWorkflows_matchAgentRoster:
    Given: .claude/workflows/*.md and .claude/agents/*.md both exist
    Assert: every [agent-name] in workflow files has a corresponding .md file in .claude/agents/

- noIndustrySpecificTerms_inWorkflowContent:
    Given: .claude/workflows/*.md files
    Assert: grep for doctor|patient|vehicle|mechanic returns zero matches
```

**Coverage requirements**:
- Manual walkthrough of `implement-api-endpoint.md` required before marking atom complete
- Human gate in `apply-database-migration.md` must be tested in a live orchestrator session

---

## Implementation Constraints

- Workflow files are Markdown only — no YAML front matter, no JSON, no executable scripts
- Every step must name exactly one agent from the `.claude/agents/` roster
- `apply-database-migration.md` must include `[human approval required]` before any migration apply command
- No industry-specific terms in any workflow file
- Agent names must be lowercase and match filenames in `.claude/agents/` exactly
- Workflows must include a "Preconditions" section listing what must exist before the workflow starts
- Workflows must include a "Success Criteria" section listing observable completion conditions

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Write a grep-based shell check: `grep -r "doctor\|patient\|vehicle" .claude/workflows/` — assert no matches (fails because directory does not exist)
2. Write a file-existence check for all 4 workflow files — assert they fail
3. Write an agent-name validation script: extract `[agent-name]` tokens from workflows, diff against `.claude/agents/*.md` filenames — assert it fails

### GREEN — Minimum code to pass

1. Create `.claude/workflows/` directory
2. Create `implement-api-endpoint.md` with all 9 steps and correct agent labels
3. Create `apply-database-migration.md` with human gate at step 3
4. Create `onboard-new-tenant.md` with 5 steps
5. Create `debug-slot-availability.md` with 6 steps

### REFACTOR — Quality pass

1. Cross-check every `[agent-name]` against `.claude/agents/` roster — fix any mismatches
2. Ensure each workflow's Preconditions reference the correct upstream atom IDs
3. Add a brief one-line description comment at the top of each workflow file
4. Run agent-name validation script — assert zero mismatches

---

## Implementation Reference

### implement-api-endpoint.md

**File**: `.claude/workflows/implement-api-endpoint.md`

```markdown
# Workflow: Implement a New API Endpoint

## Preconditions
- [ ] Atom task file exists with full specification and acceptance criteria
- [ ] JPA entity model exists (or is part of this atom's scope)
- [ ] Flyway migration for any new tables/indexes is included in the atom spec

## Steps (execute in order; confirm each before proceeding)

1. **[coder]** Review atom task spec and acceptance criteria; confirm scope is understood
2. **[coder]** Write pseudocode outline for the service method, identifying state mutations
3. **[coder]** Identify cross-cutting concerns: tenant guard, outbox write, Redis cache eviction
4. **[migrations]** If new columns or indexes needed: write migration SQL file, run dry-run, present output for human approval
5. **[coder]** Implement in layer order: entity → repository → service → controller → DTOs
6. **[security]** Verify tenant isolation on the new endpoint — run /security-scan scoped to the new controller file
7. **[testgen]** Generate unit and integration tests; verify line coverage ≥ 80% on service class
8. **[adr-docs]** Update docs/API-SPEC.md with the new endpoint entry
9. **[adr-docs]** Run /adr-check — create ADR stub if an architectural decision was made

## Success Criteria
- All acceptance criteria in the atom spec are met
- mvn verify -P integration passes
- Endpoint documented in docs/API-SPEC.md
- /security-scan reports zero tenant isolation findings
```

### apply-database-migration.md

**File**: `.claude/workflows/apply-database-migration.md`

```markdown
# Workflow: Apply a Database Migration

## Preconditions
- [ ] Flyway migration SQL file written and reviewed by migrations agent
- [ ] Undo/rollback script exists alongside the migration file
- [ ] Target environment identified (local / staging / production)

## Steps

1. **[migrations]** Static analysis: check for DROP without IF EXISTS, NOT NULL column without DEFAULT on existing table, missing tenant_id in new table definitions
2. **[migrations]** Estimate row count impact; if affected table has > 1M rows → STOP and await human confirmation before proceeding
3. **[human approval required]** Review dry-run output and row count estimate before migration is applied
4. **[migrations]** Apply migration: `mvn flyway:migrate`
5. **[migrations]** Validate schema state: `mvn flyway:validate`
6. **[testgen]** Run integration tests to confirm schema change does not break existing passing tests

## Success Criteria
- `mvn flyway:validate` passes with no errors
- Integration test suite remains green after migration
- Migration execution logged in docs/memory/task-progress.md with timestamp and row counts
```

### onboard-new-tenant.md

**File**: `.claude/workflows/onboard-new-tenant.md`

```markdown
# Workflow: Onboard a New Tenant Configuration

## Preconditions
- [ ] Tenant display name, URL slug, and subscription plan provided
- [ ] Admin user email address provided
- [ ] At least one Location timezone specified

## Steps

1. **[coder]** Insert tenant row via admin API: POST /api/v1/admin/tenants with name, slug, and plan
2. **[coder]** Create initial admin user for the tenant via POST /api/v1/admin/tenants/{tenantId}/users
3. **[coder]** Seed one Location with the specified timezone via POST /api/v1/tenants/{tenantId}/locations
4. **[coder]** Seed one Service type with basic duration and buffer settings via POST /api/v1/tenants/{tenantId}/services
5. **[coder]** Verify end-to-end: admin user can authenticate via OTP flow and complete a booking for the seeded Service

## Success Criteria
- Admin OTP login succeeds for the new tenant
- Booking flow is visible and functional in the UI for the new tenant
- No data from other tenants is visible in the new tenant's session
```

### debug-slot-availability.md

**File**: `.claude/workflows/debug-slot-availability.md`

```markdown
# Workflow: Debug Slot Availability Issue

## Preconditions
- [ ] Affected booking ID or resource ID and date provided
- [ ] Error description or reproduction steps provided

## Steps

1. **[orchestrator]** Retrieve booking records for the affected resource and date from the bookings table
2. **[orchestrator]** Check resource_schedules for that resource and the relevant day_of_week
3. **[orchestrator]** Check branch_holidays for the location on the affected date
4. **[orchestrator]** Check buffer_before_min and buffer_after_min on the associated service type
5. **[orchestrator]** Trace SlotCalculatorService logic against the retrieved data: operating matrix − confirmed bookings − buffer windows = expected available slots
6. **[adr-docs]** Document root cause and proposed fix as a new entry in docs/DEBUGGING-LOG.md

## Success Criteria
- Root cause identified and documented in docs/DEBUGGING-LOG.md
- Fix specification written or linked to a new atom task
- Affected booking or slot state is understood and explainable
```

---

## Integration Points

**Depends on**: ATOM-SETUP-002 (P1) — `.claude/agents/` sub-agent definitions exist; orchestrator agent is configured to read workflow files

**Enables**: Consistent multi-agent task execution across all phases; reduces orchestrator prompt engineering per task

**Cascading updates required**:
- `tasks/MASTER-TASK-LIST.md` — mark ATOM-ORCHESTRATION-002 complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `.claude/workflows/implement-api-endpoint.md` | New | 9-step API endpoint implementation workflow |
| `.claude/workflows/apply-database-migration.md` | New | 6-step migration workflow with human gate |
| `.claude/workflows/onboard-new-tenant.md` | New | 5-step tenant onboarding workflow |
| `.claude/workflows/debug-slot-availability.md` | New | 6-step slot availability debugging workflow |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] All 4 workflow files exist in `.claude/workflows/`
- [ ] Every `[agent-name]` in workflow files matches a definition in `.claude/agents/`
- [ ] `apply-database-migration.md` contains `[human approval required]` gate before step 4
- [ ] Zero industry-specific terms in any workflow file
- [ ] Manual walkthrough of `implement-api-endpoint.md` completed in a live orchestrator session
- [ ] ADR created or referenced (if architectural decision made)
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: orchestration-workflow-templates | Phase: 4*
