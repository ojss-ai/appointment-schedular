-- TASK: ATOM-KAFKA-001 — undo V014 (emergency use only)
-- NOTE: the audit_writer role is intentionally left in place (shared with
-- infra/postgres/init.sql); only table-scoped objects are dropped.
DROP POLICY IF EXISTS audit_insert_only ON audit_log;
DROP TABLE IF EXISTS audit_log;
