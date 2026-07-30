# ATOM-FLYWAY-MIGRATIONS-004: PostgreSQL Baseline Schema Migrations V001–V009

**Status**: ✅ Complete
**Feature**: flyway-migrations
**Phase**: 1 (Foundation)
**Tags**: [MIGRATION]
**Complexity**: Medium
**Agent**: migrations
**Dependencies**: ATOM-LOCAL-DEV-003
**Blocks**: None
**PR**: TBD

---

## Overview

This atom creates the 9 baseline Flyway migration files that define the complete Phase 1 database schema, plus matching undo scripts. Tables cover tenants, users, OTP records, locations, branch holidays, resources, resource schedules, resource breaks, and service types. Every table carries `tenant_id UUID NOT NULL` and the required indexes. The key design decision is that all entities use generic domain names (`Resource`, `Service`, `Location`) with no industry-specific column names, enforcing ADR-005 from the first migration.

---

## User Story

```
As a System
I want a versioned, tenant-isolated baseline database schema
So that all application services have a consistent, rollback-safe starting point
```

---

## Acceptance Criteria

- [ ] **AC-01**: Migrations agent dry-run completed and documented in `docs/memory/task-progress.md`
- [ ] **AC-02**: `mvn flyway:migrate` runs cleanly from empty DB to V009 with exit code 0
- [ ] **AC-03**: All 9 tables exist in the `scheduler` database (verified with `\dt`)
- [ ] **AC-04**: All specified indexes exist (verified with `\di`)
- [ ] **AC-05**: `mvn flyway:validate` passes with no checksum mismatches
- [ ] **AC-06**: `flyway_schema_history` shows all 9 migrations with status `SUCCESS`
- [ ] **AC-07**: All 9 undo scripts exist in `apps/api/src/main/resources/db/undo/`
- [ ] **AC-08 (Tenant isolation)**: Every table has `tenant_id UUID NOT NULL` with a FK to `tenants(id)` — zero exceptions
- [ ] **AC-09 (Domain abstraction)**: No column in any table contains industry-specific terms (`doctor`, `patient`, `vehicle`, `mechanic`, `appointment` as a column name, etc.)

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `docs/memory/task-progress.md` | Migration agent output | 🔜 Planned |
| AC-02 | CI `mvn flyway:migrate` | `db/migration/V001–V009` | 🔜 Planned |
| AC-03 | `psql \dt` | All migration files | 🔜 Planned |
| AC-04 | `psql \di` | All migration files | 🔜 Planned |
| AC-05 | CI `mvn flyway:validate` | Migration checksums | 🔜 Planned |
| AC-06 | `flyway_schema_history` query | Flyway internals | 🔜 Planned |
| AC-07 | File existence check | `db/undo/` | 🔜 Planned |
| AC-08 | SQL grep for `tenant_id` | All migration files | 🔜 Planned |
| AC-09 | SQL grep for forbidden terms | All migration files | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 9 criteria rewritten, 9 marked TBD -->

---

## Technical Design

### Architecture

All 9 migrations are pure SQL files consumed by Flyway at application startup (`spring.flyway.enabled: true`). The migrations agent runs a dry-run pass before writing any files. Each file follows a required header comment format. Undo scripts live in a separate `db/undo/` directory and are not run automatically — they are manual rollback artifacts. No JPA entity code is introduced in this atom; that work happens in the service-layer atoms.

### File Structure

```
apps/api/src/main/resources/
├── db/
│   ├── migration/
│   │   ├── V001__create_tenants.sql
│   │   ├── V002__create_users.sql
│   │   ├── V003__create_otp_records.sql
│   │   ├── V004__create_locations.sql
│   │   ├── V005__create_branch_holidays.sql
│   │   ├── V006__create_resources.sql
│   │   ├── V007__create_resource_schedules.sql
│   │   ├── V008__create_resource_breaks.sql
│   │   └── V009__create_service_types.sql
│   └── undo/
│       ├── U009__create_service_types.sql
│       ├── U008__create_resource_breaks.sql
│       ├── U007__create_resource_schedules.sql
│       ├── U006__create_resources.sql
│       ├── U005__create_branch_holidays.sql
│       ├── U004__create_locations.sql
│       ├── U003__create_otp_records.sql
│       ├── U002__create_users.sql
│       └── U001__create_tenants.sql
```

