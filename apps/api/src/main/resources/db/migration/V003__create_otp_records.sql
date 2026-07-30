-- Migration: V003__create_otp_records
-- Author: migrations-agent
-- Task: P1-T04
-- Tables affected: otp_records
-- Estimated rows affected: schema-only
-- Zero-downtime: YES
-- Rollback: U003__create_otp_records.sql

CREATE TABLE otp_records (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants(id),
    identifier    VARCHAR(320) NOT NULL,
    otp_hash      VARCHAR(255) NOT NULL,               -- bcrypt hash of OTP
    channel       VARCHAR(10)  NOT NULL,               -- 'EMAIL' or 'SMS'
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING, USED, EXPIRED
    attempt_count INT          NOT NULL DEFAULT 0,
    expires_at    TIMESTAMPTZ  NOT NULL,               -- created_at + 5 minutes
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_otp_identifier ON otp_records(identifier, status, expires_at);
CREATE INDEX idx_otp_tenant_id  ON otp_records(tenant_id);
