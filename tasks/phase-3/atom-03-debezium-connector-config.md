---
description: Debezium PostgreSQL connector config wiring the outbox table to Kafka topics via CDC and the EventRouter transform
---

# ATOM-KAFKA-003: Debezium Connector Configuration

**Status**: ✅ Complete
**Feature**: kafka-event-mesh
**Phase**: 3 (Kafka)
**Tags**: [KAFKA]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-KAFKA-001 (outbox table + WAL config), ATOM-PHASE1-003 (Kafka Connect running in Docker)
**Blocks**: ATOM-KAFKA-011
**PR**: TBD

---

## Overview

This atom configures the Debezium PostgreSQL connector that acts as the CDC bridge between the `outbox` table and Kafka. Debezium watches PostgreSQL's Write-Ahead Log for INSERT events on `public.outbox` and, using the EventRouter Single Message Transform, routes each row to the Kafka topic named in the `topic` column using the `partition_key` column as the message key. This is the relay leg of ADR-003 — no application code writes to Kafka directly. PostgreSQL WAL must be configured to `logical` replication level before the connector is registered.

---

## User Story

```
As a System
I want Debezium to automatically relay outbox INSERT events to Kafka topics
So that business transactions never need to write to Kafka directly, eliminating dual-write failures
```

---

## Acceptance Criteria

- [ ] **AC-01**: Inserting a row into `outbox` with `status=PENDING` causes a message to appear in the specified Kafka topic within 2 seconds
- [ ] **AC-02**: Kafka message key matches the `partition_key` column value of the inserted outbox row
- [ ] **AC-03**: Kafka message is routed to the topic named in the `topic` column of the inserted outbox row
- [ ] **AC-04**: Connector status is `RUNNING` at `GET http://localhost:8083/connectors/scheduler-outbox-connector/status`
- [ ] **AC-05**: Connector survives a PostgreSQL container restart — resumes from last WAL position with no lost events
- [ ] **AC-06**: Connector survives a Kafka container restart — buffers and replays pending rows after recovery
- [ ] **AC-07**: `register-connector.sh` is idempotent — running it twice does not create a duplicate connector or return an error
- [ ] **AC-08 (Idempotency)**: Debezium restart mid-relay → row is relayed exactly once after restart, no duplicate Kafka message
- [ ] **AC-09 (Domain abstraction)**: No industry-specific terms in connector config keys, slot names, or publication names

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `DebeziumConnectorIT.java` — `shouldRelayOutboxRow_withinTwoSeconds` | `debezium-outbox-connector.json` | 🔜 Planned |
| AC-02 | `DebeziumConnectorIT.java` — `shouldSetMessageKey_toPartitionKey` | `debezium-outbox-connector.json` | 🔜 Planned |
| AC-03 | `DebeziumConnectorIT.java` — `shouldRouteToCorrectTopic` | `debezium-outbox-connector.json` | 🔜 Planned |
| AC-04 | Manual / `register-connector.sh` | `register-connector.sh` | 🔜 Planned |
| AC-05 | `DebeziumConnectorIT.java` — `shouldResumeAfterPostgresRestart` | Docker Compose config | 🔜 Planned |
| AC-07 | `register-connector.sh` idempotency test | `register-connector.sh` | 🔜 Planned |
| AC-08 | `OutboxChaosIT.java` (ATOM-KAFKA-011) — `shouldNotDuplicate_afterDebeziumRestart` | Debezium config | 🔜 Planned |

<!-- AC validation passed: TBD, 9 criteria written, 7 mapped -->

---

## Technical Design

### Architecture

Debezium runs as a Kafka Connect plugin inside the `kafka-connect` Docker container. It monitors the PostgreSQL WAL using the `pgoutput` plugin (native to PostgreSQL 10+). The `EventRouter` SMT reads the `topic`, `partition_key`, `event_type`, and `payload` columns from each captured `outbox` INSERT and produces a Kafka `ProducerRecord` with:
- topic: value of `outbox.topic`
- key: value of `outbox.partition_key`
- value: Avro-serialized payload (via `KafkaAvroSerializer` + Schema Registry)

The `tombstones.on.delete=false` setting prevents spurious tombstone messages when outbox rows are eventually purged.

### Data Flow / Sequence

```
BookingService commits @Transactional (bookings row + outbox row)
  → PostgreSQL WAL receives INSERT on public.outbox
  → Debezium captures WAL event via logical replication slot scheduler_outbox_slot
  → EventRouter SMT transforms CDC event:
      topic      = outbox.topic          ("tenant.bookings.lifecycle")
      key        = outbox.partition_key  (booking UUID string)
      value      = outbox.payload        (Avro-serialized)
  → Kafka message produced to tenant.bookings.lifecycle
  → Notification consumer + Audit consumer receive message
```

### File Structure

