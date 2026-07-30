-- TASK: ATOM-KAFKA-001 — Append-only HIPAA audit ledger + audit_writer role.
-- NOTE ON NUMBERING: DATABASE-SCHEMA.md 2.13 lists this as V013; renumbered
-- to V014 because V011 was taken by add_booking_confirmation_code. Table DDL
-- is exactly per DATABASE-SCHEMA.md 2.13; role/grant/RLS statements per
-- SECURITY-SPEC 5.2 and PHASE-3-TASKS P3-T01.
-- Rows are NEVER updated or deleted (HIPAA requirement).
CREATE TABLE audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL,
    booking_id      UUID         NOT NULL,
    resource_id     UUID,
    user_id         UUID         NOT NULL,             -- who performed the action
    event_type      VARCHAR(100) NOT NULL,
    previous_status VARCHAR(30),
    new_status      VARCHAR(30),
    ip_address      INET,
    user_agent      TEXT,
    metadata        JSONB        NOT NULL DEFAULT '{}',
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_log_tenant_booking ON audit_log(tenant_id, booking_id);
CREATE INDEX idx_audit_log_tenant_date    ON audit_log(tenant_id, occurred_at DESC);

-- audit_writer role: INSERT-only on audit_log — no UPDATE or DELETE grant,
-- ever. Created here with a guard because Testcontainers databases do not run
-- infra/postgres/init.sql (which creates the role for local dev with LOGIN).
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'audit_writer') THEN
        CREATE ROLE audit_writer;
    END IF;
END
$$;

GRANT INSERT ON audit_log TO audit_writer;
-- audit-service also owns the consumer-side dedup check/insert (NFR-2.1).
GRANT SELECT, INSERT ON processed_events TO audit_writer;

-- Row-level security: INSERT-only policy (FOR INSERT, not FOR ALL — no
-- accidental UPDATE/DELETE leakage). Missing UPDATE/DELETE grants plus RLS
-- make append-only a DB-layer guarantee independent of application code.
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_insert_only ON audit_log FOR INSERT TO audit_writer WITH CHECK (true);
