# API Specification
## Multi-Tenant Omni-Industry Scheduling Framework

**Version:** 1.0.0
**Base URL:** `https://api.scheduler.io/api/v1`
**Auth:** Bearer JWT in `Authorization` header (except OTP endpoints)
**Content-Type:** `application/json`

---

## Conventions

- All timestamps in ISO 8601 UTC: `2026-07-01T09:00:00Z`
- All IDs are UUIDs v4
- Paginated endpoints accept `?page=0&size=20&sort=createdAt,desc`
- Errors follow: `{ "code": "ERROR_CODE", "message": "Human message", "field": "optional" }`
- `tenant_id` claim in JWT is always cross-checked against path/body `tenantId`; mismatch → `403 TENANT_MISMATCH`

---

## 1. Authentication Endpoints

### POST `/auth/request-otp`
Dispatch OTP or magic link based on identifier type.
**Auth:** None

**Request:**
```json
{
  "identifier": "suraj@example.com",   // email or E.164 phone: +14155550123
  "tenantSlug": "metro-clinic"
}
```

**Response 202:**
```json
{
  "channel": "EMAIL",
  "maskedIdentifier": "s***@example.com",
  "expiresInSeconds": 300
}
```

**Errors:**
- `400 INVALID_IDENTIFIER` — not a valid email or phone
- `429 OTP_RATE_LIMIT_EXCEEDED` — > 5 requests/hr for this identifier

---

### POST `/auth/verify-otp`
Verify OTP and receive JWT.
**Auth:** None

**Request:**
```json
{
  "identifier": "suraj@example.com",
  "otp": "A4X9Z2",
  "tenantSlug": "metro-clinic"
}
```

**Response 200:**
```json
{
  "accessToken": "eyJhbGci...",
  "tokenType": "Bearer",
  "expiresIn": 3600,
  "claims": {
    "tenantId": "uuid",
    "userId": "uuid",
    "roleClaims": ["customer"]
  }
}
```

**Errors:**
- `401 OTP_INVALID` — wrong code
- `401 OTP_EXPIRED` — past 5-minute TTL
- `401 OTP_ALREADY_USED` — already consumed

---

## 2. Location Endpoints

### GET `/tenants/{tenantId}/locations`
List all active locations for a tenant.
**Auth:** Required

**Response 200:**
```json
{
  "content": [
    {
      "id": "uuid",
      "name": "Downtown Clinic",
      "address": { "line1": "...", "city": "...", "postalCode": "...", "countryCode": "US" },
      "coordinates": { "latitude": 33.749, "longitude": -84.388 },
      "timezone": "America/New_York",
      "status": "active"
    }
  ],
  "totalElements": 1,
  "page": 0,
  "size": 20
}
```

---

### POST `/tenants/{tenantId}/locations`
Create a new location branch.
**Auth:** Required — role: ADMIN

**Request:**
```json
{
  "name": "Downtown Clinic",
  "addressLine1": "123 Peachtree St NE",
  "city": "Atlanta",
  "state": "GA",
  "postalCode": "30303",
  "countryCode": "US",
  "latitude": 33.749,
  "longitude": -84.388,
  "timezone": "America/New_York"
}
```

**Response 201:** Location object

---

### PUT `/tenants/{tenantId}/locations/{locationId}`
Update location details.
**Auth:** Required — role: ADMIN

### DELETE `/tenants/{tenantId}/locations/{locationId}`
Soft-delete (sets status = 'inactive').
**Auth:** Required — role: ADMIN

---

## 3. Resource Endpoints

### GET `/tenants/{tenantId}/locations/{locationId}/resources`
List resources at a location.
**Auth:** Required

### POST `/tenants/{tenantId}/locations/{locationId}/resources`
Register a resource at a location.
**Auth:** Required — role: ADMIN

**Request:**
```json
{
  "name": "Dr. Sarah Kim",
  "resourceType": "STAFF",
  "extension": {
    "specialization": "Cardiology",
    "licenseNumber": "GA-MD-12345"
  },
  "schedule": [
    { "dayOfWeek": 1, "startTime": "09:00", "endTime": "17:00" },
    { "dayOfWeek": 2, "startTime": "09:00", "endTime": "17:00" },
    { "dayOfWeek": 3, "startTime": "09:00", "endTime": "13:00" }
  ],
  "breaks": [
    { "dayOfWeek": 1, "breakStart": "12:00", "breakEnd": "13:00", "label": "Lunch" }
  ]
}
```

**Response 201:** Resource object with `id`

---

## 4. Service Type Endpoints

### GET `/tenants/{tenantId}/service-types`
List service types for a tenant.
**Auth:** Required

### POST `/tenants/{tenantId}/service-types`
Create a service type.
**Auth:** Required — role: ADMIN

**Request:**
```json
{
  "name": "Initial Consultation",
  "description": "First-time patient consultation",
  "durationMinutes": 60,
  "bufferBeforeMin": 0,
  "bufferAfterMin": 15,
  "allowedResourceTypes": ["STAFF"],
  "intakeSchema": {
    "type": "object",
    "properties": {
      "chiefComplaint": { "type": "string", "title": "Chief Complaint" },
      "insuranceProvider": { "type": "string", "title": "Insurance Provider" }
    },
    "required": ["chiefComplaint"]
  }
}
```

---

## 5. Slot Availability Endpoint

### GET `/tenants/{tenantId}/slots`
Compute available slots on-demand.
**Auth:** Required

