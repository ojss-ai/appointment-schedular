# Architecture Specification
## Multi-Tenant Omni-Industry Scheduling Framework

**Version:** 1.0.0
**Status:** Approved

---

## 1. System Context

```
┌──────────────────────────────────────────────────────────────────┐
│                        External Actors                           │
│                                                                  │
│  [Customer Browser]   [Admin Browser]   [Super-Admin Dashboard]  │
└──────────┬─────────────────┬───────────────────┬────────────────┘
           │                 │                   │
           ▼                 ▼                   ▼
┌──────────────────────────────────────────────────────────────────┐
│                  Next.js 15 (App Router)                         │
│          apps/web  ─  CDN / Vercel / Amplify                     │
│  [Booking UI]  [Admin Portal]  [Auth Flow]  [Form Builder]       │
└──────────────────────────┬───────────────────────────────────────┘
                           │ JWT-authenticated REST
                           ▼
┌──────────────────────────────────────────────────────────────────┐
│              Spring Boot 3.x Core Engine                         │
│                      apps/api                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────────┐ │
│  │   Auth   │  │  Slot    │  │ Booking  │  │   Admin Config   │ │
│  │ Controller│  │Calculator│  │ Service  │  │   Controller     │ │
│  └──────────┘  └──────────┘  └──────────┘  └──────────────────┘ │
│                        │ JPA + Pessimistic Lock                  │
└────────────────────────┼─────────────────────────────────────────┘
             ┌───────────┴──────────────┐
             │                          │
             ▼                          ▼
┌────────────────────┐      ┌───────────────────────┐
│   PostgreSQL 15+   │      │    Outbox Table        │
│  (Primary Store)   │      │ (ACID event staging)   │
│  tenant_id on all  │      └───────────┬────────────┘
│  tables (Row-level │                  │ Debezium CDC
│  isolation)        │                  ▼
└────────────────────┘      ┌───────────────────────┐
             ▲              │    Apache Kafka        │
             │ Redis        │  tenant.bookings.*     │
┌────────────┴───────┐      └─────────┬─────────────┘
│  Redis             │                │
│  - OTP TTL         │    ┌───────────┴──────────────┐
│  - Distributed     │    │                          │
│    lock (scale)    │    ▼                          ▼
│  - Hold expiry     │ ┌──────────────┐  ┌───────────────────┐
└────────────────────┘ │Notification  │  │  Audit Ledger     │
                       │  Service     │  │    Service        │
                       │ (SES/Twilio) │  │  (HIPAA log)      │
                       └──────────────┘  └───────────────────┘
```

---

## 2. Component Descriptions

### 2.1 Next.js 15 Frontend (`apps/web`)

**Responsibilities:**
- Booking UI: slot calendar, resource selection, service selection
- Auth flow: identifier input → OTP entry → JWT cookie storage
- Admin portal: branch topology, resource registration, service config, form builder
- Super-admin dashboard: tenant management, health monitoring

**Key patterns:**
- Server Components by default; Client Components only for interactive forms
- JWT stored in `HttpOnly` cookie; sent automatically with every API request
- `apps/web/lib/api-client.ts` is the single outbound HTTP abstraction
- Slot availability fetched with Tanstack Query (polling interval: 15 seconds)
- `react-jsonschema-form` renders tenant-defined intake schemas at checkout

**Pages layout:**
```
app/
├── (auth)/
│   ├── login/page.tsx
│   └── verify/page.tsx
├── (booking)/
│   ├── [tenantSlug]/
│   │   ├── page.tsx          ← location selector
│   │   ├── [locationId]/
│   │   │   ├── page.tsx      ← resource + service selector
│   │   │   └── [resourceId]/
│   │   │       └── page.tsx  ← slot calendar + checkout
├── (admin)/
│   ├── dashboard/page.tsx
│   ├── locations/page.tsx
│   ├── resources/page.tsx
│   ├── services/page.tsx
│   └── forms/page.tsx        ← JSON Schema form builder
└── (super-admin)/
    └── tenants/page.tsx
```

