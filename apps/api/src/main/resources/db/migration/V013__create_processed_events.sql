-- TASK: ATOM-KAFKA-001 — Consumer idempotency/dedup table (NFR-2.1).
-- NOTE ON NUMBERING: DATABASE-SCHEMA.md 2.12 lists this as V012; renumbered
-- to V013 because V011 was taken by add_booking_confirmation_code. DDL is
-- exactly per DATABASE-SCHEMA.md 2.12.
-- Every Kafka consumer checks (consumer_group, message_key) BEFORE acting;
-- the UNIQUE constraint is the last line of defence against races.
CREATE TABLE processed_events (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    consumer_group  VARCHAR(100) NOT NULL,
    message_key     VARCHAR(255) NOT NULL,
    topic           VARCHAR(255) NOT NULL,
    partition       INT          NOT NULL,
    offset_value    BIGINT       NOT NULL,
    processed_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (consumer_group, message_key)
);
CREATE INDEX idx_processed_events_key ON processed_events(consumer_group, message_key);