### Interface Contracts

No runtime interfaces defined in this atom. This atom produces SQL migration files only.

### Design Rationale

- **ADR-004**: Row-level multi-tenancy via `tenant_id` discriminator — every table implements this from V001 onward; the `tenants` table itself is the anchor.
- **ADR-005**: Generic domain model (`Resource`, `Service`, `Location`) with JSONB `extension` — the `resources` and `service_types` tables carry `extension JSONB` and `intake_schema JSONB` respectively to allow tenant-specific domain data.
- **ADR-001**: No `slots` table is created — availability is computed on demand by `SlotCalculatorService` in a later atom.
- The compound B-tree index `(tenant_id, location_id, start_time)` required by NFR-1.3 is not yet present (no `bookings` table in this atom) — it will be added in the Phase 2 bookings migration.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL)

```
- shouldMigrateCleanDatabase_toV009:
    Given: empty PostgreSQL 15 container
    Assert: `flyway:migrate` exits 0; `flyway_schema_history` has 9 rows all with status SUCCESS

- shouldPassFlywayValidation_afterMigration:
    Given: V001–V009 applied
    Assert: `flyway:validate` exits 0 with no checksum mismatch errors

- shouldEnforceTenantId_onAllTables:
    Given: V001–V009 applied
    Assert: SQL query on `information_schema.columns` confirms `tenant_id` present and NOT NULL on all 8 non-tenants tables

- shouldRejectInsertion_withoutTenantId:
    Given: V002 applied (users table)
    Assert: INSERT into `users` without `tenant_id` raises NOT NULL constraint violation

- shouldRollback_withUndoScripts:
    Given: V001–V009 applied
    Assert: running undo scripts U009 down to U001 leaves no application tables in the database
```

**Coverage requirements**:
- Migration files produce no Java code — coverage target N/A
- Schema integrity tests must pass in CI before any service-layer atom proceeds

---

## Implementation Constraints

- Every table must include `tenant_id UUID NOT NULL REFERENCES tenants(id)` (except `tenants` itself)
- Migration files must be named exactly `V{NNN}__{description}.sql` (three-digit version number)
- Every migration file must begin with the required header comment block
- No `slots` table — availability is computed on demand (ADR-001)
- No industry-specific column names in any table
- `extension` JSONB column read/write only for tenant-injected metadata; core logic must never read it
- Undo scripts must be pure `DROP TABLE IF EXISTS` — no data migration logic
- All migrations must be zero-downtime (schema-only, no table locks beyond `CREATE TABLE`)

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/.../migration/BaselineMigrationIT.java` with Testcontainers PostgreSQL
2. Write `shouldMigrateCleanDatabase_toV009` — assert it fails (no migration files exist)
3. Write `shouldEnforceTenantId_onAllTables` — assert it fails

### GREEN — Minimum code to pass

1. Write `V001__create_tenants.sql`
2. Write `V002__create_users.sql` through `V009__create_service_types.sql` in order
3. Write all 9 undo scripts in `db/undo/`
4. Run `mvn flyway:migrate` against local Postgres — verify all 9 applied
5. Run integration tests — all pass

### REFACTOR — Quality pass

1. Run `/migration-validate` slash command to confirm checksums
2. Verify no forbidden column names with grep: `grep -r "doctor\|patient\|vehicle\|mechanic" db/migration/`
3. Document migration in `docs/memory/domain-model.md`

---

## Implementation Reference

### Migration file header (required on every file)

```sql
-- Migration: V{n}__{description}
-- Author: migrations-agent
-- Task: P1-T04
-- Tables affected: {table name}
-- Estimated rows affected: schema-only
-- Zero-downtime: YES
-- Rollback: U{n}__{description}.sql
```

### V001__create_tenants.sql

**File**: `apps/api/src/main/resources/db/migration/V001__create_tenants.sql`

```sql
CREATE TABLE tenants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) NOT NULL UNIQUE,
    plan            VARCHAR(50)  NOT NULL DEFAULT 'starter',
    status          VARCHAR(20)  NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','suspended','cancelled')),
    settings        JSONB        NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenants_slug ON tenants(slug);
