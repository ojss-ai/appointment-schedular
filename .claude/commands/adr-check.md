# /adr-check

Check whether recent code changes require a new Architecture Decision Record.

## Steps

1. **Get changed files**
   - `git diff --name-only HEAD~1 HEAD`

2. **Evaluate each change against ADR triggers**
   - New `@Entity` class → potential domain model change (check ADR-005)
   - New Kafka producer/consumer → requires ADR if topic is new
   - New external dependency in `pom.xml` or `package.json` → check if significant
   - Change to `SecurityConfig` or JWT builder → check ADR relevance
   - New locking strategy or caching layer → definitely needs an ADR
   - Also evaluate the **Project-Specific Triggers** section below

3. **Cross-reference existing ADRs**
   - List all files in `docs/ADR/` and check if the change contradicts or extends an existing decision.
   - Consult the **Existing ADR Cross-Reference** table below before creating any stub.

4. **Generate ADR stub if needed**
   - If a trigger is hit and no existing ADR covers it, create `docs/ADR/ADR-{next_number}-{description}.md` using the **ADR Stub Template** below (also mirrored in `.claude/agents/adr-docs.md`).
   - Numbering continues from the current maximum in `docs/ADR/`.
   - Status: `Proposed` — NEVER write `Accepted` automatically; a human must promote it.
   - After writing each stub, print exactly:
     `⚠ ADR stub created: docs/ADR/ADR-{NNN}-{description}.md — review and change status to Accepted before merging`

5. **Output**
   - If one or more stubs were created or existing ADRs referenced, print: `ADR check complete — {N} new ADR(s) proposed, {N} existing ADR(s) referenced`
   - If no triggers matched, use the **No-Change Response** below.

## Project-Specific Triggers (Scheduling Framework)
<!-- ATOM-ADR-005 — project-specific architectural trigger rules -->

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

```markdown
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
```

After writing the stub, print:
⚠ ADR stub created: docs/ADR/ADR-{NNN}-{description}.md — review and change status to Accepted before merging

## No-Change Response

When no triggers are matched across all changed files, print exactly:
ADR check complete — 0 new ADRs needed
