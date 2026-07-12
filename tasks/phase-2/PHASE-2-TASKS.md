# Phase 2 — Core Booking Engine
**Duration:** Weeks 4–7
**Milestone:** Full booking flow works end-to-end; concurrency guard verified by automated tests

---

## P2-T01 — Location and Branch Admin APIs
**Tags:** [CONFIG]
**Priority:** P1
**Estimate:** 1 day
**Agent:** Coder agent
**Depends on:** P1-T05, P1-T04

### Specification
Implement CRUD for `Location` entity. Admin-only write operations; customers can read.

**Endpoints:**
- `GET /api/v1/tenants/{tenantId}/locations` — list (paginated)
- `POST /api/v1/tenants/{tenantId}/locations` — create (role: ADMIN)
- `GET /api/v1/tenants/{tenantId}/locations/{locationId}` — get single
- `PUT /api/v1/tenants/{tenantId}/locations/{locationId}` — update (role: ADMIN)
- `DELETE /api/v1/tenants/{tenantId}/locations/{locationId}` — soft-delete (role: ADMIN)

**Validation rules:**
- `timezone` must be a valid IANA timezone name (validate against `ZoneId.of()`)
- `latitude` range: -90.0 to 90.0
- `longitude` range: -180.0 to 180.0
- `countryCode` must be ISO 3166-1 alpha-2 (2 uppercase letters)

**Acceptance criteria:**
- [ ] Tenant A cannot read Tenant B's locations (tenant guard enforced)
- [ ] Invalid timezone returns `400` with field error
- [ ] Soft-delete sets `status = inactive`; inactive locations excluded from list by default
- [ ] All fields validated per rules above

---

## P2-T02 — Resource Registration and Schedule API
**Tags:** [CONFIG]
**Priority:** P1
**Estimate:** 1.5 days
**Agent:** Coder agent
**Depends on:** P2-T01

### Specification
Implement Resource CRUD plus nested `resource_schedules` and `resource_breaks` management.

**Endpoints:**
- `GET /api/v1/tenants/{tenantId}/locations/{locationId}/resources`
- `POST /api/v1/tenants/{tenantId}/locations/{locationId}/resources`
- `GET /api/v1/tenants/{tenantId}/locations/{locationId}/resources/{resourceId}`
- `PUT /api/v1/tenants/{tenantId}/locations/{locationId}/resources/{resourceId}`
- `PUT /api/v1/tenants/{tenantId}/locations/{locationId}/resources/{resourceId}/schedule` — replace schedule
- `PUT /api/v1/tenants/{tenantId}/locations/{locationId}/resources/{resourceId}/breaks` — replace breaks

**Schedule validation:**
- No overlapping schedule windows on same `day_of_week`
- `end_time > start_time` enforced
- At least 1 schedule entry required for a resource to be bookable

**JSONB extension:**
- `extension` field accepted on create/update; stored as-is
- No validation of extension content at this layer (tenant-defined)

**Acceptance criteria:**
- [ ] Resource created with schedule and breaks in single request
- [ ] Overlapping schedule windows return `422`
- [ ] Resource's `extension` JSONB stored and returned correctly
- [ ] Schedule replace operation replaces all existing entries atomically

---

## P2-T03 — Service Type CRUD and Intake Schema Storage
**Tags:** [CONFIG]
**Priority:** P1
**Estimate:** 1 day
**Agent:** Coder agent
**Depends on:** P2-T01

### Specification
Implement ServiceType CRUD. The `intakeSchema` field is a JSON Schema document defining the custom intake form for this service.

**Endpoints:**
- `GET /api/v1/tenants/{tenantId}/service-types`
- `POST /api/v1/tenants/{tenantId}/service-types`
- `GET /api/v1/tenants/{tenantId}/service-types/{serviceTypeId}`
- `PUT /api/v1/tenants/{tenantId}/service-types/{serviceTypeId}`
- `DELETE /api/v1/tenants/{tenantId}/service-types/{serviceTypeId}` — soft-delete

**Intake schema validation:**
- `intakeSchema` must be valid JSON Schema draft-07 (validate structure, not semantics)
- `durationMinutes` must be a positive integer ≥ 5 and ≤ 480
- `bufferBeforeMin` and `bufferAfterMin` must be ≥ 0 and ≤ 120

