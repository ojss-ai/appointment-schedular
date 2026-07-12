# ATOM-CLAUDE-AGENT-SETUP-002: Claude Code Agent Configuration Setup

**Status**: 🟡 Planned
**Feature**: claude-agent-setup
**Phase**: 1 (Foundation)
**Tags**: [INFRA]
**Complexity**: Low
**Agent**: orchestrator
**Dependencies**: ATOM-MONOREPO-SCAFFOLD-001
**Blocks**: None
**PR**: TBD

---

## Overview

This atom verifies and completes the Claude Code agent configuration at the monorepo root, replacing any external agentic harness with Claude Code's native Agent SDK. It creates all 7 sub-agent definition files in `.claude/agents/`, all 5 slash command files in `.claude/commands/`, the settings hook file, and seeds the project memory namespace files in `docs/memory/`. The key design decision is that all orchestration runs natively through Claude Code's `Task` tool — no external MCP server or third-party harness is required.

---

## User Story

```
As a System
I want Claude Code agents to be fully configured and verifiable at session start
So that every sub-agent can be delegated tasks with correct role, context, and constraints
```

---

## Acceptance Criteria

- [ ] **AC-01**: All 7 agent definition files present in `.claude/agents/` (`orchestrator.md`, `coder.md`, `testgen.md`, `security.md`, `adr-docs.md`, `migrations.md`, `observability.md`)
- [ ] **AC-02**: All 5 slash command files present in `.claude/commands/` (`security-scan.md`, `test-gap.md`, `adr-check.md`, `migration-validate.md`, `cost-report.md`)
- [ ] **AC-03**: `.claude/settings.json` contains valid JSON with hooks and agent/command registrations
- [ ] **AC-04**: `docs/memory/` directory created with 5 namespace seed files (`domain-model.md`, `api-contracts.md`, `kafka-topology.md`, `security-rules.md`, `task-progress.md`)
- [ ] **AC-05**: Orchestrator agent can be invoked and correctly reads `CLAUDE.md` at session start
- [ ] **AC-06**: `/security-scan` command can be invoked from a Claude Code session
- [ ] **AC-07**: No external agentic harness, CLI tool, or MCP server required for agent coordination
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) in any agent definition, command file, or memory namespace file

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | Manual / CI file-exist check | `.claude/agents/` | 🔜 Planned |
| AC-02 | Manual / CI file-exist check | `.claude/commands/` | 🔜 Planned |
| AC-03 | `jq` validation in CI | `.claude/settings.json` | 🔜 Planned |
| AC-04 | Manual / CI file-exist check | `docs/memory/` | 🔜 Planned |
| AC-05 | Manual session test | `.claude/agents/orchestrator.md` | 🔜 Planned |
| AC-06 | Manual session test | `.claude/commands/security-scan.md` | 🔜 Planned |
| AC-07 | TBD | TBD | 🔜 Planned |
| AC-08 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 8 criteria rewritten, 8 marked TBD -->

---

## Technical Design

### Architecture

This atom configures Claude Code's Agent SDK infrastructure. The `.claude/agents/` directory contains Markdown prompt files that scope each sub-agent's role, constraints, and SPARC phases. The `.claude/commands/` directory contains slash-command definitions that agents and users invoke during development. The `docs/memory/` namespace files act as persistent context anchors that agents read and write across sessions — they are not source code, but structured Markdown tables that grow as the project progresses.

### File Structure

```
.claude/
├── agents/
│   ├── orchestrator.md      ← top-level router
│   ├── coder.md             ← Spring Boot + Next.js implementation
│   ├── testgen.md           ← unit / integration / E2E / load tests
│   ├── security.md          ← CVE scan, tenant isolation audit
│   ├── adr-docs.md          ← ADR generation + living docs sync
│   ├── migrations.md        ← Flyway SQL, dry-run, rollback
│   └── observability.md     ← Prometheus metrics, Grafana spec, cost log
├── commands/
│   ├── security-scan.md
│   ├── test-gap.md
│   ├── adr-check.md
│   ├── migration-validate.md
│   └── cost-report.md
└── settings.json

docs/memory/
├── domain-model.md
├── api-contracts.md
├── kafka-topology.md
├── security-rules.md
└── task-progress.md
```

### Interface Contracts

No runtime interfaces defined in this atom. All deliverables are configuration and documentation files.

### Design Rationale

- **ADR reference**: No ADR directly governs agent configuration, but the orchestrator routing rules in `CLAUDE.md` define how task tags (`[AUTH]`, `[SLOT]`, `[CONCURRENCY]`, etc.) map to specific agents — this atom makes those routes functional.
- Claude Code's native Agent SDK (`Task` tool) is used instead of external harnesses to keep the toolchain minimal and avoid credential/network dependencies at development time.

---

## Test Strategy

**Test type**: Manual session verification

