# /migration-validate

Dry-run all pending Flyway migrations and validate safety.

## Steps

1. **Find pending migrations**
   - List files in `apps/api/src/main/resources/db/migration/` sorted by version number.
   - Compare against `flyway_schema_history` table (if DB is accessible) to find unapplied migrations.

2. **Static analysis on each pending migration**
   - Check for `DROP TABLE` or `DROP COLUMN` — flag as DANGEROUS if found.
   - Check for `NOT NULL` column additions without a `DEFAULT` — flag as RISKY.
   - Check for `ALTER TABLE` without a corresponding index migration step.
   - Verify the migration header comment is present (author, task ID, tables affected, zero-downtime flag).

3. **Rollback file check**
   - For each `V{n}__*.sql`, verify `U{n}__*.sql` exists in `apps/api/src/main/resources/db/undo/`.

4. **Row count estimation**
   - If a migration targets a table, estimate row count from existing data or schema hints.
   - If > 1M rows estimated, print a WARNING and require explicit confirmation before proceeding.

5. **Output**
   - Print: `Migration validate complete — {N} migrations checked, {N} issues found`
   - List each issue with severity: DANGEROUS / RISKY / WARNING / OK
