# ADR-002 — Pessimistic Locking as Primary Concurrency Model

**Status:** Accepted
**Date:** 2026-06-18
**Deciders:** Architecture Lead (Suraj)
**adr-docs agent:** auto-captured

---

## Context

The booking checkout sequence is a classic concurrent-modification problem: multiple users may attempt to book the same slot at the same time. Without a concurrency guard, two users can both observe a slot as available, both pass the availability check, and both successfully write a booking — resulting in a double-booking.

We need a concurrency control mechanism for the `BookingService.createHold()` operation.

---

## Decision

**Primary:** Pessimistic locking via `SELECT ... FOR UPDATE` in Spring Data JPA.

**Fallback at scale:** Redis-based distributed lock (`SET NX EX`) applied when the system is deployed as multiple API instances under an ALB.

The locking sequence:
```sql
-- Executed inside @Transactional(isolation = SERIALIZABLE)
SELECT b.id FROM bookings b
WHERE b.resource_id = :resourceId
  AND b.tenant_id = :tenantId
  AND b.status IN ('PENDING_HOLD', 'CONFIRMED')
  AND (b.buffer_start, b.buffer_end) OVERLAPS (:requestedStart, :requestedEnd)
FOR UPDATE;
```

If this query returns any rows, the slot is unavailable → `409 SLOT_UNAVAILABLE`.
If it returns no rows, proceed to INSERT the new PENDING_HOLD booking.

---

## Rationale

### Why pessimistic over optimistic?

| Concern | Optimistic (version column) | Pessimistic (SELECT FOR UPDATE) |
|---|---|---|
| Correctness | Retry on collision — possible livelock under high contention | Guaranteed exactly-one winner |
| Latency | Low under no contention; high under contention (retries) | Slight overhead from lock acquisition |
| Implementation | Requires `@Version` field + retry loop | Single SQL statement |
| User experience | User may get error mid-checkout requiring retry | User gets immediate definitive answer |

Booking checkout is a low-frequency, high-value operation (not a read-heavy path). The cost of a lock is acceptable. Livelock under high contention for a single slot would be a worse UX than a clear "slot unavailable" response.

### Why SERIALIZABLE isolation?

`REPEATABLE_READ` would prevent phantom reads on most databases, but PostgreSQL's SERIALIZABLE isolation provides full predicate-level conflict detection at low overhead (SSI implementation). This is the safest choice for the overlap-check query pattern.

### Redis distributed lock (horizontal scale fallback)

When multiple API instances run behind an ALB, `SELECT FOR UPDATE` locks within a single PostgreSQL connection pool — cross-instance contention is handled by PostgreSQL itself (row-level lock at the DB layer). Redis is therefore a secondary defense, not a primary one:

- Redis lock key: `slot-lock:{tenantId}:{resourceId}:{slotStart}`
- Acquire: `SET NX EX 15` (15-second expiry)
- If not acquired: immediate `409 SLOT_UNAVAILABLE` (faster than waiting for DB lock)
- Release: after DB transaction commits

---

## Consequences

- Positive: Simple correctness guarantee; no retry logic in application code
- Positive: Exactly-one booking winner guaranteed at database level
- Negative: Lock held for duration of DB transaction (~50-100ms); throughput limited by lock contention on the same slot
- Mitigation: Lock is per-resource per slot; parallel bookings for different slots/resources are unaffected
- Mitigation: PENDING_HOLD 10-minute expiry prevents lock starvation; GC returns abandoned holds

---

## Alternatives Considered

**Optimistic locking:** Rejected for checkout path due to livelock risk under promotional events (many users booking the same popular slot simultaneously). Acceptable for low-contention admin operations.

**Application-level mutex (single-node):** Rejected — not viable in a horizontally scaled deployment.

**Kafka-based serialized booking queue:** Considered for v2 if throughput demands exceed pessimistic lock capacity. Adds significant complexity; not justified for v1 scale targets.
