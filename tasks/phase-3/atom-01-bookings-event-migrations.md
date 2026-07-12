---
description: Flyway migrations V010–V013 creating bookings, outbox, processed_events, and audit_log tables with RLS
---

# ATOM-KAFKA-001: Bookings and Event-Related Flyway Migrations

**Status**: 🟡 Planned
**Feature**: kafka-event-mesh
**Phase**: 3 (Kafka)
**Tags**: [MIGRATION]
**Complexity**: Medium
**Agent**: migrations
**Dependencies**: ATOM-PHASE2-009 (P2 foundation tables)
**Blocks**: ATOM-KAFKA-002, ATOM-KAFKA-006, ATOM-KAFKA-010
**PR**: TBD

---

## Overview

This atom adds the four Flyway migrations (V010–V013) that create the data-layer foundation for Phase 3: the `bookings` table (with its NFR-1.3 compound index), the transactional `outbox` table, the consumer deduplication `processed_events` table, and the HIPAA-grade `audit_log` table with append-only Row-Level Security. All four migrations must exist and pass before any Kafka or booking-engine code is merged. The audit log is locked down to the `audit_writer` role (INSERT only) from day one so RLS is never retrofitted onto live data.

---

## User Story

```
As a System
I want the bookings, outbox, processed_events, and audit_log tables to exist with correct indexes and RLS
So that the booking engine, outbox relay, consumer deduplication, and HIPAA audit trail can all function correctly
```

---

## Acceptance Criteria

- [ ] **AC-01**: `mvn flyway:migrate` completes cleanly from V009 to V013 with no errors
- [ ] **AC-02**: NFR-1.3 compound index `idx_bookings_tenant_location_start ON bookings(tenant_id, location_id, slot_start)` exists and is confirmed by `\d bookings` in psql
- [ ] **AC-03**: All 5 booking-related indexes (`idx_bookings_tenant_location_start`, `idx_bookings_resource_status`, `idx_bookings_user`, `idx_bookings_hold_expiry`) exist
- [ ] **AC-04**: `audit_writer` role can execute `INSERT INTO audit_log` — returns success
- [ ] **AC-05**: `audit_writer` role cannot execute `UPDATE audit_log` — returns `ERROR: permission denied`
- [ ] **AC-06**: `audit_writer` role cannot execute `DELETE FROM audit_log` — returns `ERROR: permission denied`
- [ ] **AC-07**: `mvn flyway:validate` passes after all migrations applied
- [ ] **AC-08 (Tenant isolation)**: `bookings` table has `tenant_id UUID NOT NULL` column; `audit_log` table has `tenant_id UUID NOT NULL` column
- [ ] **AC-09 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) in any column name or migration comment
- [ ] **AC-10**: Undo scripts `U010` through `U013` exist in `db/undo/`
- [ ] **AC-11**: Migrations agent dry-run result documented in `docs/memory/task-progress.md` before applying to any non-local environment

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `MigrationIT.java` — `shouldMigrateV009ToV013_successfully` | `db/migration/V010–V013` | 🔜 Planned |
| AC-02 | `MigrationIT.java` — `shouldHaveNFR13CompoundIndex` | `V010__create_bookings.sql` | 🔜 Planned |
| AC-03 | `MigrationIT.java` — `shouldHaveAllBookingIndexes` | `V010__create_bookings.sql` | 🔜 Planned |
| AC-04 | `AuditRlsIT.java` — `auditWriter_canInsert` | `V013__create_audit_log.sql` | 🔜 Planned |
| AC-05 | `AuditRlsIT.java` — `auditWriter_cannotUpdate` | `V013__create_audit_log.sql` | 🔜 Planned |
| AC-06 | `AuditRlsIT.java` — `auditWriter_cannotDelete` | `V013__create_audit_log.sql` | 🔜 Planned |
| AC-07 | `MigrationIT.java` — `shouldPassFlywayValidate` | `db/migration/` | 🔜 Planned |
| AC-08 | `MigrationIT.java` — `shouldHaveTenantIdColumns` | `V010, V013` | 🔜 Planned |
| AC-10 | Manual / CI check | `db/undo/U010–U013.sql` | 🔜 Planned |

<!-- AC validation passed: TBD, 11 criteria written, 9 mapped -->

---

## Technical Design

### Architecture

