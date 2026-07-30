-- Migration: V009__create_service_types
-- Author: migrations-agent
-- Task: P1-T04
-- Tables affected: service_types
-- Estimated rows affected: schema-only
-- Zero-downtime: YES
-- Rollback: U009__create_service_types.sql

CREATE TABLE service_types (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name              VARCHAR(255) NOT NULL,
    description       TEXT,
    duration_minutes  INT          NOT NULL CHECK (duration_minutes > 0),
    buffer_before_min INT          NOT NULL DEFAULT 0,
    buffer_after_min  INT          NOT NULL DEFAULT 0,
    allowed_resource_types TEXT[]  NOT NULL DEFAULT '{}', -- e.g., ['STAFF']
    intake_schema     JSONB        NOT NULL DEFAULT '{}', -- JSON Schema for custom form
    status            VARCHAR(50)  NOT NULL DEFAULT 'active',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_service_types_tenant ON service_types(tenant_id);
