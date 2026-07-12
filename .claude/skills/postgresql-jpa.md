# PostgreSQL + JPA Skill — Scheduling Framework Patterns

> Reference for the Coder and Migrations agents.
> PostgreSQL 15+ with Flyway migrations. All schema changes via Flyway only.

---

## Flyway Migration Rules

### Naming Convention

```
V{n}__{description}.sql

V1__initial_schema.sql
V2__add_booking_status_index.sql
V3__add_outbox_table.sql
V10__add_processed_events.sql
```

**Rules:**
- One migration per logical change
- New columns must be `NULLABLE` or have a `DEFAULT` (zero-downtime deployments)
- Indexes created in a **separate migration** from column additions (PostgreSQL locks)
- Never include `DROP TABLE` or `DROP COLUMN` — deprecate and defer removal
- Always generate a rollback SQL counterpart (`undo/U{n}__{description}.sql`)

### Standard Table Template

```sql
CREATE TABLE bookings (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID NOT NULL,                          -- NEVER NULL — multi-tenancy
    resource_id UUID NOT NULL,
    service_id  UUID NOT NULL,
    location_id UUID NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING_HOLD'
                    CHECK (status IN ('PENDING_HOLD','CONFIRMED','CANCELLED','COMPLETED','NO_SHOW')),
    start_time  TIMESTAMPTZ NOT NULL,
    end_time    TIMESTAMPTZ NOT NULL,
    extension   JSONB,                                  -- tenant-injected domain data
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_booking_time CHECK (end_time > start_time)
);
```

---

## Required Indexes

Every table with `tenant_id` needs at minimum:

```sql
-- Separate migration step from table creation
CREATE INDEX CONCURRENTLY idx_bookings_tenant
    ON bookings(tenant_id);

-- Compound index for slot conflict queries (NFR-1.3 — mandatory before slot query code)
CREATE INDEX CONCURRENTLY idx_bookings_tenant_resource_time
    ON bookings(tenant_id, resource_id, start_time);

-- Partial index for active bookings (common filter)
CREATE INDEX CONCURRENTLY idx_bookings_active
    ON bookings(tenant_id, resource_id, start_time)
    WHERE status NOT IN ('CANCELLED', 'NO_SHOW');
```

Use `CONCURRENTLY` for all index creation in production migrations (no table lock).

---

## Row-Level Security (Audit Table)

The `audit_log` table uses PostgreSQL RLS for HIPAA compliance:

```sql
CREATE TABLE audit_log (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL,
    actor_id     UUID NOT NULL,
    action       VARCHAR(100) NOT NULL,
    aggregate    VARCHAR(50) NOT NULL,
    aggregate_id UUID NOT NULL,
    payload      JSONB,
    occurred_at  TIMESTAMPTZ NOT NULL DEFAULT now()
) PARTITION BY RANGE (occurred_at);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;

CREATE POLICY audit_tenant_isolation ON audit_log
    USING (tenant_id = current_setting('app.tenant_id')::UUID);

-- audit_writer role: INSERT only (no SELECT, UPDATE, DELETE)
GRANT INSERT ON audit_log TO audit_writer;
```

---

## JSONB Extension Column

The `extension` JSONB column is for tenant-injected metadata only:

```sql
-- Query JSONB (reporting/admin only — never in core booking logic)
SELECT id, extension->>'patientMrn' AS mrn
FROM bookings
WHERE tenant_id = $1
  AND extension @> '{"industry": "healthcare"}';

-- Index a frequently queried JSONB key
CREATE INDEX CONCURRENTLY idx_bookings_ext_industry
    ON bookings USING GIN ((extension->'industry'));
```

**Rule:** Core `BookingService` logic must NEVER read from `extension`. It is opaque to the framework.

---

## JPA + Spring Data Patterns

### Enum Mapping

```java
public enum BookingStatus {
    PENDING_HOLD, CONFIRMED, CANCELLED, COMPLETED, NO_SHOW
}

// In entity:
@Enumerated(EnumType.STRING)
@Column(name = "status", length = 20)
private BookingStatus status;
```

### Timestamp Mapping

```java
@Column(name = "start_time", nullable = false)
private Instant startTime;         // maps to TIMESTAMPTZ

@CreationTimestamp
@Column(name = "created_at", updatable = false)
private OffsetDateTime createdAt;  // maps to TIMESTAMPTZ
```

### Pessimistic Locking

```java
@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)  // SELECT ... FOR UPDATE
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
    @Query("SELECT b FROM Booking b WHERE b.id = :id AND b.tenantId = :tenantId")
    Optional<Booking> findByIdForUpdate(@Param("id") UUID id,
                                        @Param("tenantId") UUID tenantId);
}
```

Lock timeout hint prevents indefinite waits — 3000ms then throw `LockTimeoutException`.

### JSONB Type Mapping (Hibernate)

```java
// In entity — requires hibernate-types dependency
@Type(JsonBinaryType.class)
@Column(name = "extension", columnDefinition = "jsonb")
private JsonNode extension;
```

---

## Connection Pool (HikariCP)

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:5432/${DB_NAME:scheduling}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
      connection-test-query: SELECT 1
  jpa:
    hibernate:
      ddl-auto: validate   # NEVER create/update in production — Flyway owns schema
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        jdbc.time_zone: UTC
        format_sql: false
```

**`ddl-auto: validate`** — Hibernate validates entity ↔ schema match, never modifies it.

---

## Testcontainers Setup

```java
@SpringBootTest
@Testcontainers
class BookingServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("scheduling_test")
        .withUsername("test")
        .withPassword("test");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }
}
```

---

## Anti-Patterns (Never Do)

- ❌ `ddl-auto: create` or `update` — Flyway owns the schema
- ❌ `CREATE INDEX` without `CONCURRENTLY` in production migrations (table lock)
- ❌ `DROP COLUMN` in a migration — deprecate first, remove in next release
- ❌ Storing pre-computed slots in any table (ADR-001)
- ❌ JPA query without `tenant_id` filter — every query scoped to tenant
- ❌ Reading `extension` JSONB in `BookingService` — it is opaque
- ❌ `@GeneratedValue(strategy = AUTO)` — always use `UUID` strategy
