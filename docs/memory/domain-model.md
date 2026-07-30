# scheduling:domain-model
> Maintained by: adr-docs agent
> Last updated: 2026-07-20

| Entity | Fields | Constraints | Updated |
|---|---|---|---|
| Tenant | id, name, slug, plan, status, settings(JSONB), created_at, updated_at | slug UNIQUE; anchor of row-level tenancy (ADR-004) | 2026-07-20 |
| User | id, tenant_id, identifier, identifier_type(EMAIL/PHONE), role, status | UNIQUE(tenant_id, identifier); tenant_id NOT NULL FK | 2026-07-20 |
| OtpRecord | id, tenant_id, identifier, otp_hash(bcrypt), channel(EMAIL/SMS), status(PENDING/USED/EXPIRED), attempt_count, expires_at | Redis is the TTL source of truth; DB row is audit trail | 2026-07-20 |
| Location | id, tenant_id, name, address*, timezone(IANA), status | tenant_id NOT NULL FK | 2026-07-20 |
| BranchHoliday | id, tenant_id, location_id, holiday_date, is_recurring | UNIQUE(location_id, holiday_date) | 2026-07-20 |
| Resource | id, tenant_id, location_id, name, resource_type, status, extension(JSONB) | extension is tenant metadata only — core logic never reads it (ADR-005) | 2026-07-20 |
| ResourceSchedule | id, tenant_id, resource_id, day_of_week(0-6), start_time, end_time, effective_from/to | CHECK end_time > start_time | 2026-07-20 |
| ResourceBreak | id, tenant_id, resource_id, day_of_week(0-6), break_start, break_end, label | CHECK break_end > break_start | 2026-07-20 |
| ServiceType | id, tenant_id, name, duration_minutes, buffer_before_min, buffer_after_min, allowed_resource_types(TEXT[]), intake_schema(JSONB), status | duration_minutes > 0 | 2026-07-20 |

No `slots` table exists or ever will (ADR-001).