```

### V002__create_users.sql

**File**: `apps/api/src/main/resources/db/migration/V002__create_users.sql`

```sql
CREATE TABLE users (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    identifier      VARCHAR(255) NOT NULL,
    identifier_type VARCHAR(20)  NOT NULL CHECK (identifier_type IN ('email','phone')),
    role            VARCHAR(20)  NOT NULL DEFAULT 'customer'
                    CHECK (role IN ('customer','admin','staff')),
    display_name    VARCHAR(255),
    status          VARCHAR(20)  NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','inactive')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, identifier)
);

CREATE INDEX idx_users_tenant ON users(tenant_id);
CREATE INDEX idx_users_identifier ON users(tenant_id, identifier);
```

### V003__create_otp_records.sql

**File**: `apps/api/src/main/resources/db/migration/V003__create_otp_records.sql`

```sql
CREATE TABLE otp_records (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    identifier      VARCHAR(255) NOT NULL,
    identifier_type VARCHAR(20)  NOT NULL,
    channel         VARCHAR(20)  NOT NULL CHECK (channel IN ('email','sms')),
    status          VARCHAR(20)  NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','verified','expired','invalidated')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    expires_at      TIMESTAMPTZ  NOT NULL,
    verified_at     TIMESTAMPTZ
);

CREATE INDEX idx_otp_tenant_identifier ON otp_records(tenant_id, identifier);
CREATE INDEX idx_otp_expires ON otp_records(expires_at) WHERE status = 'pending';
```

### V004__create_locations.sql

**File**: `apps/api/src/main/resources/db/migration/V004__create_locations.sql`

```sql
CREATE TABLE locations (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    name            VARCHAR(255) NOT NULL,
    slug            VARCHAR(100) NOT NULL,
    address_line1   VARCHAR(255),
    address_line2   VARCHAR(255),
    city            VARCHAR(100),
    state           VARCHAR(100),
    country_code    CHAR(2),
    postal_code     VARCHAR(20),
    latitude        NUMERIC(9,6),
    longitude       NUMERIC(9,6),
    timezone        VARCHAR(60)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','inactive')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (tenant_id, slug)
);

CREATE INDEX idx_locations_tenant ON locations(tenant_id);
```

### V005__create_branch_holidays.sql

**File**: `apps/api/src/main/resources/db/migration/V005__create_branch_holidays.sql`

```sql
CREATE TABLE branch_holidays (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    location_id     UUID        NOT NULL REFERENCES locations(id),
    holiday_date    DATE        NOT NULL,
    name            VARCHAR(255),
    is_recurring    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (location_id, holiday_date)
);