```
- shouldLoadClaudeMd_atSessionStart:
    Given: orchestrator agent invoked in a new Claude Code session
    Assert: agent reads CLAUDE.md, AGENTS.md, and MASTER-TASK-LIST.md without error

- shouldInvokeSecurityScan_asSlashCommand:
    Given: a Claude Code session with .claude/commands/security-scan.md present
    Assert: /security-scan executes and returns output without "command not found" error

- shouldValidateSettingsJson:
    Given: .claude/settings.json written
    Assert: `jq . .claude/settings.json` parses without errors (valid JSON)

- shouldSeedMemoryNamespaces_withCorrectHeaders:
    Given: docs/memory/ files created
    Assert: each file contains the correct namespace header (e.g., `# scheduling:domain-model`)
```

**Coverage requirements**:
- No line coverage target — this atom produces no production logic
- All 7 agent files and 5 command files must exist before any subsequent atom begins

---

## Implementation Constraints

- No external MCP server or CLI harness — Claude Code native Agent SDK only
- `.claude/settings.json` must be valid JSON (verified by `jq` in CI)
- Each agent Markdown file must begin with a clear role statement and list its SPARC phases
- Memory namespace files must use the exact header format `# scheduling:{namespace}` to match context anchors in `CLAUDE.md`
- No `console.log` in Next.js; no `System.out.println` in Java — use pino / SLF4J
- All Next.js API calls through `apps/web/lib/api-client.ts`

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Write `scripts/verify-agents.sh` that checks for all 7 agent files, 5 command files, valid `settings.json`, and 5 memory files
2. Run the script — all checks fail (files don't exist yet)

### GREEN — Minimum code to pass

1. Create `.claude/agents/orchestrator.md` with routing rules from `CLAUDE.md`
2. Create remaining 6 agent definition files (`coder.md`, `testgen.md`, `security.md`, `adr-docs.md`, `migrations.md`, `observability.md`)
3. Create all 5 slash command files in `.claude/commands/`
4. Create `.claude/settings.json` with PreToolUse and PostToolUse hooks
5. Create `docs/memory/` with 5 seed files, each with correct namespace header and empty table
6. Document setup completion in `docs/memory/task-progress.md`
7. Run `scripts/verify-agents.sh` — all checks pass

### REFACTOR — Quality pass

1. Review each agent file for role clarity and constraint completeness
2. Ensure orchestrator routing rules exactly match those in `CLAUDE.md`
3. Run `/security-scan` manually to verify the command is functional

---

## Implementation Reference

### Memory namespace file template

**File**: `docs/memory/domain-model.md` (and equivalent for other namespaces)

```markdown
# scheduling:domain-model
> Maintained by: adr-docs agent
> Last updated: {date}

| Entity | Fields | Constraints | Updated |
|---|---|---|---|
```

### docs/memory/task-progress.md initial content

**File**: `docs/memory/task-progress.md`

```markdown
# scheduling:task-progress
> Maintained by: orchestrator agent
> Last updated: {date}

## Setup Complete
- Agent SDK: Claude Code native (no external MCP required)
- Slash commands: `.claude/commands/` (5 commands registered)
- Sub-agents: `.claude/agents/` (7 agents defined)
- Hooks: `.claude/settings.json` (PreToolUse + PostToolUse)

| Atom ID | Title | Status | Completed |
|---|---|---|---|
```

### Session start verification checklist

At the start of each session, the orchestrator agent must:
1. Read `CLAUDE.md` — architecture rules
2. Read `AGENTS.md` — agent roster
3. Read `tasks/MASTER-TASK-LIST.md` — active phase
4. Load current phase task list
5. Confirm `.claude/` directory is intact

---

## Integration Points

**Depends on**: ATOM-MONOREPO-SCAFFOLD-001 (`.claude/` directory structure must exist)

**Enables**: All subsequent atoms rely on agent definitions for correct task delegation; `/security-scan`, `/test-gap`, `/adr-check` commands become available immediately

**Cascading updates required**:
- `docs/memory/task-progress.md` — record atom complete
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `.claude/agents/orchestrator.md` | New | Top-level routing agent |
| `.claude/agents/coder.md` | New | Spring Boot + Next.js implementer |
| `.claude/agents/testgen.md` | New | Test generation agent |
| `.claude/agents/security.md` | New | Security audit agent |
| `.claude/agents/adr-docs.md` | New | ADR and living docs agent |
| `.claude/agents/migrations.md` | New | Flyway migration agent |
| `.claude/agents/observability.md` | New | Metrics and cost logging agent |
| `.claude/commands/security-scan.md` | New | /security-scan slash command |
| `.claude/commands/test-gap.md` | New | /test-gap slash command |
| `.claude/commands/adr-check.md` | New | /adr-check slash command |
| `.claude/commands/migration-validate.md` | New | /migration-validate slash command |
| `.claude/commands/cost-report.md` | New | /cost-report slash command |
| `.claude/settings.json` | New | Hooks configuration |
| `docs/memory/domain-model.md` | New | Entity definitions namespace seed |
| `docs/memory/api-contracts.md` | New | REST endpoint namespace seed |
| `docs/memory/kafka-topology.md` | New | Kafka topology namespace seed |
| `docs/memory/security-rules.md` | New | Security rules namespace seed |
| `docs/memory/task-progress.md` | New | Task progress namespace seed |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `jq . .claude/settings.json` passes (valid JSON)
- [ ] Zero industry-specific terms in any identifier or file path
- [ ] ADR created or referenced (if architectural decision made)
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: claude-agent-setup | Phase: 1*
