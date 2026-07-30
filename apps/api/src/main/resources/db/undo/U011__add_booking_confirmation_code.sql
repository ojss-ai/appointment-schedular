-- Undo for V011__add_booking_confirmation_code (manual rollback only)
DROP INDEX IF EXISTS idx_bookings_tenant_confirmation_code;
ALTER TABLE bookings DROP COLUMN IF EXISTS confirmation_code;
