-- Migration: V005__create_branch_holidays
-- Author: migrations-agent
-- Task: P1-T04
-- Tables affected: branch_holidays
-- Estimated rows affected: schema-only
-- Zero-downtime: YES
-- Rollback: U005__create_branch_holidays.sql

CREATE TABLE branch_holidays (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants(id),
    location_id   UUID         NOT NULL REFERENCES locations(id) ON DELETE CASCADE,
    holiday_date  DATE         NOT NULL,
    name          VARCHAR(255),
    is_recurring  BOOLEAN      NOT NULL DEFAULT false, -- recurring annually
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (location_id, holiday_date)
);
CREATE INDEX idx_holidays_location_date ON branch_holidays(location_id, holiday_date);
