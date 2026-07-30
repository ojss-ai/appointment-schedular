# Workflow: Implement a New API Endpoint
<!-- ATOM-ORCHESTRATION-002 — 9-step endpoint implementation workflow.
     Step format: {n}. **[{agent-name}]** {description} — agent names must
     match .claude/agents/ filenames. -->

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
