# Database Schema Specification
## Multi-Tenant Omni-Industry Scheduling Framework

**Version:** 1.0.0
**Migration tool:** Flyway
**Database:** PostgreSQL 15+

---

## 1. Entity Relationship Overview

```
tenants ──< locations ──< resources ──< bookings
   │              │             │
   │              └──< holidays │
   │                            └── extension (JSONB)
   └──< users
         └── role_assignments

service_types (tenant-scoped)
     └── booking.service_type_id → service_types

outbox (event staging)
audit_log (append-only)
otp_records (auth)
processed_events (idempotency)
```

---

## 2. Core Tables — DDL Specification

### 2.1 `tenants`
```sql
-- V001__create_tenants.sql
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
```

---

### 2.2 `users`
```sql
-- V002__create_users.sql
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
```

---

### 2.3 `otp_records`
```sql
-- V003__create_otp_records.sql
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
```

---

### 2.4 `locations`
```sql
-- V004__create_locations.sql
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
```

---

### 2.5 `branch_holidays`
```sql
-- V005__create_branch_holidays.sql
CREATE TABLE branch_holidays (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants(id),
    location_id   UUID         NOT NULL REFERENCES locations(id) ON DELETE CASCADE,
    holiday_date  DATE         NOT NULL,
    name          VARCHAR(255),
    is_recurring  BOOLEAN      NOT NULL DEFAULT false, -- recurring annually
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (location_id, holiday_date)
);
CREATE INDEX idx_holidays_location_date ON branch_holidays(location_id, holiday_date);
```

---

### 2.6 `resources`
```sql
-- V006__create_resources.sql
CREATE TABLE resources (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    location_id   UUID         NOT NULL REFERENCES locations(id),
    name          VARCHAR(255) NOT NULL,
    resource_type VARCHAR(100) NOT NULL,               -- e.g., 'STAFF', 'ROOM', 'EQUIPMENT'
    status        VARCHAR(50)  NOT NULL DEFAULT 'active',
    extension     JSONB        NOT NULL DEFAULT '{}',  -- tenant domain metadata
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_resources_tenant_location ON resources(tenant_id, location_id);
CREATE INDEX idx_resources_extension       ON resources USING GIN(extension);
```

---

### 2.7 `resource_schedules`
```sql
-- V007__create_resource_schedules.sql
CREATE TABLE resource_schedules (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants(id),
    resource_id   UUID         NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    day_of_week   SMALLINT     NOT NULL CHECK (day_of_week BETWEEN 0 AND 6), -- 0=Sun, 6=Sat
    start_time    TIME         NOT NULL,
    end_time      TIME         NOT NULL,
    is_active     BOOLEAN      NOT NULL DEFAULT true,
    effective_from DATE        NOT NULL DEFAULT CURRENT_DATE,
    effective_to  DATE,
    CHECK (end_time > start_time)
);
CREATE INDEX idx_schedules_resource ON resource_schedules(resource_id, day_of_week);
```

---

### 2.8 `resource_breaks`
```sql
-- V008__create_resource_breaks.sql
CREATE TABLE resource_breaks (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants(id),
    resource_id   UUID         NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    day_of_week   SMALLINT     NOT NULL CHECK (day_of_week BETWEEN 0 AND 6),
    break_start   TIME         NOT NULL,
    break_end     TIME         NOT NULL,
    label         VARCHAR(100),                        -- e.g., 'Lunch', 'Prayer break'
    CHECK (break_end > break_start)
);
CREATE INDEX idx_breaks_resource ON resource_breaks(resource_id, day_of_week);
```

---

### 2.9 `service_types`
```sql
-- V009__create_service_types.sql
CREATE TABLE service_types (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name              VARCHAR(255) NOT NULL,
    description       TEXT,
    duration_minutes  INT          NOT NULL CHECK (duration_minutes > 0),
    buffer_before_min INT          NOT NULL DEFAULT 0,
    buffer_after_min  INT          NOT NULL DEFAULT 0,
    allowed_resource_types TEXT[]  NOT NULL DEFAULT '{}', -- e.g., ['STAFF']
    intake_schema     JSONB        NOT NULL DEFAULT '{}', -- JSON Schema for custom form
    status            VARCHAR(50)  NOT NULL DEFAULT 'active',
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_service_types_tenant ON service_types(tenant_id);
```

---

### 2.10 `bookings` (core table)
```sql
-- V010__create_bookings.sql
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
```

---

### 2.11 `outbox`
> **Implemented as `V012__create_outbox.sql`** — V011 was consumed by
> `add_booking_confirmation_code` in Phase 2; DDL below is unchanged.
```sql
-- V011__create_outbox.sql (actual file: V012)
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
```

---

### 2.12 `processed_events`
> **Implemented as `V013__create_processed_events.sql`** (renumbered, see 2.11).
```sql
-- V012__create_processed_events.sql (actual file: V013)
-- Idempotency table for Kafka consumers (NFR-2.1)
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
```

---

### 2.13 `audit_log`
> **Implemented as `V014__create_audit_log.sql`** (renumbered, see 2.11).
```sql
-- V013__create_audit_log.sql (actual file: V014)
-- Append-only; rows are NEVER updated or deleted (HIPAA requirement)
CREATE TABLE audit_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL,
    booking_id      UUID         NOT NULL,
    resource_id     UUID,
    user_id         UUID         NOT NULL,             -- who performed the action
    event_type      VARCHAR(100) NOT NULL,
    previous_status VARCHAR(30),
    new_status      VARCHAR(30),
    ip_address      INET,
    user_agent      TEXT,
    metadata        JSONB        NOT NULL DEFAULT '{}',
    occurred_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_log_tenant_booking ON audit_log(tenant_id, booking_id);
CREATE INDEX idx_audit_log_tenant_date    ON audit_log(tenant_id, occurred_at DESC);
-- Row-level security: audit_service role can INSERT only, never UPDATE/DELETE
ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
```

---

## 3. Index Strategy Summary

| Index Name | Table | Columns | Type | Purpose |
|---|---|---|---|---|
| `idx_bookings_tenant_location_start` | bookings | tenant_id, location_id, slot_start | B-tree | Slot availability queries (NFR-1.3) |
| `idx_bookings_resource_slot` | bookings | resource_id, buffer_start, buffer_end | B-tree | Overlap detection during booking |
| `idx_bookings_hold_expiry` | bookings | hold_expires_at | B-tree (partial) | GC scheduler scan |
| `idx_outbox_pending` | outbox | created_at | B-tree (partial) | Debezium CDC polling |
| `idx_processed_events_key` | processed_events | consumer_group, message_key | B-tree | Idempotency check |
| `idx_resources_extension` | resources | extension | GIN | JSONB metadata search |
| `idx_bookings_extension` | bookings | extension | GIN | Tenant intake form search |

---

## 4. Flyway Migration Naming Convention

```
V{NNN}__{description}.sql         ← forward migration
U{NNN}__{description}.sql         ← undo migration (manual, for emergencies)
R__{description}.sql              ← repeatable migration (views, functions)
```

All migrations must be idempotent where possible. Column additions must be nullable or carry a default. Index additions placed in a separate migration step from table changes.
