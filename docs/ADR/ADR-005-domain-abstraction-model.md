# ADR-005 — Generic Domain Model with JSONB Extension

**Status:** Accepted
**Date:** 2026-06-18
**Deciders:** Architecture Lead (Suraj)
**adr-docs agent:** auto-captured

---

## Context

The framework targets multiple industries: healthcare (doctors/patients), automotive (mechanics/vehicles), beauty (stylists/clients), legal (lawyers/consultants), and more. Each industry has domain-specific data requirements:

- Healthcare: patient medical history, insurance details, chief complaint
- Automotive: vehicle make/model/VIN, service history
- Beauty: stylist preferences, product allergies

We must decide how to accommodate industry-specific data without creating industry-specific schemas, tables, or API fields in the core system.

---

## Decision

**Generic entity model with JSONB extension column for domain metadata.**

Core entities use industry-agnostic names:

| Generic term | Industry examples |
|---|---|
| `Resource` | Doctor, Mechanic, Stylist, Booth, Meeting room |
| `Service` | Consultation, Oil change, Haircut, Legal review |
| `Booking` | Appointment, Work order, Reservation |
| `Location` | Clinic branch, Auto shop, Salon, Office |
| `Tenant` | Metro Clinic LLC, Quick Lube Inc, Style Studio |

Industry-specific data is stored in a `JSONB extension` column on `Booking` and `Resource`:

```json
// A healthcare booking's extension:
{
  "chiefComplaint": "Lower back pain",
  "insuranceProvider": "BlueCross",
  "insuranceMemberId": "BC-123456",
  "referredBy": "Dr. Johnson"
}

// An automotive booking's extension:
{
  "vehicleMake": "Toyota",
  "vehicleModel": "Camry",
  "vehicleYear": 2019,
  "vin": "1HGCM82633A123456",
  "mileage": 45000
}
```

The schema for the extension is defined by the tenant admin via the JSON Schema form builder (`service_types.intake_schema`), not by the core application.

---

## Rationale

### Why not industry-specific tables?

Creating tables like `patient_records`, `vehicle_data`, `stylist_profiles` would:
- Require a new database migration for every new industry vertical
- Force the core API to have industry-specific endpoints
- Require industry-specific validation logic in the booking engine
- Destroy the "omni-industry" value proposition

### Why JSONB over EAV (Entity-Attribute-Value)?

| Concern | EAV pattern | JSONB extension |
|---|---|---|
| Query complexity | Complex joins for every attribute | Direct JSON path operators |
| Schema enforcement | None at DB level | JSON Schema validation at application level |
| Indexing | Index per attribute column | GIN index on entire JSONB column |
| Readability | Requires joins to reconstruct object | Native JSON structure |
| Flexibility | Adds rows per attribute | Arbitrary nesting and arrays |

JSONB is strictly superior to EAV for this use case.

### Why not a document database (MongoDB)?

The core booking data (scheduling logic, concurrency, transactions) is highly relational and requires ACID guarantees. Moving to a document DB would sacrifice transactional integrity for the flexibility benefit — which JSONB already provides within PostgreSQL.

---

## Core model constraint — CRITICAL

**The core booking engine must NEVER read from the `extension` JSONB column for any business logic decision.**

Extension data is:
- Written at booking confirmation (from user form submission)
- Read only by downstream consumers (audit service, notification service for personalization) and tenant-specific report queries
- Never used for slot calculation, concurrency checks, or state machine transitions

This constraint ensures the core engine remains truly industry-agnostic.

---

## Consequences

- Positive: Zero schema changes required for new industry verticals
- Positive: Tenant admins self-service their own intake form schemas
- Positive: Core engine stays lean and industry-agnostic
- Positive: GIN index on JSONB enables efficient tenant-specific reporting queries
- Negative: No compile-time type safety for extension data
- Mitigation: JSON Schema validation at API layer before write; Zod schema in Next.js; Spring validator against `intakeSchema`
- Negative: JSONB GIN indexes are larger than B-tree indexes on typed columns
- Mitigation: GIN indexes only on frequently queried JSONB paths, not entire extension column

---

## Schema evolution for extension data

When a tenant changes their `intakeSchema` (adds or removes fields):
- Existing bookings retain their old extension data (no migration needed)
- New bookings use the new schema
- The UI renders existing bookings with a "legacy fields" notice for removed schema fields
- No migration agent involvement required for extension schema changes

---

## Alternatives Considered

**Industry-specific subclasses (table inheritance):** Rejected — requires code changes for each industry; violates the framework's extensibility goal.

**Separate extension tables per tenant:** Rejected — N tables for N tenants multiplied by N industries; operationally unmanageable.

**Pure document database:** Rejected — ACID requirements for concurrency control outweigh document flexibility benefits; JSONB provides the same flexibility within PostgreSQL.
