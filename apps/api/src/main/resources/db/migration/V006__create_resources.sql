-- Migration: V006__create_resources
-- Author: migrations-agent
-- Task: P1-T04
-- Tables affected: resources
-- Estimated rows affected: schema-only
-- Zero-downtime: YES
-- Rollback: U006__create_resources.sql

CREATE TABLE resources (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    location_id   UUID         NOT NULL REFERENCES locations(id),
    name          VARCHAR(255) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,               -- e.g., 'STAFF', 'ROOM', 'EQUIPMENT'
    status        VARCHAR(50)  NOT NULL DEFAULT 'active',
    extension     JSONB        NOT NULL DEFAULT '{}',  -- tenant domain metadata
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_resources_tenant_location ON resources(tenant_id, location_id);
CREATE INDEX idx_resources_extension       ON resources USING GIN(extension);