---

### 2.2 Spring Boot Core Engine (`apps/api`)

**Package structure:**
```
com.scheduler/
├── auth/
│   ├── controller/AuthController.java
│   ├── service/OtpService.java
│   ├── service/JwtService.java
│   └── model/OtpRecord.java
├── location/
│   ├── controller/LocationController.java
│   ├── service/LocationService.java
│   └── entity/Location.java
├── resource/
│   ├── controller/ResourceController.java
│   ├── service/ResourceService.java
│   └── entity/Resource.java
├── service/
│   ├── controller/ServiceTypeController.java
│   ├── service/ServiceTypeService.java
│   └── entity/ServiceType.java
├── booking/
│   ├── controller/BookingController.java
│   ├── service/BookingService.java
│   ├── service/SlotCalculatorService.java
│   ├── scheduler/HoldGcScheduler.java
│   └── entity/Booking.java
├── tenant/
│   ├── entity/Tenant.java
│   └── guard/TenantGuard.java
├── kafka/
│   ├── outbox/OutboxEntity.java
│   ├── outbox/OutboxService.java
│   └── producer/BookingEventProducer.java
├── config/
│   ├── SecurityConfig.java
│   ├── RedisConfig.java
│   └── KafkaConfig.java
└── common/
    ├── exception/GlobalExceptionHandler.java
    └── audit/AuditableEntity.java
```

**Transaction boundaries:**
- `BookingService.createHold()` — `@Transactional(isolation = SERIALIZABLE)` + pessimistic lock
- `BookingService.confirmBooking()` — `@Transactional` + outbox write
- `HoldGcScheduler.expireHolds()` — `@Transactional` + batch update
- `OutboxService.writeEvent()` — always called within the caller's existing transaction (no `@Transactional` annotation; relies on propagation MANDATORY)

---

### 2.3 PostgreSQL Data Layer

See `docs/DATABASE-SCHEMA.md` for full DDL.

**Multi-tenancy enforcement:**
- Every table has `tenant_id UUID NOT NULL REFERENCES tenants(id)`
- Spring AOP interceptor (`TenantFilterAspect`) injects `tenant_id` into every JPA query
- Attempt to query without `tenant_id` in context throws `TenantContextMissingException`

**JSONB usage:**
- `booking.extension` — tenant intake form responses
- `resource.extension` — tenant resource metadata (e.g., medical credentials, vehicle specs)
- No core business logic reads from JSONB columns

---

### 2.4 Kafka Event Mesh

**Topics:**

| Topic | Partitions | Retention | Purpose |
|---|---|---|---|
| `tenant.bookings.lifecycle` | 12 | 7 days | All booking state transitions |
| `tenant.bookings.lifecycle.DLQ` | 3 | 30 days | Failed consumer messages |
| `tenant.notifications.outbound` | 6 | 1 day | Notification dispatch commands |
| `tenant.audit.events` | 6 | 365 days | Immutable compliance audit stream |

**Consumer groups:**

| Group | Topic | Service |
|---|---|---|
| `notification-consumers` | `tenant.bookings.lifecycle` | notification-service |
| `audit-consumers` | `tenant.bookings.lifecycle` | audit-service |

**Outbox → Kafka relay:**
- Debezium PostgreSQL connector monitors `outbox` table
- New rows captured via WAL CDC → published to Kafka with `booking_id` as message key
- Ensures at-least-once delivery with idempotent consumers on receiving end

---

### 2.5 Notification Service (`services/notification-service`)

**Responsibilities:**
- Consume `tenant.bookings.lifecycle` events
- On `CONFIRMED`: send booking confirmation (email + SMS if both identifiers present)
- On `CANCELLED`: send cancellation notice
- Idempotency: check `processed_events` table before dispatching; skip if already processed
- Dispatch via AWS SES (email) and Twilio (SMS)

**Tech stack:** Spring Boot 3.x, Spring Kafka, AWS SDK v2 (SES), Twilio Java SDK

---

