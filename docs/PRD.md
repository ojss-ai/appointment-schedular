# Product Requirements Document (PRD)
## Multi-Tenant Omni-Industry Scheduling Framework

**Version:** 1.0.0
**Status:** Approved
**Authors:** Suraj (Architecture Lead)
**Last updated:** 2026-06-18

---

## 1. Executive Summary

This framework provides a universal, white-label appointment scheduling engine that any industry vertical can adopt without custom backend development. The core insight is that all booking systems share the same mechanical primitives — a bookable resource, a time slot, a confirmed reservation — and differ only in the metadata attached to those primitives.

By abstracting the domain model to `Resource`, `Service`, `Booking`, `Location`, and `Tenant`, and exposing a JSONB extension point for tenant-specific fields, the system serves a medical clinic (resources = doctors, extension = patient history) and an auto shop (resources = mechanics, extension = vehicle data) through the same codebase.

---

## 2. Problem Statement

Existing scheduling tools are either industry-specific (limiting extensibility) or overly generic (lacking the concurrency guarantees and compliance features enterprise tenants require). There is no open, multi-tenant scheduling engine that:

- Enforces row-level data isolation between tenants
- Computes slot availability dynamically (preventing stale-slot race conditions)
- Guarantees exactly-once notification delivery via Kafka
- Produces an immutable, HIPAA-ready audit trail
- Allows tenants to inject custom intake forms without touching the schema

This framework fills that gap.

---

## 3. Goals

| Goal | Success Metric |
|---|---|
| Multi-tenant isolation | Zero cross-tenant data leakage in security audit |
| High-concurrency booking | 500+ reservations/min with no duplicate slot assignments |
| Fast slot discovery | Slot-generation endpoint p99 < 300ms under load |
| Reliable event delivery | Zero duplicate notifications in 30-day soak test |
| Industry agnosticism | Same codebase deployed for 3+ distinct industry tenants |
| Developer extensibility | New tenant custom fields added via JSON schema, zero backend changes |

---

## 4. Non-Goals

- Payment processing (tenants integrate their own payment provider)
- Video conferencing integration (out of scope v1)
- Mobile native apps (responsive web only in v1)
- AI-powered scheduling recommendations (Phase 4 — optional agentic layer)

---

## 5. User Personas

### 5.1 Tenant Administrator
**Who:** Office manager, clinic coordinator, shop owner
**Goal:** Configure branches, register resources (staff/rooms), define services and buffer rules, build custom intake forms
**Pain point:** Currently uses fragmented tools (calendar + spreadsheet + form builder) with no unified system
**Key flows:** Branch setup → Resource registration → Service definition → Form schema builder

### 5.2 End User (Customer / Patient / Client)
**Who:** Anyone booking an appointment with a tenant
**Goal:** Discover available time slots, complete booking with minimal friction, receive confirmation
**Pain point:** Having to call or email instead of self-serve; receiving no confirmation or reminder
**Key flows:** Auth (OTP) → Location/Resource selection → Slot selection → Custom form → Confirmation

### 5.3 Platform Super-Admin
**Who:** Internal team managing the SaaS platform
**Goal:** Onboard new tenants, monitor system health, manage Kafka lag and audit trails
**Key flows:** Tenant provisioning → Health dashboard → Audit log viewer

---

## 6. Functional Requirements

### FR-1 — Authentication & Identity Access Management

| ID | Requirement | Priority | Acceptance Criteria |
|---|---|---|---|
| FR-1.1 | Login accepts email address OR phone number | P0 | System correctly identifies identifier type and routes to appropriate dispatch strategy |
| FR-1.2 | Email login dispatches magic link or OTP via SES/SMTP | P0 | Email received within 30 seconds; link/OTP valid for exactly 5 minutes |
| FR-1.2b | Phone login dispatches numeric OTP via Twilio SMS | P0 | SMS received within 30 seconds; OTP valid for exactly 5 minutes |
| FR-1.3 | OTP expires after 5 minutes OR after first failed attempt | P0 | Expired OTP returns `401 OTP_EXPIRED`; used OTP returns `401 OTP_ALREADY_USED` |
| FR-1.4 | Successful verification returns JWT with `tenant_id`, `user_id`, `role_claims` | P0 | JWT decoded contains all three claims; token signed with HS256/RS256 |

### FR-2 — Location & Resource Configurator (Admin Portal)

| ID | Requirement | Priority | Acceptance Criteria |
|---|---|---|---|
| FR-2.1 | Admin configures branches with lat/lng, timezone, address | P1 | Branch saved with all fields; timezone used for all slot computations in that branch |
| FR-2.2 | Admin registers Resources to a branch with availability schedule | P1 | Resource linked to branch; schedule (days/hours) persisted and used in operating matrix |
| FR-2.3 | JSON Schema form builder for custom intake fields | P1 | Admin-defined schema renders as form at checkout; submitted data stored in `extension` JSONB |

### FR-3 — Dynamic Slot Engine

| ID | Requirement | Priority | Acceptance Criteria |
|---|---|---|---|
| FR-3.1 | Operating matrix computed from shift + breaks + holidays | P0 | Matrix correctly excludes breaks and holidays; verified by unit test suite |
| FR-3.2 | Slot availability computed on demand (no stored slots) | P0 | No `slots` table exists; availability endpoint computes from booking records in real time |
| FR-3.3 | Pre/post buffer padding enforced automatically | P0 | Booking cannot be placed within configured buffer window of existing booking; verified by concurrency tests |

### FR-4 — Concurrency & Reservation Engine

