-- Migration: V001__create_tenants
-- Author: migrations-agent
-- Task: P1-T04
-- Tables affected: tenants
-- Estimated rows affected: schema-only
-- Zero-downtime: YES
-- Rollback: U001__create_tenants.sql

CREATE TABLE tenants (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(255) NOT NULL,
    slug          VARCHAR(100) NOT NULL UNIQUE,       -- URL-safe identifier
    plan          VARCHAR(50)  NOT NULL DEFAULT 'standard',
    status        VARCHAR(50)  NOT NULL DEFAULT 'active',
    settings      JSONB        NOT NULL DEFAULT '{}', -- tenant-level config
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_tenants_slug ON tenants(slug);