**Query params:**
- `locationId` (required)
- `resourceId` (required)
- `serviceTypeId` (required)
- `date` (required) — `YYYY-MM-DD`
- `rangeEndDate` (optional) — `YYYY-MM-DD`; defaults to `date`

**Response 200:**
```json
{
  "resourceId": "uuid",
  "serviceTypeId": "uuid",
  "date": "2026-07-01",
  "timezone": "America/New_York",
  "slots": [
    {
      "startTime": "2026-07-01T09:00:00Z",
      "endTime": "2026-07-01T10:00:00Z",
      "durationMinutes": 60,
      "available": true
    },
    {
      "startTime": "2026-07-01T10:15:00Z",
      "endTime": "2026-07-01T11:15:00Z",
      "durationMinutes": 60,
      "available": true
    }
  ],
  "computedAt": "2026-07-01T08:00:00Z"
}
```

**Performance SLA:** p99 < 300ms (NFR-1.2)
**Caching:** Resource schedule and holidays cached in Redis for 5 minutes. Booking data always fresh.

---

## 6. Booking Endpoints

### POST `/tenants/{tenantId}/bookings/hold`
Reserve a slot (PENDING_HOLD for 10 minutes).
**Auth:** Required

**Request:**
```json
{
  "resourceId": "uuid",
  "serviceTypeId": "uuid",
  "slotStart": "2026-07-01T09:00:00Z"
}
```

**Response 201:**
```json
{
  "bookingId": "uuid",
  "status": "PENDING_HOLD",
  "slotStart": "2026-07-01T09:00:00Z",
  "slotEnd": "2026-07-01T10:00:00Z",
  "holdExpiresAt": "2026-07-01T08:10:00Z"
}
```

**Errors:**
- `409 SLOT_UNAVAILABLE` — slot taken under pessimistic lock
- `409 HOLD_ALREADY_EXISTS` — user already holds a slot for this resource+time
- `422 SLOT_OUTSIDE_OPERATING_HOURS` — slot not in resource's operating matrix

---

### POST `/tenants/{tenantId}/bookings/{bookingId}/confirm`
Confirm hold → CONFIRMED state + emit Kafka event.
**Auth:** Required (must be the booking owner)

**Request:**
```json
{
  "extensionData": {
    "chiefComplaint": "Back pain",
    "insuranceProvider": "BlueCross"
  }
}
```

**Response 200:**
```json
{
  "bookingId": "uuid",
  "status": "CONFIRMED",
  "confirmationCode": "MC-2026-00421",
  "slotStart": "2026-07-01T09:00:00Z",
  "slotEnd": "2026-07-01T10:00:00Z"
}
```

**Errors:**
- `404 BOOKING_NOT_FOUND`
- `409 HOLD_EXPIRED` — hold window elapsed before confirmation
- `422 EXTENSION_SCHEMA_VIOLATION` — extensionData fails tenant's intake schema

---

### POST `/tenants/{tenantId}/bookings/{bookingId}/cancel`
Cancel a confirmed booking.
**Auth:** Required (booking owner or ADMIN)

**Request:**
```json
{
  "reason": "Patient request"
}
```

**Response 200:**
```json
{
  "bookingId": "uuid",
  "status": "CANCELLED",
  "cancelledAt": "2026-06-30T15:00:00Z"
}
```

---

### GET `/tenants/{tenantId}/bookings`
List bookings for authenticated user (or all if ADMIN).
**Auth:** Required

**Query params:** `status`, `resourceId`, `locationId`, `dateFrom`, `dateTo`, `page`, `size`

---

### GET `/tenants/{tenantId}/bookings/{bookingId}`
Get single booking detail.
**Auth:** Required (owner or ADMIN)

---

## 7. Admin — Branch Holiday Endpoints

### GET `/tenants/{tenantId}/locations/{locationId}/holidays`
### POST `/tenants/{tenantId}/locations/{locationId}/holidays`

**Request:**
```json
{
  "holidayDate": "2026-12-25",
  "name": "Christmas Day",
  "isRecurring": true
}
```

---

## 8. Health & Meta

### GET `/health`
**Auth:** None
**Response 200:** `{ "status": "UP", "version": "1.0.0" }`

### GET `/health/ready`
**Auth:** None — checks DB, Redis, Kafka connectivity

---

## 9. Error Code Reference

| Code | HTTP | Meaning |
|---|---|---|
| `OTP_RATE_LIMIT_EXCEEDED` | 429 | Too many OTP requests |
| `OTP_INVALID` | 401 | Wrong OTP code |
| `OTP_EXPIRED` | 401 | OTP past TTL |
| `OTP_ALREADY_USED` | 401 | OTP already consumed |
| `TENANT_MISMATCH` | 403 | JWT tenant_id ≠ path tenantId |
| `INSUFFICIENT_ROLE` | 403 | Role claim does not permit action |
| `SLOT_UNAVAILABLE` | 409 | Slot taken under pessimistic lock |
| `HOLD_EXPIRED` | 409 | PENDING_HOLD expired before confirm |
| `SLOT_OUTSIDE_OPERATING_HOURS` | 422 | Requested slot outside operating matrix |
| `EXTENSION_SCHEMA_VIOLATION` | 422 | extensionData fails JSON Schema validation |
| `RESOURCE_NOT_FOUND` | 404 | Resource ID not found for tenant |
| `BOOKING_NOT_FOUND` | 404 | Booking ID not found for tenant |
