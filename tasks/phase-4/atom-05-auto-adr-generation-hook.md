---
description: Atom design document for the auto ADR generation hook and extended trigger rules
---

# ATOM-ADR-005: Auto ADR Generation Hook for Architectural Changes

**Status**: ✅ Complete (2026-07-20 — triggers/cross-reference/stub template appended to /adr-check; adr-docs agent updated; PostToolUse Edit|Write hook added to .claude/settings.json; CI reminder deferred to P5-T07 — .github/workflows/ci.yml does not exist yet)
**Feature**: auto-adr-generation
**Phase**: 4 (Intelligence)
**Tags**: [ADR]
**Complexity**: Medium
**Agent**: adr-docs
**Dependencies**: ATOM-SETUP-002 (P1 atom-02) — `.claude/commands/adr-check.md` exists; Claude agent setup complete
**Blocks**: None
**PR**: TBD

---

## Overview

This atom extends the existing `/adr-check` slash command with six project-specific architectural trigger patterns and verifies that the `adr-docs` agent can automatically generate ADR stub files when those patterns are detected in changed files. The `adr-docs` agent writes stubs to `docs/ADR/ADR-{NNN}-{description}.md` with status `Proposed` and prompts the human to promote the status to `Accepted` before merging. The key design decision is appending trigger rules to the existing command file rather than creating a separate config file, keeping all ADR detection logic in one auditable place.

---

## User Story

```
As a Tenant Admin
I want architectural changes to automatically trigger ADR stub creation
So that no significant architectural decision is merged without a corresponding record in docs/ADR/
```

---

## Acceptance Criteria

- [ ] **AC-01**: Running `/adr-check` on a file containing a new `@KafkaListener` annotation creates an ADR stub in `docs/ADR/` with status `Proposed`
- [ ] **AC-02**: The generated ADR stub contains: a date header, status `Proposed`, a Context section naming the triggering file and class, an empty Decision section with `[Human to fill in]`, and a Consequences section
- [ ] **AC-03**: Human is prompted with the message `⚠ ADR stub created: docs/ADR/ADR-{NNN}-{description}.md — review and change status to Accepted before merging` after each stub is written
- [ ] **AC-04**: All six project-specific trigger rules are appended to `.claude/commands/adr-check.md`
- [ ] **AC-05**: Existing ADRs (ADR-001 through ADR-005) are cross-referenced correctly — `/adr-check` reports existing coverage for patterns already governed by those ADRs
- [ ] **AC-06**: Running `/adr-check` when no architectural changes are detected in changed files returns: `ADR check complete — 0 new ADRs needed`
- [ ] **AC-07 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) appear in any ADR stub template, trigger rule text, or generated stub content

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | Manual: run `/adr-check` on a file with new `@KafkaListener` | `.claude/commands/adr-check.md` | 🔜 Planned |
| AC-02 | Manual: inspect generated stub structure | `adr-docs` agent stub template | 🔜 Planned |
| AC-03 | Manual: verify agent console output after stub creation | `adr-docs` agent prompt | 🔜 Planned |
| AC-04 | Grep `.claude/commands/adr-check.md` for all 6 triggers | `.claude/commands/adr-check.md` | 🔜 Planned |
| AC-05 | Manual: run `/adr-check` on file modifying bookings table | `.claude/commands/adr-check.md` ADR cross-ref section | 🔜 Planned |
| AC-06 | Manual: run `/adr-check` with no architectural changes | `.claude/commands/adr-check.md` | 🔜 Planned |
| AC-07 | Static review + grep of trigger rules and stub template | `.claude/commands/adr-check.md` | 🔜 Planned |

<!-- AC validation passed: TBD, 7 criteria written, all marked TBD -->

---

## Technical Design

### Architecture

The `/adr-check` command is a Markdown file in `.claude/commands/` that the `adr-docs` agent reads and executes when invoked. This atom appends a "Project-Specific Triggers" section to the existing command file — no new files are created for the trigger logic itself. When a trigger is detected, the `adr-docs` agent uses a stub template (defined in this atom) to generate a new Markdown ADR file in `docs/ADR/`. The ADR numbering sequence continues from the highest existing ADR number. CI integration provides a reminder comment in `.github/workflows/ci.yml`.

