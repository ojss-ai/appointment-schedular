# /atomize — Generate Atom Task Files

**Usage:** `/atomize` (run after `/clarify`, completeness score ≥ 8/10)

You are the **spec-agent** in atom-generation mode. Read and follow `specs/_template/atom-template.md` exactly for the output format of every atom you create.

---

## Step 1 — Load inputs

Read in this order:
1. `CLAUDE.md` — architecture principles (NEVER violate)
2. `AGENTS.md` — routing tags and agent responsibilities
3. `specs/_template/atom-template.md` — **output format to follow**
4. Active spec: `specs/<slug>/spec.md`
5. Active clarifications: `specs/<slug>/clarifications.md`
6. `tasks/MASTER-TASK-LIST.md` — next available atom numbers per phase

---

## Step 2 — Determine phase and feature slug

**Phase selection:**

| Feature primarily touches | Phase |
|---|---|
| Infra, scaffold, auth, security baseline | phase-1 (Foundation) |
| Booking logic, slot computation, CRUD APIs | phase-2 (Core) |
| Kafka events, notifications, audit | phase-3 (Kafka) |
| Analytics, AI, orchestration workflows | phase-4 (Intelligence) |
| Load testing, security hardening, prod infra | phase-5 (Production) |

Cross-cutting features go in the phase of their deepest dependency.

**Feature slug:** derive from the spec filename (`specs/<slug>/spec.md` → `<slug>`).
**Atom ID prefix:** uppercase the slug, preserve hyphens. Example: `recurring-booking` → `ATOM-RECURRING-BOOKING-NNN`

Find next available atom number:
```bash
ls tasks/phase-N/atom-*.md | sort | tail -1
```

Announce before generating:
> "I'll create atoms under `tasks/phase-N/` with IDs `ATOM-<FEATURE>-NNN`. Proceed?"

---

## Step 3 — Decompose into atoms

Each atom must be:
- **Single-responsibility** — one layer, one concern per atom
- **Independently mergeable** — atom N+1 depends on N, never on N+2
- **100–300 lines of production code** — if more, split; if fewer than 90 non-trivial lines, merge

**Mandatory ordering within a feature:**
1. Flyway migration (new columns/tables) → `[MIGRATION]`
2. JPA entity + repository → tag by domain
3. Service layer → tag by domain
4. Controller + DTOs → tag by domain
5. Integration tests → `[TEST]`
6. Next.js server component / page → no tag
7. Next.js client form component → no tag

---

## Step 4 — Write each atom file

Follow `specs/_template/atom-template.md` exactly. Key rules:

**Header fields (all required):**
- Status: always `🟡 Planned` for new atoms
- Feature: the feature slug
- Phase: number + label (Foundation/Core/Kafka/Intelligence/Production)
- Tags: routing tags from `AGENTS.md`
- Complexity: Low/Medium/High based on layers touched
- Agent: the responsible agent from `AGENTS.md`
- Dependencies/Blocks: atom IDs or "None"
- PR: always "TBD"

**Interface Contracts (Technical Design):**
- Java: `record` shapes, `interface` method signatures — **no method bodies**
- TypeScript: type/interface definitions — **no implementations**
- SQL DDL (migrations): full DDL is allowed — it's a contract, not logic

**Test Strategy:**
- Use Given/Assert format for every named test case — no JUnit/Playwright code in this section
- Every `[CONCURRENCY]` atom must have a test case with ≥ 10 simultaneous threads
- Every `[KAFKA]` atom must have an idempotency test case (duplicate message delivery)

**Implementation Reference:**
- Full, production-ready implementation code goes here
- Tag every file with `// [TASK: ATOM-FEATURE-NNN]` as first comment
- Include every `import`, `@Annotation`, and method body
- Migration SQL: complete DDL including indexes and RLS if applicable

**Hard constraints every atom must enforce:**
```
□ JPA query without tenant_id? → BLOCK — fix before writing
□ Slots table being created? → BLOCK — use SlotCalculatorService
□ DTO as class instead of record? → BLOCK — Java 21 records only
□ Direct Kafka write (not outbox)? → BLOCK — use OutboxService
□ @PreAuthorize missing from controller? → BLOCK — add it
□ Industry-specific term in any identifier? → BLOCK — use domain abstraction
□ console.log / System.out.println? → BLOCK — use pino / SLF4J
```

**AC validation:**
Run each acceptance criterion through:
- Testable: can a specific test verify this?
- Unambiguous: only one interpretation?
- Measurable: specific count, status code, time bound?
- Independent: verifiable without checking other criteria?
- Traceable: references specific file, method, or endpoint?

Anti-patterns to reject:
- ❌ "Service works correctly" → ✅ "FooService.create() returns FooResponse with all fields populated"
- ❌ "Tenant isolation enforced" → ✅ "GET /tenants/{tenantA}/foos with tenantB JWT returns 403"
- ❌ "Handles errors" → ✅ "confirmBooking() with a held booking throws BookingConflictException(409)"

Append after the AC section:
```
<!-- AC validation passed: YYYY-MM-DD, N criteria rewritten, M marked TBD -->
```

---

## Step 5 — Update MASTER-TASK-LIST.md

Append new atoms to the correct phase table. Follow the existing table format.

---

## Step 6 — Output summary

```
✅ Atomized: specs/<slug>
   Feature ID prefix: ATOM-<FEATURE>
   Phase: N (<label>)
   Atoms created: <N>

   Files written:
   - tasks/phase-N/atom-NN-<name>.md  [ATOM-<FEATURE>-001]
   - tasks/phase-N/atom-NN-<name>.md  [ATOM-<FEATURE>-002]
   ...

   MASTER-TASK-LIST.md updated.
   
   Recommended next steps:
   1. /adr-check — verify no new ADR is needed
   2. Review Interface Contracts in each atom before implementation
   3. Run /implement-atom or hand to coder agent
```

Do NOT automatically begin implementation.
