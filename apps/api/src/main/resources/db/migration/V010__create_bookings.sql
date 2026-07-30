-- V010__create_bookings.sql
-- ATOM-BOOKING-009 — core bookings table, exactly per docs/DATABASE-SCHEMA.md 2.10
CREATE TABLE bookings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants(id),
    location_id     UUID         NOT NULL REFERENCES locations(id),
    resource_id     UUID         NOT NULL REFERENCES resources(id),
    service_type_id UUID         NOT NULL REFERENCES service_types(id),
    user_id         UUID         NOT NULL REFERENCES users(id),
    status          VARCHAR(30)  NOT NULL DEFAULT 'PENDING_HOLD',
                                         -- PENDING_HOLD, CONFIRMED, CANCELLED, COMPLETED, NO_SHOW
    slot_start      TIMESTAMPTZ  NOT NULL,
    slot_end        TIMESTAMPTZ  NOT NULL,
    buffer_start    TIMESTAMPTZ  NOT NULL, -- slot_start - buffer_before
    buffer_end      TIMESTAMPTZ  NOT NULL, -- slot_end + buffer_after
    hold_expires_at TIMESTAMPTZ,           -- null after confirmation
    cancelled_at    TIMESTAMPTZ,
    cancelled_by    UUID,
    cancellation_reason TEXT,
    extension       JSONB        NOT NULL DEFAULT '{}', -- tenant intake form responses
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CHECK (slot_end > slot_start)
);

-- PRIMARY performance index (NFR-1.3 mandate)
CREATE INDEX idx_bookings_tenant_location_start
    ON bookings(tenant_id, location_id, slot_start)
    WHERE status IN ('PENDING_HOLD', 'CONFIRMED');

-- Slot overlap detection index
CREATE INDEX idx_bookings_resource_slot
    ON bookings(resource_id, buffer_start, buffer_end)
    WHERE status IN ('PENDING_HOLD', 'CONFIRMED');

-- GC scheduler index
CREATE INDEX idx_bookings_hold_expiry
    ON bookings(hold_expires_at)
    WHERE status = 'PENDING_HOLD';

-- Tenant + status query
CREATE INDEX idx_bookings_tenant_status
    ON bookings(tenant_id, status, created_at DESC);

-- JSONB extension search
CREATE INDEX idx_bookings_extension
    ON bookings USING GIN(extension);
