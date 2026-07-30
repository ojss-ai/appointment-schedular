-- Migration: V002__create_users
-- Author: migrations-agent
-- Task: P1-T04
-- Tables affected: users
-- Estimated rows affected: schema-only
-- Zero-downtime: YES
-- Rollback: U002__create_users.sql

CREATE TABLE users (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    identifier    VARCHAR(320) NOT NULL,               -- email or E.164 phone
    identifier_type VARCHAR(10) NOT NULL,              -- 'EMAIL' or 'PHONE'
    role          VARCHAR(50)  NOT NULL DEFAULT 'customer', -- 'customer', 'admin', 'super_admin'
    status        VARCHAR(50)  NOT NULL DEFAULT 'active',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, identifier)
);
CREATE INDEX idx_users_tenant_id ON users(tenant_id);
CREATE INDEX idx_users_identifier ON users(identifier);