**Acceptance criteria:**
- [ ] Invalid JSON Schema in `intakeSchema` returns `422 INVALID_JSON_SCHEMA`
- [ ] Service type returned includes full `intakeSchema` for use by Next.js form builder
- [ ] Soft-delete prevents new bookings; does not affect existing confirmed bookings

---

## P2-T04 — Branch Holiday Management API
**Tags:** [CONFIG]
**Priority:** P1
**Estimate:** 0.5 days
**Agent:** Coder agent
**Depends on:** P2-T01

### Specification
CRUD for `branch_holidays` — dates when a location is closed.

**Endpoints:**
- `GET /api/v1/tenants/{tenantId}/locations/{locationId}/holidays?year=2026`
- `POST /api/v1/tenants/{tenantId}/locations/{locationId}/holidays`
- `DELETE /api/v1/tenants/{tenantId}/locations/{locationId}/holidays/{holidayId}`

**Holiday date validation:**
- `holidayDate` must be a valid future date (cannot add holidays in the past)
- Duplicate `(locationId, holidayDate)` returns `409 HOLIDAY_ALREADY_EXISTS`

**Acceptance criteria:**
- [ ] Holidays for a location excluded from operating matrix (tested in P2-T05)
- [ ] Recurring holidays (isRecurring = true) appear for same date in future years
- [ ] Past holiday dates rejected with `422`

---

## P2-T05 — SlotCalculatorService — Operating Matrix
**Tags:** [SLOT]
**Priority:** P0
**Estimate:** 2 days
**Agent:** Coder agent (domain abstraction guard active)
**Depends on:** P2-T02, P2-T04

### Specification
Implement the first half of `SlotCalculatorService`: computing the operating matrix (available time windows before booking subtraction).

**Operating matrix algorithm:**
```
Input: resourceId, date (LocalDate), tenantId

1. Load resource_schedules for resource where day_of_week = date.dayOfWeek
   → Get base shift windows [startTime, endTime] for that day
2. Load resource_breaks for same day
   → Subtract break windows from shift windows
3. Load branch_holidays for location where holiday_date = date
   → If holiday exists: return empty matrix
4. Convert shift start/end to UTC TIMESTAMPTZ using location timezone
5. Return list of TimeWindow{start: TIMESTAMPTZ, end: TIMESTAMPTZ}
```

**Key rules:**
- All time arithmetic performed in UTC after timezone conversion
- Break subtraction may split a single shift window into multiple windows
- If a break exactly aligns with shift start/end, the window is simply shortened
- Multiple shifts on same day are supported (split shifts)

**Unit tests (no DB required — pure logic):**
- Single shift, no breaks → returns single window
- Single shift with lunch break → returns two windows
- Holiday date → returns empty list
- Multiple shifts on same day → returns multiple windows
- Break wider than shift → that shift window is eliminated

**Acceptance criteria:**
- [ ] All 5 unit test scenarios pass
- [ ] Operating matrix correctly reflects branch timezone
- [ ] No industry-specific terminology anywhere in this service
- [ ] Method is pure (no side effects, no DB calls — inputs injected from outside)

---

## P2-T06 — SlotCalculatorService — Booking Subtraction and Buffer
**Tags:** [SLOT]
**Priority:** P0
**Estimate:** 1.5 days
**Agent:** Coder agent
**Depends on:** P2-T05

### Specification
Implement the second half of `SlotCalculatorService`: subtracting confirmed bookings and buffer windows from the operating matrix to produce the final available slots list.

**Slot generation algorithm:**
```
Input: operatingMatrix (from P2-T05), resourceId, serviceTypeId, date, tenantId

1. Load service type (durationMinutes, bufferBeforeMins, bufferAfterMins)
2. Load all bookings for resource on date WHERE status IN ('PENDING_HOLD', 'CONFIRMED')
   → Each booking occupies [buffer_start, buffer_end] (inclusive of buffer windows)
3. For each operating window in matrix:
   a. Walk the window in steps of durationMinutes
   b. For each candidate slot [candidateStart, candidateStart + duration]:
      - Add buffer_before and buffer_after to get [effectiveStart, effectiveEnd]
      - Check if [effectiveStart, effectiveEnd] overlaps any existing booking's [buffer_start, buffer_end]
      - If no overlap AND effectiveEnd ≤ window end: add to available slots
4. Return list of AvailableSlot{startTime, endTime, durationMinutes}
```