CREATE INDEX idx_holidays_location_date ON branch_holidays(location_id, holiday_date);
```

### V006__create_resources.sql

**File**: `apps/api/src/main/resources/db/migration/V006__create_resources.sql`

```sql
CREATE TABLE resources (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    location_id     UUID        NOT NULL REFERENCES locations(id),
    name            VARCHAR(255) NOT NULL,
    resource_type   VARCHAR(100) NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','inactive')),
    extension       JSONB        NOT NULL DEFAULT '{}',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_resources_tenant_location ON resources(tenant_id, location_id);
```

### V007__create_resource_schedules.sql

**File**: `apps/api/src/main/resources/db/migration/V007__create_resource_schedules.sql`

```sql
CREATE TABLE resource_schedules (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    resource_id     UUID        NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    day_of_week     SMALLINT    NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time      TIME        NOT NULL,
    end_time        TIME        NOT NULL,
    CHECK (end_time > start_time)
);

CREATE INDEX idx_schedules_resource_day ON resource_schedules(resource_id, day_of_week);
```

### V008__create_resource_breaks.sql

**File**: `apps/api/src/main/resources/db/migration/V008__create_resource_breaks.sql`

```sql
CREATE TABLE resource_breaks (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID        NOT NULL REFERENCES tenants(id),
    resource_id     UUID        NOT NULL REFERENCES resources(id) ON DELETE CASCADE,
    day_of_week     SMALLINT    NOT NULL CHECK (day_of_week BETWEEN 1 AND 7),
    start_time      TIME        NOT NULL,
    end_time        TIME        NOT NULL,
    label           VARCHAR(100),
    CHECK (end_time > start_time)
);

CREATE INDEX idx_breaks_resource_day ON resource_breaks(resource_id, day_of_week);
```

### V009__create_service_types.sql

**File**: `apps/api/src/main/resources/db/migration/V009__create_service_types.sql`

```sql
CREATE TABLE service_types (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID        NOT NULL REFERENCES tenants(id),
    name                VARCHAR(255) NOT NULL,
    description         TEXT,
    duration_minutes    INTEGER     NOT NULL CHECK (duration_minutes BETWEEN 5 AND 480),
    buffer_before_min   INTEGER     NOT NULL DEFAULT 0 CHECK (buffer_before_min BETWEEN 0 AND 120),
    buffer_after_min    INTEGER     NOT NULL DEFAULT 0 CHECK (buffer_after_min BETWEEN 0 AND 120),
    intake_schema       JSONB       NOT NULL DEFAULT '{"type":"object","properties":{}}',
    allowed_resource_types JSONB   NOT NULL DEFAULT '[]',
    status              VARCHAR(20)  NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active','inactive')),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_service_types_tenant ON service_types(tenant_id);
```

### Undo scripts pattern

**File**: `apps/api/src/main/resources/db/undo/U009__create_service_types.sql` (and U008–U001)

```sql
-- Undo scripts drop tables in reverse dependency order
DROP TABLE IF EXISTS service_types;    -- U009
DROP TABLE IF EXISTS resource_breaks;  -- U008
DROP TABLE IF EXISTS resource_schedules; -- U007
DROP TABLE IF EXISTS resources;        -- U006
DROP TABLE IF EXISTS branch_holidays;  -- U005
DROP TABLE IF EXISTS locations;        -- U004
DROP TABLE IF EXISTS otp_records;      -- U003
DROP TABLE IF EXISTS users;            -- U002
DROP TABLE IF EXISTS tenants;          -- U001
```

---

## Integration Points

**Depends on**: ATOM-LOCAL-DEV-003 (PostgreSQL must be running); Docker Compose stack healthy

**Enables**: ATOM-SPRING-SECURITY-005 (needs schema for `hibernate.ddl-auto: validate`); ATOM-OTP-006 (needs `otp_records` table); ATOM-AUTH-CONTROLLER-009 (needs `users` and `tenants` tables)

**Cascading updates required**:
- `docs/memory/domain-model.md` — add all 9 entity definitions
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/resources/db/migration/V001__create_tenants.sql` | New | Tenants table |
| `apps/api/src/main/resources/db/migration/V002__create_users.sql` | New | Users table |
| `apps/api/src/main/resources/db/migration/V003__create_otp_records.sql` | New | OTP records table |
| `apps/api/src/main/resources/db/migration/V004__create_locations.sql` | New | Locations table |
| `apps/api/src/main/resources/db/migration/V005__create_branch_holidays.sql` | New | Branch holidays table |
| `apps/api/src/main/resources/db/migration/V006__create_resources.sql` | New | Resources table |
| `apps/api/src/main/resources/db/migration/V007__create_resource_schedules.sql` | New | Resource schedules table |
| `apps/api/src/main/resources/db/migration/V008__create_resource_breaks.sql` | New | Resource breaks table |
| `apps/api/src/main/resources/db/migration/V009__create_service_types.sql` | New | Service types table |
| `apps/api/src/main/resources/db/undo/U001–U009` | New | Rollback scripts |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn flyway:migrate` passes (clean DB)
- [ ] `mvn flyway:validate` passes (no checksum mismatches)
- [ ] Zero JPA queries without `tenant_id` in WHERE clause
- [ ] Zero industry-specific terms in any column name or table name
- [ ] Flyway migration exists for all schema changes
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: flyway-migrations | Phase: 1*