| ID | Requirement | Priority | Acceptance Criteria |
|---|---|---|---|
| FR-4.1 | Pessimistic lock prevents duplicate slot assignment | P0 | Concurrent booking of same slot by 10 simultaneous clients results in exactly 1 confirmed and 9 rejected |
| FR-4.2 | PENDING_HOLD state applied on slot selection; expires in 10 min | P0 | State machine transitions: AVAILABLE → PENDING_HOLD → CONFIRMED or AVAILABLE; hold auto-expires |
| FR-4.3 | Spring scheduler GC's expired holds without deadlocks | P0 | GC job runs every 60 seconds; expired holds reverted; no deadlock in 48-hour soak test |

### FR-5 — Kafka Event Mesh

| ID | Requirement | Priority | Acceptance Criteria |
|---|---|---|---|
| FR-5.1 | Booking lifecycle events published to `tenant.bookings.lifecycle` | P1 | Event emitted within 500ms of state transition; verified by consumer integration test |
| FR-5.2 | Transactional outbox: business state + event in single ACID write | P1 | Simulated Kafka failure does not cause double-write or lost event; verified by chaos test |
| FR-5.3a | NotificationConsumer sends email/SMS on CONFIRMED/CANCELLED events | P1 | Notification sent exactly once per event; idempotency verified by duplicate-delivery test |
| FR-5.3b | AuditLedgerConsumer writes immutable record for every state transition | P1 | Audit record exists for every booking event; records are append-only; HIPAA field checklist verified |

---

## 7. Non-Functional Requirements

### Performance

| ID | Requirement | Gate |
|---|---|---|
| NFR-1.1 | 500+ concurrent reservations/min | k6 load test, Phase 5 |
| NFR-1.2 | Slot generation endpoint p99 < 300ms | Load test at 500 RPS |
| NFR-1.3 | Compound index on (tenant_id, location_id, start_time) | Migration exists before slot queries |

### Security & Compliance

| ID | Requirement | Gate |
|---|---|---|
| NFR-2.1 | Idempotent Kafka consumers (dedup by message key) | Integration test: duplicate message → single action |
| NFR-2.2 | Avro schema registry for all Kafka payloads | All schemas registered before any consumer deployed |
| NFR-2.3 | HIPAA audit trail on audit-service | Field checklist: who, what, when, tenantId, resourceId, bookingId |
| NFR-2.4 | Zero cross-tenant data leakage | Security agent audit on every release |

### Resilience

| ID | Requirement | Approach |
|---|---|---|
| NFR-3.1 | PENDING_HOLD GC prevents slot starvation | Spring `@Scheduled` every 60s |
| NFR-3.2 | Kafka consumer retry with dead-letter queue | 3 retries, then DLQ; alert on DLQ depth > 0 |
| NFR-3.3 | OTP rate limiting | Max 5 OTP requests per identifier per hour |

---

## 8. User Flows

### Flow 1 — Customer Books Appointment
1. Customer enters email or phone
2. System dispatches OTP/magic link
3. Customer verifies → receives JWT
4. Customer selects Location → Resource → Service
5. System returns available slots (computed on demand)
6. Customer selects slot → state transitions to PENDING_HOLD
7. Customer completes custom intake form (tenant-defined schema)
8. Customer confirms → state transitions to CONFIRMED
9. Outbox event emitted → Kafka → NotificationConsumer → email/SMS confirmation sent

### Flow 2 — Admin Configures a New Branch
1. Admin logs in (JWT with `role_claims: ADMIN`)
2. Admin creates Location (address, timezone, coordinates)
3. Admin registers Resources (name, type, availability schedule)
4. Admin defines Services (name, duration, buffer rules, allowed resource types)
5. Admin builds custom intake form schema (drag-and-drop JSON Schema builder)
6. Configuration saved; slot engine immediately uses new operating matrix

### Flow 3 — Expired Hold Recovery
1. Customer selects slot → PENDING_HOLD (10-min timer starts)
2. Customer abandons checkout (no action for 10 minutes)
3. GC scheduler detects expired hold record
4. GC reverts state to AVAILABLE
5. Slot becomes bookable by next customer
6. No notification sent (hold abandonment is silent)

---

## 9. Constraints & Assumptions

- All time calculations performed in UTC; display converted to branch timezone in UI
- Tenant provisioning (creating a new tenant) is a manual super-admin operation in v1
- Each Resource belongs to exactly one Location (no cross-branch resource sharing in v1)
- OTP delivery depends on third-party SLAs (AWS SES, Twilio); system degrades gracefully if unavailable
- Kafka is treated as durable; retention set to 7 days minimum
- Schema registry is Confluent-compatible; local dev uses embedded registry

---

## 10. Dependencies

| Dependency | Version | Purpose |
|---|---|---|
| Spring Boot | 3.x | Backend framework |
| Spring Security | 6.x | Auth, JWT, tenant guard |
| Spring Data JPA | 3.x | ORM layer |
| Flyway | 9.x | Database migrations |
| Next.js | 15 | Frontend framework |
| React Hook Form | 7.x | Form state management |
| Zod | 3.x | Schema validation |
| react-jsonschema-form | 5.x | Tenant intake form builder |
| Tanstack Query | 5.x | Server state / slot polling |
| Apache Kafka | 3.x | Event streaming |
| Confluent Schema Registry | 7.x | Avro schema enforcement |
| PostgreSQL | 15+ | Primary datastore |
| Redis | 7.x | Distributed lock, OTP TTL |
| Testcontainers | 1.19+ | Integration test infra |
| k6 | latest | Load testing |
| Playwright | latest | E2E testing |