**Slot granularity:** Slots generated starting on durationMinutes boundaries (e.g., 60-min service starts at :00 only, not :15 or :30). Configurable per service type in v2.

**Unit tests:**
- No existing bookings → all windows available as slots
- One booking in middle of window → splits into slots before and after
- Buffer causes adjacent slot to be unavailable → adjacent slot excluded
- PENDING_HOLD booking blocks same as CONFIRMED
- Date with no operating matrix (holiday) → empty slot list

**Acceptance criteria:**
- [ ] All 5 unit test scenarios pass
- [ ] Buffer windows correctly applied to both new and existing bookings
- [ ] PENDING_HOLD bookings treated as blocking (same as CONFIRMED)
- [ ] Slot list returned as transient objects — nothing written to DB

---

## P2-T07 — Slot Availability REST Endpoint
**Tags:** [SLOT]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Coder agent
**Depends on:** P2-T05, P2-T06

### Specification
Expose `SlotCalculatorService` through `SlotController`.

**Endpoint:** `GET /api/v1/tenants/{tenantId}/slots`

**Query parameters:**
- `locationId` (required)
- `resourceId` (required)
- `serviceTypeId` (required)
- `date` (required, format: `YYYY-MM-DD`)
- `rangeEndDate` (optional, default = `date`) — max 7-day range

**Response:** JSON as specified in `docs/API-SPEC.md` section 5.

**Error handling:**
- Resource not found for tenant → `404 RESOURCE_NOT_FOUND`
- Service type not found for tenant → `404 SERVICE_TYPE_NOT_FOUND`
- Date in the past → `422 DATE_IN_PAST`
- Date range > 7 days → `422 DATE_RANGE_TOO_LARGE`

**Acceptance criteria:**
- [ ] Endpoint returns correct available slots for a simple resource with no bookings
- [ ] Endpoint correctly excludes booked slots
- [ ] Tenant isolation: resource from Tenant B returns `404` when queried by Tenant A JWT
- [ ] Response time p99 < 300ms for a single day (measured with MockMvc + timing assert)

---

## P2-T08 — Redis Caching for Schedule and Holidays
**Tags:** [SLOT]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Coder agent
**Depends on:** P2-T07

### Specification
Add Redis caching to the static portions of the slot calculation to meet the 300ms p99 NFR.

**Cache targets (TTL: 5 minutes):**
- `schedule:{resourceId}:{dayOfWeek}` → `List<ResourceSchedule>`
- `breaks:{resourceId}:{dayOfWeek}` → `List<ResourceBreak>`
- `holidays:{locationId}:{year}:{month}` → `List<BranchHoliday>`

**Cache invalidation triggers:**
- `PUT /resources/{id}/schedule` → evict `schedule:{resourceId}:*`
- `PUT /resources/{id}/breaks` → evict `breaks:{resourceId}:*`
- `POST/DELETE /locations/{id}/holidays` → evict `holidays:{locationId}:*`

**Booking data (NOT cached):** Always fetched fresh from DB to prevent stale availability.

**Implementation:** Spring `@Cacheable` / `@CacheEvict` with Redis as the cache store.

**Acceptance criteria:**
- [ ] Cache hit rate > 80% in repeated slot queries for same resource/day
- [ ] Cache eviction triggered correctly when schedule/holiday is updated
- [ ] Slot calculation still correct after cache eviction (re-fetches from DB)
- [ ] Redis unavailability falls back to DB (no 500 errors if Redis is down)

---

## P2-T09 — BookingService — PENDING_HOLD with Pessimistic Lock
**Tags:** [CONCURRENCY]
**Priority:** P0
**Estimate:** 2 days
**Agent:** Coder agent + Test-gen agent (paired)
**Depends on:** P2-T06, P1-T04

### Specification
Implement the hold creation logic in `BookingService` with full concurrency protection.

