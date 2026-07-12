# ADR-003 — Transactional Outbox Pattern for Kafka Event Reliability

**Status:** Accepted
**Date:** 2026-06-18
**Deciders:** Architecture Lead (Suraj)
**adr-docs agent:** auto-captured

---

## Context

When a booking is confirmed, two things must happen:
1. The `bookings` table row is updated to `status = CONFIRMED`
2. A `BookingConfirmed` event is published to Kafka for downstream consumers (notification, audit)

The naive implementation writes the DB update in one transaction and then calls Kafka's producer. This creates a **dual-write problem**: the DB can commit but Kafka publish can fail (network partition, broker restart), leaving the system in an inconsistent state where a booking is confirmed but notifications were never sent and no audit record was created.

We need a reliable mechanism to guarantee that every DB state change produces exactly one corresponding Kafka event.

---

## Decision

**Transactional Outbox Pattern:**

Every Kafka-bound event is first written to an `outbox` table within the same ACID database transaction as the business state change. A separate CDC process (Debezium PostgreSQL connector) monitors the `outbox` table via PostgreSQL WAL (Write-Ahead Log) and relays new rows to Kafka.

```
BookingService.confirmBooking() — @Transactional
├── UPDATE bookings SET status = 'CONFIRMED'
└── INSERT INTO outbox (aggregate_id, event_type, payload, topic)

Debezium (separate process):
├── Monitors outbox table via WAL CDC
└── Publishes new rows to Kafka

Result: DB update and event emission are atomically coupled
```

---

## Rationale

### Why not write directly to Kafka from the business transaction?

Kafka is not a transactional resource in the XA/2PC sense (Kafka's transactional API covers producer-to-Kafka atomicity, not DB-to-Kafka). Writing to both PostgreSQL and Kafka in the same logical unit requires distributed transaction coordination, which adds significant operational complexity and latency.

The outbox pattern delegates the Kafka write to a separate reliable process (Debezium), keeping the business transaction simple and fast.

### Failure modes handled by outbox pattern

| Failure scenario | Dual-write behavior | Outbox behavior |
|---|---|---|
| DB commits, Kafka unreachable | Event lost permanently | Outbox row persists; Debezium retries when Kafka recovers |
| DB rollback | Kafka event already sent | No event; DB in correct state; no outbox row written |
| Debezium crashes mid-relay | Event may be lost | Debezium resumes from last WAL position; re-relays un-acked rows |
| Kafka duplicates on retry | Consumer may double-process | Consumer idempotency check (see KAFKA-SPEC.md) |

### Why Debezium over a Spring-scheduled outbox poller?

| Approach | Latency | Reliability | Complexity |
|---|---|---|---|
| Spring `@Scheduled` poller | 1-60 second polling delay | Risk of missed rows under high load | Simple — no external process |
| Debezium CDC (WAL-based) | Near-real-time (< 1 second) | WAL-based; no polling; no missed rows | Moderate — requires Kafka Connect deployment |

Debezium is preferred for production. The Spring poller is acceptable for local dev (simpler setup) and can be used as a fallback.

---

## Consequences

- Positive: Atomic coupling of DB state and Kafka event — no dual-write failure
- Positive: Business transaction remains lightweight (single DB transaction, no Kafka producer call)
- Positive: Debezium WAL-based relay provides near-real-time event delivery
- Negative: Requires Debezium deployment (Kafka Connect cluster) — adds operational surface area
- Negative: Outbox table grows until cleanup job runs; must monitor size
- Mitigation: Cleanup job deletes PUBLISHED outbox rows older than 24 hours
- Mitigation: Local dev uses Spring-scheduled poller instead of Debezium (simpler docker-compose)

---

## Alternatives Considered

**Direct Kafka producer in transaction:** Rejected — dual-write failure risk, as described above.

**Saga pattern (choreography):** Considered for v2 if cross-service transactions are needed. Overkill for v1 where the only event producer is the booking service.

**Change Data Capture on `bookings` table directly:** Rejected — would expose all booking field changes to Kafka, including internal fields. Outbox pattern gives explicit control over event shape and schema.