### 2.6 Audit Ledger Service (`services/audit-service`)

**Responsibilities:**
- Consume `tenant.bookings.lifecycle` events
- Write immutable append-only records to `audit_log` table
- HIPAA compliance fields: `who` (user_id), `what` (state transition), `when` (timestamp), `tenant_id`, `resource_id`, `booking_id`, `ip_address` (from event payload)
- Records may never be updated or deleted
- Expose read-only query API for super-admin audit viewer

**Tech stack:** Spring Boot 3.x, Spring Kafka, PostgreSQL (append-only table with row-level security)

---

## 3. Data Flow — Slot Availability Request

```
1. Client           GET /api/v1/slots?tenantId=X&locationId=Y&resourceId=Z&date=2026-07-01
2. Gateway          Validates JWT → extracts tenant_id (must match query param)
3. SlotController   Delegates to SlotCalculatorService
4. SlotCalculator   a. Fetch resource availability schedule (DB)
                    b. Fetch branch holidays for date range (DB)
                    c. Compute operating matrix (base shift − breaks − holidays)
                    d. Fetch confirmed bookings for resource on that date (DB)
                    e. Subtract confirmed bookings + pre/post buffers from matrix
                    f. Return open intervals as slot list
5. Response         200 OK: [{startTime, endTime, durationMinutes}, ...]
```

**Performance path:** Steps 4a, 4b, 4c are cacheable (Redis, 5-min TTL). Steps 4d–4f always fresh. Target: p99 < 300ms.

---

## 4. Data Flow — Booking Checkout (with Concurrency Guard)

```
1. Client           POST /api/v1/bookings/hold {tenantId, resourceId, serviceId, slotStart}
2. BookingService   SELECT booking WHERE resource_id = ? AND slot overlaps ? FOR UPDATE
                    → Pessimistic lock acquired
3. BookingService   Verify slot is still available (double-check under lock)
4. BookingService   Insert Booking(state=PENDING_HOLD, expires_at = now + 10 min)
5. BookingService   Release lock
6. Response         201 Created: {bookingId, holdExpiry}

7. Client           POST /api/v1/bookings/{id}/confirm {extensionData}
8. BookingService   @Transactional:
                    a. Update Booking(state=CONFIRMED)
                    b. Insert OutboxEvent(topic=tenant.bookings.lifecycle, payload={...})
                    (Both writes in single ACID transaction)
9. Debezium         Reads new outbox row via WAL → publishes to Kafka
10. Consumers       NotificationConsumer + AuditConsumer process event independently
```

---

## 5. Security Architecture

See `docs/SECURITY-SPEC.md` for full specification.

**Summary:**
- All endpoints require valid JWT except `POST /auth/request-otp` and `POST /auth/verify-otp`
- JWT verified by Spring Security `JwtAuthFilter` on every request
- `TenantGuard` AOP aspect validates `tenant_id` in JWT matches `tenant_id` in request path/body
- No endpoint exposes data from multiple tenants in a single response
- OTP rate limited: 5 requests per identifier per hour (Redis counter)

---

## 6. Deployment Architecture

```
┌─────────────────────────────────────────────┐
│              Production (AWS)               │
│                                             │
│  [CloudFront CDN]                           │
│       ↓                                     │
│  [Next.js on Vercel / Amplify]              │
│       ↓ HTTPS                               │
│  [ALB (Application Load Balancer)]          │
│       ↓                                     │
│  [ECS Fargate — Spring Boot API]  (2+ tasks)│
│       ↓              ↓                      │
│  [RDS PostgreSQL]   [ElastiCache Redis]     │
│                                             │
│  [MSK (Managed Kafka)]                      │
│       ↓              ↓                      │
│  [ECS — Notif.]  [ECS — Audit]             │
│                                             │
│  [Confluent Schema Registry (or AWS Glue)]  │
└─────────────────────────────────────────────┘
```

**Local dev:** Docker Compose with PostgreSQL, Redis, Kafka (KRaft mode), Schema Registry, Debezium.