**BookingService.createHold(CreateHoldRequest, tenantId):**
```
@Transactional(isolation = SERIALIZABLE)
1. Validate request (resource exists, service exists, both belong to tenantId)
2. Compute slot end = slotStart + service.durationMinutes
3. Compute bufferStart = slotStart - bufferBefore, bufferEnd = slotEnd + bufferAfter
4. SELECT booking WHERE resource_id = :resourceId AND tenant_id = :tenantId
   AND status IN ('PENDING_HOLD', 'CONFIRMED')
   AND (buffer_start, buffer_end) OVERLAPS (:bufferStart, :bufferEnd)
   FOR UPDATE
   → If rows returned: throw SlotUnavailableException (409)
5. Verify slot is within operating matrix (call SlotCalculatorService)
   → If outside: throw SlotOutsideOperatingHoursException (422)
6. INSERT Booking(status=PENDING_HOLD, holdExpiresAt=now+10min, bufferStart, bufferEnd, ...)
7. Return HoldResponse{bookingId, holdExpiresAt}
```

**Concurrent test scenario (must be part of this task):**
- Spin up 10 threads simultaneously calling `createHold()` for the same slot
- Exactly 1 thread must succeed; 9 must receive `409 SLOT_UNAVAILABLE`
- No deadlocks, no data corruption

**Acceptance criteria:**
- [ ] Single-client hold creation works correctly
- [ ] Concurrent 10-thread test: exactly 1 success, 9 failures
- [ ] `PENDING_HOLD` row has correct `hold_expires_at` (now + 10 min)
- [ ] `buffer_start` and `buffer_end` correctly computed and stored
- [ ] Tenant guard prevents booking resource from another tenant

---

## P2-T10 — BookingService — Confirm Booking and JSONB Extension Write
**Tags:** [CONCURRENCY]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Coder agent
**Depends on:** P2-T09

### Specification
Implement booking confirmation: state transition from PENDING_HOLD → CONFIRMED + extension data write.

**BookingService.confirmBooking(bookingId, extensionData, tenantId):**
```
@Transactional
1. Load Booking WHERE id = :bookingId AND tenant_id = :tenantId
   → If not found: throw BookingNotFoundException (404)
2. Verify status = PENDING_HOLD
   → If CONFIRMED: throw BookingAlreadyConfirmedException (409)
   → If holdExpiresAt < now: throw HoldExpiredException (409)
3. Validate extensionData against serviceType.intakeSchema (JSON Schema validation)
   → If fails: throw ExtensionSchemaViolationException (422)
4. UPDATE Booking(status=CONFIRMED, extension=extensionData, holdExpiresAt=null)
   (Outbox write added in Phase 3 — P3-T06)
5. Return ConfirmationResponse{bookingId, status, confirmationCode, slotStart, slotEnd}
```

**Confirmation code generation:** `{tenantSlug}-{YYYY}-{5-digit-sequence}` (e.g., `MC-2026-00421`). Sequence stored in Redis, auto-incremented per tenant.

**JSON Schema validation:** Use `networknt/json-schema-validator` library to validate `extensionData` against `serviceType.intakeSchema`.

**Acceptance criteria:**
- [ ] Confirmed booking status = CONFIRMED in DB
- [ ] Extension data stored in JSONB column as submitted
- [ ] Extension data failing schema validation returns `422` with field details
- [ ] Hold expired before confirmation returns `409 HOLD_EXPIRED`
- [ ] Confirmation code format correct and unique per tenant

---

## P2-T11 — BookingService — Cancel Booking
**Tags:** [CONCURRENCY]
**Priority:** P1
**Estimate:** 0.5 days
**Agent:** Coder agent
**Depends on:** P2-T10

### Specification
Implement booking cancellation. Both the booking owner and an ADMIN can cancel.

**BookingService.cancelBooking(bookingId, reason, actorUserId, tenantId):**
```
@Transactional
1. Load Booking WHERE id = :bookingId AND tenant_id = :tenantId
2. Verify caller is owner OR has ADMIN role
   → If neither: 403 INSUFFICIENT_ROLE
3. Verify status = CONFIRMED (cannot cancel PENDING_HOLD — let GC handle it)
   → If PENDING_HOLD: throw InvalidStateTransitionException (409)
   → If already CANCELLED: throw AlreadyCancelledException (409)
4. UPDATE Booking(status=CANCELLED, cancelledAt=now, cancelledBy=actorUserId, cancellationReason=reason)
   (Outbox write added in Phase 3)
5. Return CancellationResponse{bookingId, status, cancelledAt}
```

