-- V011__add_booking_confirmation_code.sql
-- ATOM-BOOKING-010 — confirmation code {TENANT_PREFIX}-{YYYY}-{5-digit-seq},
-- unique per tenant. Nullable: only CONFIRMED bookings carry a code.
ALTER TABLE bookings
    ADD COLUMN confirmation_code VARCHAR(50);

CREATE UNIQUE INDEX idx_bookings_tenant_confirmation_code
    ON bookings(tenant_id, confirmation_code)
    WHERE confirmation_code IS NOT NULL;