### Data Flow / Sequence

```
Developer commits changes to repo
  → Claude Code session: runs /adr-check (or PostToolUse hook triggers adr-docs agent)
  → adr-docs agent reads .claude/commands/adr-check.md
  → adr-docs agent scans changed files against trigger patterns:
      - @KafkaListener added?
      - @Cacheable with new value name added?
      - SlotCalculatorService algorithm changed?
      - BookingService lock/isolation level changed?
      - Flyway migration altering bookings/resources/tenants structurally?
      - New external dependency added to pom.xml or package.json?
  → if trigger matched AND no existing ADR covers it:
      → determine next ADR number (max existing + 1)
      → write docs/ADR/ADR-{NNN}-{description}.md from stub template
      → print: ⚠ ADR stub created: docs/ADR/ADR-{NNN}-{description}.md — review and change status to Accepted before merging
  → if no triggers matched:
      → print: ADR check complete — 0 new ADRs needed
```

### File Structure

```
.claude/
├── commands/
│   └── adr-check.md         ← MODIFIED — append project-specific triggers section

docs/ADR/
├── ADR-001-slot-generation-strategy.md         ← existing
├── ADR-002-concurrency-locking-model.md        ← existing
├── ADR-003-transactional-outbox-pattern.md     ← existing
├── ADR-004-multi-tenancy-isolation-model.md    ← existing
├── ADR-005-domain-abstraction-model.md         ← existing
└── ADR-{NNN}-{description}.md                  ← NEW stubs written by adr-docs agent

.github/workflows/
└── ci.yml                   ← MODIFIED (in P5-T07) — add ADR check reminder step
```

### Interface Contracts

There are no Java or TypeScript interfaces for this atom. The contracts are the Markdown trigger format and stub template.

**Trigger rule format** (appended to `.claude/commands/adr-check.md`):

```
- {trigger description} → {action: "create ADR" | "check if topic is new → create ADR" | "consider ADR if new cache strategy"}
```

**ADR stub template** (used by `adr-docs` agent on trigger detection):

```markdown
# ADR-{NNN}: [{Short title describing the decision}]

**Date:** {YYYY-MM-DD}
**Status:** Proposed
**Deciders:** adr-docs agent (human review required before Accepted)

## Context

{Brief description of what changed and why an ADR is needed.}
A new `{trigger pattern}` was detected in `{FileName}.java`.

## Decision

[Human to fill in: what was decided and why]

## Consequences

**Positive:**
- [Human to fill in]

**Negative / Trade-offs:**
- [Human to fill in]

## Alternatives Considered

| Option | Reason rejected |
|--------|-----------------|
| [alternative] | [reason] |
```

**Cross-reference mapping** (existing ADRs and the triggers they cover):

| Trigger | Covered by ADR |
|---------|----------------|
| `SlotCalculatorService` algorithm change | ADR-001 (slot generation strategy) |
| `BookingService` concurrency strategy change | ADR-002 (concurrency locking model) |
| New `outboxService` write pattern | ADR-003 (transactional outbox pattern) |
| New table added without `tenant_id` | ADR-004 (multi-tenancy isolation model) |
| New JSONB `extension` column usage in core logic | ADR-005 (domain abstraction model) |

### Design Rationale

- **Why append to the existing command file rather than create a separate config**: A single command file is easier to audit in code review and simpler for the `adr-docs` agent to parse. Splitting trigger logic across files introduces ambiguity about which file takes precedence.
- **Why `Proposed` as the initial stub status**: Forces human review before an ADR is considered authoritative. The `adr-docs` agent cannot judge correctness of an architectural decision; it can only detect that one was made.
- **Why include existing ADR cross-references in the trigger rules**: Prevents duplicate ADR creation for patterns already governed by ADR-001 through ADR-005. The agent checks the cross-reference table before generating a new stub.
- **Why a CI reminder rather than a full CI enforcement gate**: Enforcing ADR creation in CI would block PRs on detection errors. The current project phase (pre-production) favours developer autonomy with a reminder rather than hard enforcement; this can be promoted to a gate in Phase 5.

---

## Test Strategy

**Test type**: Manual walkthrough (no automated tests — this atom is agent-driven Markdown tooling)

