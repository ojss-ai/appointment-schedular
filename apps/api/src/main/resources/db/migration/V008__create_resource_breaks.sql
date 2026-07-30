-- Migration: V008__create_resource_breaks
-- Author: migrations-agent
-- Task: P1-T04
-- Tables affected: resource_breaks
-- Estimated rows affected: schema-only
-- Zero-downtime: YES
-- Rollback: U008__create_resource_breaks.sql

CREATE TABLE resource_breaks (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants(id),
    resource_id   UUID         NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    day_of_week   SMALLINT     NOT NULL CHECK (day_of_week BETWEEN 0 AND 6),
    break_start   TIME         NOT NULL,
    break_end     TIME         NOT NULL,
    label         VARCHAR(100),                        -- e.g., 'Lunch', 'Quiet hour'
    CHECK (break_end > break_start)
);
CREATE INDEX idx_breaks_resource ON resource_breaks(resource_id, day_of_week);
