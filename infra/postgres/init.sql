-- Local dev bootstrap for the scheduler database (ATOM-LOCAL-DEV-003).
-- Runs once on first container start via /docker-entrypoint-initdb.d.

-- Role used by audit-service: INSERT-only access is granted per-table by
-- later migrations (audit_log is append-only per SECURITY-SPEC 5.2).
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'audit_writer') THEN
        CREATE ROLE audit_writer;
    END IF;
END
$$;

-- Extensions required by gen_random_uuid() and uuid helpers.
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- TASK: ATOM-KAFKA-003 — Debezium logical replication prerequisites.
-- wal_level is also forced via the postgres container command flags in
-- infra/docker-compose.yml (ALTER SYSTEM alone only applies after restart).
ALTER SYSTEM SET wal_level = 'logical';
ALTER SYSTEM SET max_wal_senders = 4;
ALTER SYSTEM SET max_replication_slots = 4;

-- TASK: ATOM-KAFKA-009 — local-dev login for the audit-service datasource.
-- Production credentials are provisioned by the secrets manager, never here.
ALTER ROLE audit_writer WITH LOGIN PASSWORD 'audit_writer_dev';