```
infra/kafka/
├── debezium-outbox-connector.json   ← Kafka Connect connector config
├── register-connector.sh            ← idempotent registration script
└── topics.sh                        ← topic creation (ATOM-KAFKA-004)

infra/postgres/
└── init.sql                         ← WAL config additions (wal_level=logical)

apps/api/src/test/java/com/scheduler/
└── kafka/DebeziumConnectorIT.java   ← integration tests (Testcontainers)
```

### Interface Contracts

No Java service interfaces for this atom — infrastructure config only.

```json
// Connector config shape (key fields):
{
  "name": "scheduler-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "plugin.name": "pgoutput",
    "table.include.list": "public.outbox",
    "slot.name": "scheduler_outbox_slot",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.route.by.field": "topic",
    "transforms.outbox.table.field.event.key": "partition_key",
    "value.converter": "io.confluent.connect.avro.AvroConverter"
  }
}
```

### Design Rationale

- **ADR-003 (Transactional Outbox)**: Debezium is the CDC relay agent — it reads from the outbox table after the DB transaction commits, guaranteeing at-least-once delivery to Kafka without any dual-write from application code.
- **Why `pgoutput` (not `wal2json`)**: `pgoutput` is the native PostgreSQL logical replication protocol plugin, requiring no additional PostgreSQL extension installation. It is available in PostgreSQL 10+ and is the recommended approach for Debezium.
- **Why EventRouter SMT**: The EventRouter transform is purpose-built for the outbox pattern — it reads the `topic` column to route messages and the `partition_key` column for the message key, eliminating custom routing code.
- **Why `tombstones.on.delete=false`**: Outbox rows are eventually deleted by a cleanup job. Without this setting, Debezium would emit a tombstone (null-value) message for each delete, polluting Kafka topics with spurious events.
- **Why idempotent `register-connector.sh`**: The script must be safe to run on every `docker compose up` without error, supporting local dev restarts and CI environments.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL + Kafka + Kafka Connect)

```
- shouldRelayOutboxRow_withinTwoSeconds:
    Given: connector registered and RUNNING; outbox table empty
    When: INSERT INTO outbox(...topic='tenant.bookings.lifecycle', partition_key='uuid-abc'...)
    Assert: KafkaConsumer polling tenant.bookings.lifecycle receives exactly 1 message within 2000ms

- shouldSetMessageKey_toPartitionKey:
    Given: outbox row inserted with partition_key = 'test-booking-uuid'
    Assert: Kafka message key = 'test-booking-uuid'

- shouldRouteToCorrectTopic:
    Given: outbox row inserted with topic = 'tenant.bookings.lifecycle'
    Assert: message appears in tenant.bookings.lifecycle, not in any other topic

- shouldResumeAfterPostgresRestart:
    Given: outbox row inserted; PostgreSQL container stopped before Debezium relays it
    When: PostgreSQL container restarted
    Assert: message eventually appears in Kafka topic (within 30 seconds of restart)

- shouldBeIdempotent_afterDebeziumRestart:
    Given: outbox row inserted; Debezium container stopped before acknowledging relay
    When: Debezium container restarted
    Assert: exactly 1 Kafka message for the outbox row (no duplicate)

- registerConnectorScript_isIdempotent:
    Given: register-connector.sh already executed once (connector exists)
    When: register-connector.sh executed again
    Assert: exit code = 0; connector count remains 1 (no duplicate created)
```

**Coverage requirements**:
- All 6 scenarios must pass before ATOM-KAFKA-011 (chaos tests) begins
- Idempotency test required: Debezium restart produces exactly one Kafka message per outbox row

---

## Implementation Constraints

- WAL level must be `logical` in PostgreSQL — set in `infra/postgres/init.sql` via `ALTER SYSTEM SET`
- Connector must use `pgoutput` plugin (no `wal2json` — requires PostgreSQL extension)
- `tombstones.on.delete` must be `false` to prevent spurious delete tombstones
- `register-connector.sh` must be idempotent — use HTTP `PUT` (upsert) or check existence before `POST`
- Direct Kafka writes from business transactions are BLOCKED — Debezium is the only Kafka producer for booking events
- Consumers must check `processed_events` table before processing (at-least-once delivery guarantee from Debezium)
- No `console.log`; connector registration script uses `echo` only for status reporting
- Audit log is INSERT-only — Debezium never touches `audit_log` directly

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `DebeziumConnectorIT.java` with Testcontainers (PostgreSQL + Kafka + Kafka Connect containers)
2. Write `shouldRelayOutboxRow_withinTwoSeconds` — assert it fails (connector not registered yet)
3. Write `shouldSetMessageKey_toPartitionKey` — assert it fails
4. Write `shouldBeIdempotent_afterDebeziumRestart` — assert it fails

### GREEN — Minimum code to pass