Four additive Flyway migrations applied in sequence. Each migration is idempotent and zero-downtime compatible (no column drops, no renames). The bookings table is the anchor entity for the entire booking engine. The outbox table is written in the same ACID transaction as any booking state mutation — never independently. The processed_events table is a shared deduplication store used by all Kafka consumers. The audit_log table is INSERT-only by RLS design: the `audit_writer` role is granted `INSERT` only; no `UPDATE` or `DELETE` grant exists at the PostgreSQL layer.

### Data Flow / Sequence

```
Flyway applies V010 → bookings table created (with NFR-1.3 compound index)
Flyway applies V011 → outbox table created (CDC source for Debezium)
Flyway applies V012 → processed_events table created (consumer dedup store)
Flyway applies V013 → audit_log table created + audit_writer role + RLS policy applied
```

### File Structure

```
apps/api/src/main/resources/db/migration/
├── V010__create_bookings.sql           ← bookings table + 4 indexes
├── V011__create_outbox.sql             ← outbox table + PENDING index
├── V012__create_processed_events.sql   ← dedup table + unique constraint
└── V013__create_audit_log.sql          ← audit_log + audit_writer role + RLS

apps/api/src/main/resources/db/undo/
├── U010__drop_bookings.sql
├── U011__drop_outbox.sql
├── U012__drop_processed_events.sql
└── U013__drop_audit_log.sql

apps/api/src/test/java/com/scheduler/
├── migration/MigrationIT.java          ← Flyway integration tests
└── audit/AuditRlsIT.java               ← RLS permission tests
```

### Interface Contracts

No Java service interfaces for this atom — pure SQL migration.

```sql
-- Key shapes verified by integration tests:
-- bookings(id UUID PK, tenant_id UUID NOT NULL, resource_id UUID NOT NULL,
--          service_type_id UUID NOT NULL, location_id UUID NOT NULL,
--          user_id UUID NOT NULL, status VARCHAR(20) CHECK(...),
--          slot_start TIMESTAMPTZ, slot_end TIMESTAMPTZ, ...)

-- outbox(id UUID PK, aggregate_type VARCHAR, aggregate_id UUID,
--        event_type VARCHAR, topic VARCHAR, partition_key VARCHAR,
--        payload JSONB, status VARCHAR DEFAULT 'PENDING', created_at TIMESTAMPTZ)

-- processed_events(id BIGSERIAL PK, consumer_group VARCHAR,
--                  message_key VARCHAR, processed_at TIMESTAMPTZ,
--                  UNIQUE(consumer_group, message_key))

-- audit_log(id BIGSERIAL PK, tenant_id UUID NOT NULL,
--           who UUID NOT NULL, what VARCHAR, when_ TIMESTAMPTZ,
--           booking_id UUID, resource_id UUID, ip_address INET, metadata JSONB)
```

### Design Rationale

- **ADR-003**: The outbox table (V011) is the physical realization of the transactional outbox pattern — Debezium reads from it via CDC, ensuring no dual-write failure between DB commit and Kafka publish.
- **ADR-004**: `tenant_id` on `bookings` and `audit_log` enforces row-level multi-tenancy; the discriminator column is present from migration day one.
- **NFR-1.3 compliance**: The compound B-tree index on `(tenant_id, location_id, slot_start)` is created in V010 before any slot-query code is allowed to merge — this is a hard prerequisite gate.
- **Append-only audit**: PostgreSQL RLS with `audit_writer` role (INSERT only) is the simplest, most reliable enforcement — it operates at the DB layer independent of application code, so no future code change can accidentally bypass it.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL)

```
- shouldMigrateV009ToV013_successfully:
    Given: clean PostgreSQL instance at V009
    Assert: flyway.migrate() returns SUCCESS; schema version = V013

- shouldHaveNFR13CompoundIndex:
    Given: migration applied
    Assert: pg_indexes contains row with indexname = 'idx_bookings_tenant_location_start'
            and indexdef includes '(tenant_id, location_id, slot_start)'

- shouldHaveAllBookingIndexes:
    Given: migration applied
    Assert: all 4 booking indexes exist in pg_indexes

- shouldPassFlywayValidate:
    Given: all migrations applied
    Assert: flyway.validate() throws no exception

- auditWriter_canInsert:
    Given: connected as audit_writer role
    Assert: INSERT INTO audit_log(...) succeeds with row count = 1

- auditWriter_cannotUpdate:
    Given: connected as audit_writer role; 1 existing audit_log row
    Assert: UPDATE audit_log SET what='changed' WHERE id=1 throws PSQLException with ERROR: permission denied

- auditWriter_cannotDelete:
    Given: connected as audit_writer role; 1 existing audit_log row
    Assert: DELETE FROM audit_log WHERE id=1 throws PSQLException with ERROR: permission denied

- shouldHaveTenantIdNotNull_onBookingsAndAuditLog:
    Given: migration applied
    Assert: information_schema.columns shows tenant_id is_nullable='NO' on bookings and audit_log
```