**Acceptance criteria:**
- [ ] Booking owner can cancel their own confirmed booking
- [ ] ADMIN can cancel any booking in their tenant
- [ ] Non-owner non-admin gets `403`
- [ ] Cancelled booking slot immediately available for new bookings (no hold period)

---

## P2-T12 — HoldGcScheduler — Expired Hold Cleanup
**Tags:** [CONCURRENCY]
**Priority:** P0
**Estimate:** 1 day
**Agent:** Coder agent
**Depends on:** P2-T09

### Specification
Implement the background GC job that reverts expired PENDING_HOLD bookings to AVAILABLE (i.e., deletes them).

**HoldGcScheduler:**
```java
@Component
public class HoldGcScheduler {
    @Scheduled(fixedDelay = 60_000)  // Run every 60 seconds
    @Transactional
    public void expireStaleHolds() {
        // Find all PENDING_HOLD bookings where holdExpiresAt < NOW
        // Batch delete them (or update status to EXPIRED)
        // Log count of expired holds per execution
    }
}
```

**Design decisions:**
- Delete vs. status=EXPIRED: Delete preferred (removes rows from index, frees slot immediately). Status=EXPIRED acceptable if audit trail of abandoned holds is required (configurable).
- Batch size: Process max 500 rows per run to avoid long-held transactions
- No lock contention: Uses `DELETE WHERE status = 'PENDING_HOLD' AND hold_expires_at < now()` — no SELECT FOR UPDATE needed (deleting vs. contending for the row)

**Acceptance criteria:**
- [ ] Scheduler runs every 60 seconds
- [ ] Expired holds deleted within 61 seconds of expiry
- [ ] Slot available for new bookings immediately after GC run
- [ ] Scheduler does not cause deadlocks under concurrent booking load
- [ ] 48-hour soak test: no deadlocks, no accumulation of expired holds

---

## P2-T13 — Booking Concurrency Integration Tests
**Tags:** [TEST] [CONCURRENCY]
**Priority:** P0
**Estimate:** 1.5 days
**Agent:** Test-gen agent
**Depends on:** P2-T09, P2-T10, P2-T12

### Specification
Comprehensive concurrency and edge-case test suite using Testcontainers.

**Test scenarios:**

1. **10 simultaneous holds — same slot**
   - 10 threads call `createHold()` for identical resourceId + slotStart
   - Assert: exactly 1 success, 9 receive `409`
   - Assert: DB contains exactly 1 PENDING_HOLD booking

2. **Hold expiry and re-booking**
   - Create hold; wait for expiry (set holdExpiresAt = now() - 1 second in test setup)
   - Run GC manually; assert hold deleted
   - Create new hold for same slot; assert success

3. **Confirm after expiry**
   - Create hold; force expiry; attempt confirm
   - Assert: `409 HOLD_EXPIRED`

4. **Adjacent slot buffer collision**
   - Create and confirm booking from 09:00 to 10:00 with 15-min post-buffer
   - Attempt to book 10:00 slot → assert `409` (buffer extends to 10:15)
   - Attempt to book 10:15 slot → assert success

5. **Cancel and re-book**
   - Confirm booking; cancel it; attempt new booking for same slot
   - Assert: new booking succeeds

6. **Concurrent hold + immediate confirm race**
   - Thread A: hold → sleep 100ms → confirm
   - Thread B: tries to hold same slot after Thread A holds but before confirm
   - Assert Thread B gets `409`

**Acceptance criteria:**
- [ ] All 6 scenarios pass in Testcontainers environment
- [ ] Tests run in under 60 seconds
- [ ] No test isolation issues (each test uses isolated booking data)

---

## P2-T14 — Next.js Booking Flow — Slot Calendar and Checkout
**Tags:** [UI]
**Priority:** P1
**Estimate:** 2 days
**Agent:** Coder agent
**Depends on:** P2-T07, P2-T10

