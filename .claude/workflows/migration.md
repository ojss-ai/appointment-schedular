# Workflow: migration

1. Spec — migrations agent drafts V{n}__{description}.sql from the atom file.
2. Dry-run — apply against a scratch Testcontainers PostgreSQL 15; capture output in docs/memory/task-progress.md.
3. Undo — write matching U{n} script (pure DROP/ALTER reversal, no data logic).
4. Validate — /migration-validate: naming, header comment, tenant_id NOT NULL, no slots table, index placement.
5. Human gate — orchestrator never applies a [MIGRATION] task without explicit confirmation.
6. Apply — mvn flyway:migrate, then mvn flyway:validate.
