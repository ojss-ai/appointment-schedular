-- TASK: ATOM-ANALYTICS-001 — Index supporting the nightly booking pattern
-- aggregation query (AC-06: < 5s over 30 days of audit data).
-- Partial index: the ingestion job only ever scans BookingConfirmed rows.
-- Plain CREATE INDEX (not CONCURRENTLY): Flyway runs migrations inside a
-- transaction and CONCURRENTLY is not allowed there; audit_log volume is
-- low at this phase (pre-production), so a blocking build is acceptable.
CREATE INDEX IF NOT EXISTS idx_audit_log_booking_patterns
    ON audit_log (tenant_id, event_type, occurred_at)
    WHERE event_type = 'BookingConfirmed';