### Specification
Build the customer-facing booking UI pages.

**Pages:**
- `app/(booking)/[tenantSlug]/page.tsx` — Location selector with map/list view
- `app/(booking)/[tenantSlug]/[locationId]/page.tsx` — Resource and service selector
- `app/(booking)/[tenantSlug]/[locationId]/[resourceId]/page.tsx` — Slot calendar + checkout form

**Slot calendar component:**
- Shows a week view of available slots
- Slots fetched via Tanstack Query (polls `/api/v1/slots` on date change)
- Available slots shown as clickable cards; full slots grayed out
- On slot selection: POST to `/bookings/hold`; navigate to confirmation step

**Checkout form:**
- Renders `react-jsonschema-form` using `serviceType.intakeSchema`
- POST to `/bookings/{id}/confirm` with form data
- Shows hold expiry countdown (10-minute timer)
- On success: redirect to `/booking/confirmation/{bookingId}`

**Acceptance criteria:**
- [ ] Slot calendar renders available slots from API
- [ ] Slot selection triggers hold creation; failed hold shows error inline
- [ ] Custom intake form renders correctly for two different tenant schemas
- [ ] Hold expiry countdown visible; on expiry shows "session expired, please restart"
- [ ] Booking confirmation page shows confirmation code and slot details

---

## P2-T15 — Next.js Admin Portal — Locations, Resources, Services
**Tags:** [UI]
**Priority:** P1
**Estimate:** 2 days
**Agent:** Coder agent
**Depends on:** P2-T01, P2-T02, P2-T03

### Specification
Build the admin portal pages for configuration management.

**Pages:**
- `app/(admin)/dashboard/page.tsx` — Overview: booking stats, resource utilization
- `app/(admin)/locations/page.tsx` — Location list + create/edit/delete
- `app/(admin)/resources/page.tsx` — Resource list + create/edit with schedule builder
- `app/(admin)/services/page.tsx` — Service type list + create/edit

**Components:**
- `LocationForm` — address fields, timezone selector (react-select with IANA zones), lat/lng
- `ResourceForm` — name, type, JSONB extension key-value editor, schedule grid (day × time)
- `ServiceTypeForm` — name, duration slider, buffer fields, allowed resource types multi-select
- `ScheduleGrid` — visual weekly grid where admin drags to set availability hours

**Access control:**
- Middleware checks `roleClaims: admin` for all `/admin/*` routes
- Non-admin users redirected to booking home

**Acceptance criteria:**
- [ ] CRUD operations for all three entities work via API
- [ ] Schedule grid visually shows availability and allows hour-block editing
- [ ] Admin cannot access another tenant's data (tenant guard verified by 403 on mismatched JWT)

---

## P2-T16 — Next.js JSON Schema Form Builder
**Tags:** [UI]
**Priority:** P1
**Estimate:** 1.5 days
**Agent:** Coder agent
**Depends on:** P2-T03

### Specification
Implement the drag-and-drop intake form builder for tenant admins.

**Page:** `app/(admin)/forms/[serviceTypeId]/page.tsx`

**Capabilities:**
- Drag-and-drop field types onto a canvas: Short text, Long text, Number, Date, Checkbox, Select (with options)
- Each field has: label, fieldKey (camelCase auto-generated), required toggle, placeholder
- Canvas renders live preview of the form using `react-jsonschema-form`
- "Save schema" serializes the canvas to JSON Schema draft-07 and PUTs to the service type

**JSON Schema output example:**
```json
{
  "type": "object",
  "title": "Patient Intake",
  "properties": {
    "chiefComplaint": { "type": "string", "title": "Chief Complaint", "minLength": 1 },
    "insuranceProvider": { "type": "string", "title": "Insurance Provider" }
  },
  "required": ["chiefComplaint"]
}
```

**Acceptance criteria:**
- [ ] Admin can add, reorder, and remove fields via drag-and-drop
- [ ] Live preview updates in real time as fields are added
- [ ] Saved schema validates as JSON Schema draft-07
- [ ] Schema saved to `service_types.intake_schema` via API
- [ ] Saved schema immediately used by customer booking form (no redeploy)