```
- kafkaListenerTrigger_createsAdrStub:
    Given: a file containing a new @KafkaListener annotation is in the changed file set
    Assert: /adr-check creates docs/ADR/ADR-{NNN}-*.md with status Proposed;
            agent prints ⚠ stub created message

- noChanges_returnsZeroAdrsNeeded:
    Given: changed files contain no trigger patterns
    Assert: /adr-check prints "ADR check complete — 0 new ADRs needed"; no file created

- existingAdrCoverage_doesNotDuplicateStub:
    Given: changed file modifies SlotCalculatorService algorithm
    Assert: /adr-check reports "Covered by ADR-001"; no new stub created

- newExternalDependency_createsAdrStub:
    Given: pom.xml has a new <dependency> with no existing ADR
    Assert: /adr-check creates ADR stub for the new dependency

- stubContent_matchesTemplate:
    Given: /adr-check creates a stub
    Assert: stub contains Date, Status: Proposed, Context, Decision (with [Human to fill in]),
            Consequences, and Alternatives Considered sections

- noIndustrySpecificTerms_inTriggerRulesAndStubs:
    Given: all trigger rules and the stub template
    Assert: grep for doctor|patient|vehicle|mechanic returns zero matches
```

**Coverage requirements**:
- Manual walkthrough of all 6 trigger patterns required before marking atom complete
- Each pattern must produce a distinct stub with the correct triggering class/annotation noted in Context

---

## Implementation Constraints

