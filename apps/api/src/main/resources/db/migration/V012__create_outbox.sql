-- TASK: ATOM-KAFKA-001 — Outbox table (ADR-003 transactional outbox pattern).
-- NOTE ON NUMBERING: docs/DATABASE-SCHEMA.md 2.11 reserves V011 for this
-- table, but V011 was already consumed by add_booking_confirmation_code
-- (Phase 2). DDL content is exactly per DATABASE-SCHEMA.md 2.11 — only the
-- version number is shifted (V011 -> V012).
-- Debezium CDC reads INSERTs on this table and relays them to Kafka; business
-- code writes here in the SAME ACID transaction as the booking mutation and
-- never produces to Kafka directly.
CREATE TABLE outbox (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL,
    aggregate_type  VARCHAR(100) NOT NULL,             -- e.g., 'Booking'
    aggregate_id    UUID         NOT NULL,             -- booking_id
    event_type      VARCHAR(100) NOT NULL,             -- e.g., 'BookingConfirmed'
    topic           VARCHAR(255) NOT NULL,             -- Kafka topic
    partition_key   VARCHAR(255) NOT NULL,             -- message key for Kafka
    payload         JSONB        NOT NULL,             -- Avro-serializable payload
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING', -- PENDING, PUBLISHED, FAILED
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox(created_at) WHERE status = 'PENDING';
CREATE INDEX idx_outbox_aggregate ON outbox(aggregate_id);
