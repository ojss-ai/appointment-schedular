-- Migration: V007__create_resource_schedules
-- Author: migrations-agent
-- Task: P1-T04
-- Tables affected: resource_schedules
-- Estimated rows affected: schema-only
-- Zero-downtime: YES
-- Rollback: U007__create_resource_schedules.sql

CREATE TABLE resource_schedules (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants(id),
    resource_id   UUID         NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    day_of_week   SMALLINT     NOT NULL CHECK (day_of_week BETWEEN 0 AND 6), -- 0=Sun, 6=Sat
    start_time    TIME         NOT NULL,
    end_time      TIME         NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT true,
    effective_from DATE        NOT NULL DEFAULT CURRENT_DATE,
    effective_to  DATE,
    CHECK (end_time > start_time)
);
CREATE INDEX idx_schedules_resource ON resource_schedules(resource_id, day_of_week);
