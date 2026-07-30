# Workflow: Apply a Database Migration
<!-- ATOM-ORCHESTRATION-002 — 6-step migration workflow. The
     [human approval required] gate at step 3 is a non-negotiable safety
     control: the orchestrator MUST pause and await explicit user
     confirmation before any flyway:migrate step. -->

## Preconditions
- [ ] Flyway migration SQL file written and reviewed by migrations agent
- [ ] Undo/rollback script exists alongside the migration file (db/undo/U{n}__*.sql)
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