1. Add WAL config to `infra/postgres/init.sql`: `wal_level=logical`, `max_wal_senders=4`, `max_replication_slots=4`
2. Create `infra/kafka/debezium-outbox-connector.json` with full EventRouter config
3. Create `infra/kafka/register-connector.sh` — idempotent registration using `PUT /connectors/{name}/config`
4. Wire `register-connector.sh` to Docker Compose `kafka-connect` service healthcheck or init container

### REFACTOR — Quality pass

1. Add `heartbeat.interval.ms=10000` to connector config to keep WAL slot alive during low activity
2. Verify connector config against Debezium documentation for `transforms.outbox.table.fields.additional.placement`
3. Test `register-connector.sh` idempotency in CI
4. Document connector registration in `docs/KAFKA-SPEC.md` §4.2

---

## Implementation Reference

### Debezium Connector Config

**File**: `infra/kafka/debezium-outbox-connector.json`

```json
{
  "name": "scheduler-outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "plugin.name": "pgoutput",
    "database.hostname": "postgres",
    "database.port": "5432",
    "database.user": "scheduler",
    "database.password": "scheduler_dev",
    "database.dbname": "scheduler",
    "database.server.name": "scheduler",
    "table.include.list": "public.outbox",
    "slot.name": "scheduler_outbox_slot",
    "publication.name": "scheduler_outbox_pub",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.field.event.key": "partition_key",
    "transforms.outbox.table.field.event.type": "event_type",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.route.by.field": "topic",
    "transforms.outbox.table.fields.additional.placement": "aggregate_type:envelope:aggregateType,aggregate_id:envelope:aggregateId",
    "key.converter": "org.apache.kafka.connect.storage.StringConverter",
    "value.converter": "io.confluent.connect.avro.AvroConverter",
    "value.converter.schema.registry.url": "http://schema-registry:8081",
    "tombstones.on.delete": "false",
    "heartbeat.interval.ms": "10000"
  }
}
```

### Connector Registration Script

**File**: `infra/kafka/register-connector.sh`

```bash
#!/bin/bash
# [TASK: ATOM-KAFKA-003] Register Debezium outbox connector after docker compose up
# Idempotent: uses PUT to upsert connector config
set -e
CONNECT_URL=http://localhost:8083
CONNECTOR_NAME=scheduler-outbox-connector

echo "Waiting for Kafka Connect to be ready..."
until curl -sf "$CONNECT_URL/" > /dev/null; do sleep 2; done

echo "Registering outbox connector (idempotent PUT)..."
curl -X PUT "$CONNECT_URL/connectors/$CONNECTOR_NAME/config" \
  -H "Content-Type: application/json" \
  -d "$(cat "$(dirname "$0")/debezium-outbox-connector.json" | jq '.config')"

echo "Connector registration complete. Verifying status..."
sleep 3
STATUS=$(curl -sf "$CONNECT_URL/connectors/$CONNECTOR_NAME/status" | jq -r '.connector.state')
echo "Connector state: $STATUS"
if [ "$STATUS" != "RUNNING" ]; then
  echo "ERROR: Connector is not in RUNNING state"
  exit 1
fi
```

### PostgreSQL WAL Configuration

**File**: `infra/postgres/init.sql` (addition)

```sql
-- [TASK: ATOM-KAFKA-003] Required for Debezium logical replication
ALTER SYSTEM SET wal_level = 'logical';
ALTER SYSTEM SET max_wal_senders = 4;
ALTER SYSTEM SET max_replication_slots = 4;
```

---

## Integration Points

**Depends on**: ATOM-KAFKA-001 (`outbox` table + WAL-compatible PostgreSQL config), ATOM-PHASE1-003 (Kafka Connect container running in Docker Compose)

**Enables**: ATOM-KAFKA-011 (outbox chaos test requires Debezium running and restartable)

**Cascading updates required**:
- `docs/KAFKA-SPEC.md` — document connector config in §4.2
- `infra/docker-compose.yml` — ensure `register-connector.sh` is called after `kafka-connect` healthcheck passes
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `infra/kafka/debezium-outbox-connector.json` | New | Debezium connector config with EventRouter SMT |
| `infra/kafka/register-connector.sh` | New | Idempotent connector registration script |
| `infra/postgres/init.sql` | Modified | Add WAL logical replication settings |
| `infra/docker-compose.yml` | Modified | Wire register-connector.sh to startup sequence |
| `apps/api/src/test/java/com/scheduler/kafka/DebeziumConnectorIT.java` | New | Integration tests for CDC relay |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] Connector status is RUNNING after `register-connector.sh`
- [ ] `tombstones.on.delete=false` set in connector config
- [ ] `heartbeat.interval.ms=10000` set to prevent WAL slot staleness
- [ ] `register-connector.sh` is idempotent — verified by running twice in CI
- [ ] PostgreSQL WAL level = logical confirmed in `infra/postgres/init.sql`
- [ ] No direct Kafka writes from any application code
- [ ] ADR-003 referenced in connector config comment
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: kafka-event-mesh | Phase: 3*
