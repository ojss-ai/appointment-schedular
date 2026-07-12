# ADR-004 — Row-Level Multi-Tenancy via tenant_id Discriminator

**Status:** Accepted
**Date:** 2026-06-18
**Deciders:** Architecture Lead (Suraj)
**adr-docs agent:** auto-captured

---

## Context

The platform is multi-tenant: multiple independent organizations (tenants) use the same deployed system. Their data must be completely isolated — a query from Tenant A must never return data belonging to Tenant B.

Three standard approaches exist:
- (A) Separate database per tenant
- (B) Separate schema per tenant (within same DB)
- (C) Shared schema with row-level `tenant_id` discriminator column

---

## Decision

**Option C — Shared schema, row-level tenant_id discriminator.**

Every table carries `tenant_id UUID NOT NULL` as a mandatory column. All queries are filtered by `tenant_id` at the application layer (Spring AOP) and enforced as a NOT NULL constraint at the database layer.

Enforcement stack:
1. **Database:** `tenant_id NOT NULL` on every table; FK to `tenants(id)`
2. **ORM layer:** `TenantFilterAspect` (Spring AOP) injects `tenant_id` into every JPA query via Hibernate filter
3. **Controller layer:** `@PreAuthorize("@tenantGuard.check(#tenantId)")` on every endpoint
4. **JWT:** `tenantId` claim must match path/body `tenantId` on every request

---

## Rationale

| Concern | Separate DB | Separate schema | Shared (tenant_id) |
|---|---|---|---|
| Isolation strength | Strongest | Strong | Strong with proper enforcement |
| Operational cost | Very high (N DBs to manage) | High (N schemas, N migration runs) | Low (1 DB, 1 migration run) |
| Query performance | Isolated per tenant | Isolated per tenant | Shared; indexes span tenants |
| Tenant provisioning | Create new DB (slow) | Create new schema (moderate) | Insert row in `tenants` table (instant) |
| Schema migrations | Run per tenant (complex) | Run per tenant (complex) | Run once for all tenants |
| Horizontal scale | Complex (N connection pools) | Moderate | Simple (1 connection pool) |

For the v1 scale target (hundreds of tenants, not hundreds of thousands), the operational simplicity of a shared schema significantly outweighs the isolation benefits of per-tenant databases.

### Index strategy for multi-tenant queries

All performance-critical indexes include `tenant_id` as the leading column:
```sql
CREATE INDEX idx_bookings_tenant_location_start
    ON bookings(tenant_id, location_id, slot_start)
    WHERE status IN ('PENDING_HOLD', 'CONFIRMED');
```

This ensures that tenant-scoped queries use the index efficiently without scanning other tenants' data.

### Spring AOP enforcement detail

```java
@Aspect
@Component
public class TenantFilterAspect {
    @Before("@within(org.springframework.stereotype.Service)")
    public void injectTenantFilter(JoinPoint jp) {
        String tenantId = SecurityContextHolder.getContext()
            .getAuthentication().getPrincipal().getTenantId();
        TenantContext.setCurrentTenant(tenantId);
        // Hibernate filter automatically adds WHERE tenant_id = :tenantId
    }
    @After("@within(org.springframework.stereotype.Service)")
    public void clearTenantContext(JoinPoint jp) {
        TenantContext.clear();
    }
}
```

---

## Consequences

- Positive: Simple provisioning — new tenant = new row in `tenants` table
- Positive: Single migration run applies to all tenants simultaneously
- Positive: Low operational overhead (1 DB instance, 1 connection pool)
- Negative: Application-layer enforcement is a potential vulnerability — a missing `tenant_id` filter leaks data
- Mitigation: Security agent audits all JPA queries on every commit; any query without `tenant_id` in WHERE clause fails CI
- Negative: Large tenants share B-tree index space with small tenants
- Mitigation: Index leading column is `tenant_id`; large-tenant queries scan only their partition of the index

---

## Alternatives Considered

**Separate database per tenant:** Rejected. Operational overhead is prohibitive for v1. Connection pool management across N databases is complex. Schema migrations must run N times.

**Separate schema per tenant:** Rejected. Still requires N migration runs. Tenant provisioning requires DDL execution. Connection pool must switch schemas per request. Marginally better isolation than shared, but not enough to justify the complexity.

**PostgreSQL Row-Level Security (RLS):** Considered as an additional defense-in-depth layer. May be added in v2 on top of the application-layer enforcement. RLS at the DB layer would catch bugs where the application-layer filter is accidentally bypassed.

---

## Review Trigger

Revisit this ADR if: (a) a tenant requires dedicated infrastructure for compliance reasons, or (b) index bloat from large tenants causes performance degradation that cannot be resolved by index tuning. At that point, a hybrid model (shared for small tenants, dedicated for enterprise tenants) may be considered.