- Append trigger rules to the existing `.claude/commands/adr-check.md` — do not create a new file
- ADR stubs must be written to `docs/ADR/` with sequential numbering continuing from the current maximum
- ADR stub status must be `Proposed` — the `adr-docs` agent must never write `Accepted` automatically
- Human prompt message format is fixed: `⚠ ADR stub created: docs/ADR/ADR-{NNN}-{description}.md — review and change status to Accepted before merging`
- No industry-specific terms in trigger rules, stub templates, or generated content
- Cross-reference table must include all five existing ADRs (ADR-001 through ADR-005)
- CI integration in `.github/workflows/ci.yml` is a reminder comment only — not a blocking gate — until Phase 5

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Write a grep-based check: confirm `.claude/commands/adr-check.md` does NOT yet contain the 6 project-specific triggers — passes (they don't exist yet); document expected post-state
2. Write a manual test script: run `/adr-check` on a test file with `@KafkaListener` — assert stub is NOT created (trigger rules missing) — this is the RED state

### GREEN — Minimum code to pass

1. Append the "Project-Specific Triggers" section to `.claude/commands/adr-check.md` with all 6 trigger rules
2. Add the ADR cross-reference table to `.claude/commands/adr-check.md`
3. Update the `adr-docs` agent definition (`.claude/agents/adr-docs.md`) to include the stub template and the `⚠` prompt message format
4. Verify: run `/adr-check` on a file with `@KafkaListener` — stub created with correct structure

### REFACTOR — Quality pass

1. Run `/adr-check` against each of the 6 trigger patterns individually — confirm correct stub or cross-reference for each
2. Verify no industry-specific terms in trigger rules or stub template via grep
3. Confirm existing ADR-001 through ADR-005 are not duplicated when their trigger patterns are detected

---

## Implementation Reference

### Project-Specific Triggers (append to `.claude/commands/adr-check.md`)

**File**: `.claude/commands/adr-check.md` — append after existing general triggers section

```markdown
## Project-Specific Triggers (Scheduling Framework)

In addition to the general triggers above, the following patterns require ADR evaluation:

- New `@KafkaListener` annotation added to any class → check if the topic is new → create ADR if a new consumer group is introduced
- New `@Cacheable` annotation with a value name not previously used → consider ADR if this represents a new cache strategy or eviction policy
- Any change to `SlotCalculatorService` core algorithm (availability computation logic) → always create ADR (core domain logic — covered by ADR-001; create a new ADR if the algorithm changes)
- Any change to `BookingService` concurrency strategy (new lock type, new transaction isolation level, or Redis lock introduction) → always create ADR (covered by ADR-002 if pessimistic lock; new ADR if new strategy)
- New Flyway migration that structurally alters the `bookings`, `resources`, or `tenants` table (new column, type change, constraint change) → create ADR if the change affects the multi-tenancy model or domain abstraction
- New external dependency added to `pom.xml` or `package.json` with no existing ADR covering its use → create ADR documenting the dependency, its version, and the rationale for inclusion

## Existing ADR Cross-Reference

Before creating a new stub, check whether the detected change is already governed by an existing ADR:

| Trigger | Covered by ADR | Action |
|---------|----------------|--------|
| SlotCalculatorService algorithm change | ADR-001 (slot generation strategy) | Note ADR-001 in PR; new ADR only if strategy fundamentally changes |
| BookingService lock or isolation level change | ADR-002 (concurrency locking model) | Note ADR-002 in PR; new ADR only if switching to Redis or optimistic lock |
| New outboxService write pattern | ADR-003 (transactional outbox pattern) | Note ADR-003; new ADR only if bypassing outbox |
| New table missing tenant_id | ADR-004 (multi-tenancy isolation model) | Block PR; tenant_id is required on every table |
| Core logic reading extension JSONB | ADR-005 (domain abstraction model) | Block PR; extension column is for tenant metadata only |

## ADR Stub Template

Use the following template when generating a new stub:

\```markdown
# ADR-{NNN}: [{Short title describing the decision}]

**Date:** {YYYY-MM-DD}
**Status:** Proposed
**Deciders:** adr-docs agent (human review required before Accepted)

## Context

{Brief description of what changed and why an ADR is needed.}
A new `{trigger pattern}` was detected in `{FileName}.java`.
This introduces {brief description of the new component or change}.

## Decision

[Human to fill in: what was decided and why]

## Consequences

**Positive:**
- [Human to fill in]

**Negative / Trade-offs:**
- [Human to fill in]

## Alternatives Considered

| Option | Reason rejected |
|--------|-----------------|
| [alternative] | [reason] |
\```

After writing the stub, print:
⚠ ADR stub created: docs/ADR/ADR-{NNN}-{description}.md — review and change status to Accepted before merging

## No-Change Response

When no triggers are matched across all changed files, print exactly:
ADR check complete — 0 new ADRs needed
```

### CI Integration Reminder

**File**: `.github/workflows/ci.yml` — add step in the PR validation job (implemented fully in P5-T07)

```yaml
- name: ADR check reminder
  run: |
    echo "--------------------------------------------------------------"
    echo "REMINDER: Run /adr-check in your Claude Code session before"
    echo "merging if you made any of the following changes:"
    echo "  - New @KafkaListener or @Cacheable annotation"
    echo "  - SlotCalculatorService or BookingService algorithm change"
    echo "  - Structural Flyway migration on core tables"
    echo "  - New external dependency in pom.xml or package.json"
    echo "--------------------------------------------------------------"
```

---

## Integration Points

**Depends on**: ATOM-SETUP-002 (P1) — `.claude/commands/adr-check.md` exists; `adr-docs` agent definition in `.claude/agents/adr-docs.md` exists; `docs/ADR/` contains ADR-001 through ADR-005

**Enables**: Every future architectural change in phases 4 and 5 can automatically generate an ADR stub via the extended trigger rules

**Cascading updates required**:
- `.claude/agents/adr-docs.md` — add stub template and `⚠` prompt message format to agent instructions
- `tasks/MASTER-TASK-LIST.md` — mark ATOM-ADR-005 complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `.claude/commands/adr-check.md` | Modified | Append 6 project-specific triggers, cross-reference table, stub template, and no-change response |
| `.claude/agents/adr-docs.md` | Modified | Add stub template and prompt message format to agent instructions |
| `.github/workflows/ci.yml` | Modified (P5-T07) | Add ADR check reminder step |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] All 6 project-specific trigger rules appended to `.claude/commands/adr-check.md`
- [ ] Existing ADR cross-reference table (ADR-001 through ADR-005) included in command file
- [ ] ADR stub template included in command file and in `adr-docs` agent definition
- [ ] `⚠ ADR stub created` prompt message format documented in agent instructions
- [ ] Zero industry-specific terms in trigger rules, stub template, or generated content
- [ ] Manual walkthrough completed for all 6 trigger patterns
- [ ] Running `/adr-check` with no changes returns `ADR check complete — 0 new ADRs needed`
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: auto-adr-generation | Phase: 4*