**Coverage requirements**:
- All 8 test cases must pass before any Kafka code is merged
- Idempotency test: `flyway.migrate()` called twice — second call is a no-op (no new versions applied)

---

## Implementation Constraints

- Every JPA query on `bookings` must include `tenant_id` in the WHERE clause
- `audit_writer` role: no UPDATE, no DELETE — ever; grant only INSERT and SEQUENCE usage
- `audit_log` RLS policy must use `FOR INSERT` not `FOR ALL` to ensure no accidental UPDATE/DELETE leakage
- Migration files must follow naming: `V{n}__{description}.sql` (double underscore)
- NFR-1.3 compound index must be created in V010 — not deferred to a later migration
- `outbox.id` must be `UUID PRIMARY KEY` (no serial/bigserial) — value set by application before insert
- Undo scripts must exist for all four migrations (`U010`–`U013`) before migration is considered complete
- No `console.log` in any test helper; use SLF4J

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/com/scheduler/migration/MigrationIT.java` with Testcontainers PostgreSQL setup
2. Write `shouldMigrateV009ToV013_successfully` — assert it fails (migrations don't exist yet)
3. Write `shouldHaveNFR13CompoundIndex` — assert it fails
4. Create `src/test/java/com/scheduler/audit/AuditRlsIT.java`
5. Write `auditWriter_cannotUpdate` and `auditWriter_cannotDelete` — assert they fail (no RLS yet)

### GREEN — Minimum code to pass

1. Create `V010__create_bookings.sql` with all columns, status CHECK, and all 4 indexes
2. Create `V011__create_outbox.sql` with PENDING index
3. Create `V012__create_processed_events.sql` with UNIQUE(consumer_group, message_key)
4. Create `V013__create_audit_log.sql` with `audit_writer` role, GRANT INSERT, RLS policy
5. Create undo scripts U010–U013

### REFACTOR — Quality pass

1. Add inline migration comments referencing task ID and NFR number
2. Verify zero-downtime compatibility: no NOT NULL columns without defaults on existing tables
3. Run `/migration-validate` command to confirm dry-run output
4. Document dry-run result in `docs/memory/task-progress.md`

---

## Implementation Reference

### Flyway Migration V010

**File**: `apps/api/src/main/resources/db/migration/V010__create_bookings.sql`

```sql
-- [TASK: ATOM-KAFKA-001] Bookings table
-- NFR-1.3: compound index must exist before any slot query code runs
CREATE TABLE bookings (
    id                  UUID        PRIMARY KEY,
    tenant_id           UUID        NOT NULL REFERENCES tenants(id),
    resource_id         UUID        NOT NULL REFERENCES resources(id),
    service_type_id     UUID        NOT NULL REFERENCES service_types(id),
    location_id         UUID        NOT NULL REFERENCES locations(id),
    user_id             UUID        NOT NULL REFERENCES users(id),
    status              VARCHAR(20)  NOT NULL
                        CHECK (status IN ('PENDING_HOLD','CONFIRMED','CANCELLED','EXPIRED')),
    slot_start          TIMESTAMPTZ  NOT NULL,
    slot_end            TIMESTAMPTZ  NOT NULL,
    buffer_start        TIMESTAMPTZ  NOT NULL,
    buffer_end          TIMESTAMPTZ  NOT NULL,
    hold_expires_at     TIMESTAMPTZ,
    confirmation_code   VARCHAR(50),
    extension           JSONB        NOT NULL DEFAULT '{}',
    cancelled_at        TIMESTAMPTZ,
    cancelled_by        UUID,
    cancellation_reason TEXT,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- NFR-1.3: Required compound index (must exist before any slot query code runs)
CREATE INDEX idx_bookings_tenant_location_start
    ON bookings(tenant_id, location_id, slot_start)
    WHERE status IN ('PENDING_HOLD', 'CONFIRMED');

CREATE INDEX idx_bookings_resource_status
    ON bookings(resource_id, status);

CREATE INDEX idx_bookings_user
    ON bookings(tenant_id, user_id);

CREATE INDEX idx_bookings_hold_expiry
    ON bookings(hold_expires_at)
    WHERE status = 'PENDING_HOLD';
```

### Flyway Migration V011

**File**: `apps/api/src/main/resources/db/migration/V011__create_outbox.sql`

```sql
-- [TASK: ATOM-KAFKA-001] Outbox table — ADR-003 transactional outbox pattern
CREATE TABLE outbox (
    id              UUID        PRIMARY KEY,
    aggregate_type  VARCHAR(100) NOT NULL,
    aggregate_id    UUID        NOT NULL,
    event_type      VARCHAR(100) NOT NULL,
    topic           VARCHAR(255) NOT NULL,
    partition_key   VARCHAR(255) NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','RELAYED','FAILED')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_outbox_status_created ON outbox(status, created_at)
    WHERE status = 'PENDING';
```

### Flyway Migration V012

**File**: `apps/api/src/main/resources/db/migration/V012__create_processed_events.sql`

```sql
-- [TASK: ATOM-KAFKA-001] Consumer deduplication table — NFR-2.1 idempotent consumers
CREATE TABLE processed_events (
    id              BIGSERIAL    PRIMARY KEY,
    consumer_group  VARCHAR(255) NOT NULL,
    message_key     VARCHAR(255) NOT NULL,
    processed_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (consumer_group, message_key)
);

CREATE INDEX idx_processed_events_lookup ON processed_events(consumer_group, message_key);
```

### Flyway Migration V013

**File**: `apps/api/src/main/resources/db/migration/V013__create_audit_log.sql`

```sql
-- [TASK: ATOM-KAFKA-001] Audit log table — HIPAA append-only, RLS enforced
CREATE TABLE audit_log (
    id          BIGSERIAL    PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    who         UUID         NOT NULL,   -- user_id
    what        VARCHAR(100) NOT NULL,   -- event_type
    when_       TIMESTAMPTZ  NOT NULL,
    booking_id  UUID,
    resource_id UUID,
    ip_address  INET,
    metadata    JSONB        NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_audit_tenant_when ON audit_log(tenant_id, when_);

-- Append-only enforcement via RLS
CREATE ROLE audit_writer;
GRANT INSERT ON audit_log TO audit_writer;
GRANT USAGE ON SEQUENCE audit_log_id_seq TO audit_writer;
-- No UPDATE or DELETE grant — intentional

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_insert_only ON audit_log FOR INSERT TO audit_writer WITH CHECK (true);
```

---

## Integration Points

**Depends on**: ATOM-PHASE2-009 (P2 migration — all Phase 2 tables must exist at V009)

**Enables**: ATOM-KAFKA-002 (OutboxEntity maps to `outbox`), ATOM-KAFKA-006 (BookingService writes to `bookings`), ATOM-KAFKA-008 (NotificationConsumer uses `processed_events`), ATOM-KAFKA-010 (AuditConsumer writes to `audit_log`)

**Cascading updates required**:
- `docs/DATABASE-SCHEMA.md` — add schema docs for V010–V013
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/resources/db/migration/V010__create_bookings.sql` | New | Bookings table + NFR-1.3 index |
| `apps/api/src/main/resources/db/migration/V011__create_outbox.sql` | New | Outbox table for ADR-003 |
| `apps/api/src/main/resources/db/migration/V012__create_processed_events.sql` | New | Consumer dedup table |
| `apps/api/src/main/resources/db/migration/V013__create_audit_log.sql` | New | Audit log + audit_writer role + RLS |
| `apps/api/src/main/resources/db/undo/U010__drop_bookings.sql` | New | Undo V010 |
| `apps/api/src/main/resources/db/undo/U011__drop_outbox.sql` | New | Undo V011 |
| `apps/api/src/main/resources/db/undo/U012__drop_processed_events.sql` | New | Undo V012 |
| `apps/api/src/main/resources/db/undo/U013__drop_audit_log.sql` | New | Undo V013 |
| `apps/api/src/test/java/com/scheduler/migration/MigrationIT.java` | New | Migration integration tests |
| `apps/api/src/test/java/com/scheduler/audit/AuditRlsIT.java` | New | RLS permission tests |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] Zero JPA queries without `tenant_id` in WHERE clause
- [ ] Zero industry-specific terms in any identifier or migration comment
- [ ] Flyway migration exists for all schema changes
- [ ] NFR-1.3 compound index present in V010 before any slot-query code is merged
- [ ] `audit_writer` RLS verified: INSERT passes, UPDATE and DELETE both return permission denied
- [ ] Undo scripts U010–U013 exist and tested
- [ ] Migrations agent dry-run documented
- [ ] ADR-003 referenced in V011 migration comment
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: kafka-event-mesh | Phase: 3*
