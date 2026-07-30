-- Migration: V004__create_locations
-- Author: migrations-agent
-- Task: P1-T04
-- Tables affected: locations
-- Estimated rows affected: schema-only
-- Zero-downtime: YES
-- Rollback: U004__create_locations.sql

CREATE TABLE locations (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name          VARCHAR(255) NOT NULL,
    address_line1 VARCHAR(255) NOT NULL,
    address_line2 VARCHAR(255),
    city          VARCHAR(100) NOT NULL,
    state         VARCHAR(100),
    postal_code   VARCHAR(20)  NOT NULL,
    country_code  CHAR(2)      NOT NULL DEFAULT 'US',
    latitude      DECIMAL(9,6),
    longitude     DECIMAL(9,6),
    timezone      VARCHAR(50)  NOT NULL DEFAULT 'UTC', -- IANA timezone name
    status        VARCHAR(50)  NOT NULL DEFAULT 'active',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_locations_tenant_id ON locations(tenant_id);
