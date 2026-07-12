# Workflow: New Feature — Specify → Clarify → Atomize

Use this workflow whenever adding a new feature to the scheduling framework.
The spec-agent handles all three stages. Run each command in order.

---

## Stage 1 — Specify

**Command:** `/specify <feature description>`

Describe *what* you want to build and *why*. Focus on user value. Do NOT mention tech stack, database tables, or implementation details.

**Good example:**
```
/specify Allow tenant admins to configure blackout periods where no bookings
can be made — for example, public holidays or maintenance windows. Blackout
periods apply to all resources at a location, or optionally to a single resource.
```

**Bad example** (too implementation-focused):
```
/specify Add a blackout_periods table with tenant_id, start_time, end_time
and modify the slot query to exclude these periods.
```

**Output:** `specs/<slug>/spec.md`

---

## Stage 2 — Clarify

**Command:** `/clarify`

The spec-agent will ask one question at a time. Answer each one. The agent will cover:
- Tenant isolation implications
- Slot computation impact
- Concurrency edge cases
- Kafka events needed
- Role / auth constraints
- Data migration requirements
- UI scope

**Output:** `specs/<slug>/clarifications.md`

When completeness score reaches 8/10 or higher, proceed.

---

## Stage 3 — Atomize

**Command:** `/atomize`

The spec-agent reads the spec + clarifications and generates atom task files with complete, production-ready code. It will:
1. Determine the correct phase
2. Number atoms sequentially
3. Apply correct routing tags (`[MIGRATION]`, `[SLOT]`, `[KAFKA]`, etc.)
4. Write full Spring Boot Java + Next.js TypeScript implementation code
5. Update `tasks/MASTER-TASK-LIST.md`

**Output:** `tasks/phase-N/atom-NN-<name>.md` files

---

## After Atomize

1. Review the generated atoms — check code quality and constraints
2. Run `/adr-check` — the adr-docs agent will flag if any atom requires a new ADR
3. Open the first atom and begin implementation

---

## Quick Reference

| Command | When | Output |
|---|---|---|
| `/specify <description>` | You have a new feature idea | `specs/<slug>/spec.md` |
| `/clarify` | After `/specify` | `specs/<slug>/clarifications.md` |
| `/atomize` | After `/clarify` (score ≥ 8) | `tasks/phase-N/atom-NN-*.md` files |
| `/adr-check` | After `/atomize` | Flags if new ADR needed |
| `/security-scan` | After implementing atoms | Security audit report |
| `/test-gap` | After implementing atoms | Missing test coverage |
