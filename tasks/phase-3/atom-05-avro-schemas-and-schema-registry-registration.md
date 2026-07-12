---
description: Define three Avro schemas for booking lifecycle events, notification commands, and audit events; register them with BACKWARD compatibility in the Confluent Schema Registry
---

# ATOM-KAFKA-005: Avro Schemas and Schema Registry Registration

**Status**: 🟡 Planned
**Feature**: kafka-event-mesh
**Phase**: 3 (Kafka)
**Tags**: [KAFKA]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-KAFKA-004 (Kafka topics must exist; Schema Registry must be running)
**Blocks**: ATOM-KAFKA-002, ATOM-KAFKA-006, ATOM-KAFKA-008, ATOM-KAFKA-010
**PR**: TBD

---

## Overview

This atom defines the Avro schemas for all three event types flowing through the Phase 3 Kafka pipeline: `BookingLifecycleEvent` (booking state changes), `NotificationCommand` (dispatch instructions to notification-service), and `AuditEvent` (HIPAA audit fields for audit-service). Schemas are registered against the Confluent Schema Registry with BACKWARD compatibility policy, ensuring consumers can always deserialize messages produced by any older schema version. Java classes are generated from `.avsc` files via `avro-maven-plugin`. The `BookingLifecycleEvent` field names are the authoritative reference for `OutboxService.buildPayload()` in ATOM-KAFKA-002.

---

## User Story

```
As a System
I want all Kafka message schemas registered in Schema Registry with BACKWARD compatibility
So that producers and consumers evolve independently without deserialization failures
```

---

## Acceptance Criteria

- [ ] **AC-01**: All 3 schemas registered in Schema Registry — `GET http://localhost:8081/subjects` returns `tenant.bookings.lifecycle-value`, `tenant.notifications.outbound-value`, `tenant.audit.events-value`
- [ ] **AC-02**: Java Avro classes generated from `.avsc` files via `avro-maven-plugin` and compile successfully (`mvn generate-sources`)
- [ ] **AC-03**: Schema Registry rejects an incompatible schema change — adding a required field without a default value returns HTTP 409 CONFLICT
- [ ] **AC-04**: All 3 schemas use BACKWARD compatibility policy (verified at `GET http://localhost:8081/config/{subject}`)
- [ ] **AC-05**: `BookingLifecycleEvent` Avro fields match `OutboxService.buildPayload()` payload keys exactly: `eventId`, `eventType`, `occurredAt`, `tenantId`, `bookingId`, `resourceId`, `serviceTypeId`, `userId`, `slotStart`, `slotEnd`, `previousStatus`, `newStatus`, `ipAddress`
- [ ] **AC-06 (Idempotency)**: `register-schemas.sh` run twice — second run returns HTTP 200 (schema already exists, same fingerprint) without creating a duplicate version
- [ ] **AC-07 (Tenant isolation)**: All new JPA queries include `tenant_id` in WHERE clause — zero cross-tenant rows returned; `BookingLifecycleEvent.tenantId` is a required string field — no schema allows tenant-unscoped events
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) in any schema field name, namespace, or doc string

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `SchemaRegistryIT.java` — `shouldHaveAllThreeSchemas_registered` | `register-schemas.sh` | 🔜 Planned |
| AC-02 | `mvn generate-sources` in CI | `pom.xml` avro-maven-plugin config | 🔜 Planned |
| AC-03 | `SchemaRegistryIT.java` — `shouldRejectIncompatibleChange` | Schema Registry config | 🔜 Planned |
| AC-04 | `SchemaRegistryIT.java` — `shouldHaveBackwardCompatibility_forAllSubjects` | `register-schemas.sh` | 🔜 Planned |
| AC-05 | `OutboxServiceTest.java` — `shouldBuildPayload_matchingAvroSchema` (ATOM-KAFKA-002) | `booking-lifecycle-event.avsc` | 🔜 Planned |
| AC-06 | `SchemaRegistryIT.java` — `registerScript_isIdempotent` | `register-schemas.sh` | 🔜 Planned |
| AC-07 | TBD | TBD | 🔜 Planned |
| AC-08 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 0 criteria rewritten, 0 marked TBD -->

---

## Technical Design

### Architecture

Three `.avsc` Avro schema files live under `infra/kafka/schemas/`. A registration shell script (`register-schemas.sh`) wraps each schema in the Schema Registry HTTP API envelope and `POST`s it to the Registry. The `avro-maven-plugin` in `apps/api/pom.xml` reads these same `.avsc` files during `generate-sources` and emits Java classes into `target/generated-sources/avro/`. BACKWARD compatibility means new schema versions can only add optional fields (with defaults) — never remove or rename required fields.

### Data Flow / Sequence

```
mvn generate-sources
  → avro-maven-plugin reads infra/kafka/schemas/*.avsc
  → generates Java classes: BookingLifecycleEvent, NotificationCommand, AuditEvent
  → classes available at compile time in apps/api

register-schemas.sh (run at docker compose up or CI)
  → POST http://localhost:8081/subjects/tenant.bookings.lifecycle-value/versions
  → POST http://localhost:8081/subjects/tenant.notifications.outbound-value/versions
  → POST http://localhost:8081/subjects/tenant.audit.events-value/versions
  → Schema Registry validates BACKWARD compatibility before accepting each registration

OutboxService.buildPayload() (ATOM-KAFKA-002)
  → constructs Map with keys matching BookingLifecycleEvent field names
  → Debezium serializes payload via KafkaAvroSerializer against registered schema
```

### File Structure

```
infra/kafka/schemas/
├── booking-lifecycle-event.avsc      ← BookingLifecycleEvent schema
├── notification-command.avsc         ← NotificationCommand schema
└── audit-event.avsc                  ← AuditEvent schema

infra/kafka/
└── register-schemas.sh               ← BACKWARD-compatible registration script

apps/api/pom.xml                      ← avro-maven-plugin config
apps/api/target/generated-sources/avro/com/scheduler/avro/
├── BookingLifecycleEvent.java        ← generated
├── NotificationCommand.java          ← generated
└── AuditEvent.java                   ← generated

apps/api/src/test/java/com/scheduler/
└── kafka/SchemaRegistryIT.java       ← Schema Registry integration tests
```

### Interface Contracts

```java
// Generated Avro class shapes — field list only (no method bodies):

// BookingLifecycleEvent — authoritative field list for OutboxService.buildPayload()
public class BookingLifecycleEvent extends SpecificRecordBase {
    String eventId;        // UUID string — unique per event
    String eventType;      // "BookingConfirmed" | "BookingCancelled" | "BookingHeld"
    String occurredAt;     // ISO-8601 Instant string
    String tenantId;       // UUID string — required for tenant isolation
    String bookingId;      // UUID string
    String resourceId;     // UUID string
    String serviceTypeId;  // UUID string
    String userId;         // UUID string
    String slotStart;      // ISO-8601 Instant string
    String slotEnd;        // ISO-8601 Instant string
    String previousStatus; // nullable (["null","string"] union, default null)
    String newStatus;      // nullable
    String ipAddress;      // nullable
}

// NotificationCommand
public class NotificationCommand extends SpecificRecordBase {
    String commandId;               // UUID string
    String tenantId;                // required
    String userId;                  // recipient
    String channel;                 // "EMAIL" | "SMS"
    String templateKey;             // e.g. "booking.confirmed"
    Map<String, String> variables;  // template substitution map
}

// AuditEvent
public class AuditEvent extends SpecificRecordBase {
    String eventId;                        // UUID string
    String tenantId;                       // required
    String who;                            // userId
    String what;                           // eventType
    String when_;                          // ISO-8601 (underscore avoids Java keyword)
    String bookingId;                      // nullable
    String resourceId;                     // nullable
    String ipAddress;                      // nullable
    Map<String, String> metadata;          // nullable
}
```

### Design Rationale

- **ADR-003 (Transactional Outbox)**: The `BookingLifecycleEvent` schema is the single source of truth for payload field names — `OutboxService.buildPayload()` must use these exact keys. Any mismatch causes deserialization failure at the consumer.
- **Why BACKWARD compatibility (not FULL or FORWARD)**: BACKWARD allows consumers to be updated before producers — the most common deployment order. New optional fields can be added without breaking existing consumers. Removing fields or changing types is rejected.
- **Why Avro (not JSON Schema or Protobuf)**: Confluent Schema Registry has first-class Avro support; `avro-maven-plugin` generates strongly-typed Java classes; Debezium's `KafkaAvroSerializer` is production-proven with this stack.
- **Why string types for UUIDs and Instants**: Avro has no native UUID or Instant type. Using `string` with logical type documentation avoids platform-specific encoding issues across polyglot consumers.
- **NFR-2.2 compliance**: The Schema Registry validates every payload before it is produced — incompatible changes are rejected at registration time, not at runtime.

---

## Test Strategy

**Test type**: Integration (Testcontainers + Kafka + Schema Registry)

```
- shouldHaveAllThreeSchemas_registered:
    Given: register-schemas.sh executed against running Schema Registry
    Assert: GET /subjects returns list containing all 3 subject names

- shouldHaveBackwardCompatibility_forAllSubjects:
    Given: schemas registered
    Assert: GET /config/tenant.bookings.lifecycle-value returns {"compatibilityLevel":"BACKWARD"}
            (same for other two subjects)

- shouldRejectIncompatibleChange:
    Given: BookingLifecycleEvent v1 registered
    When: POST a v2 schema that removes the required 'tenantId' field
    Assert: Schema Registry returns HTTP 409 CONFLICT

- shouldAcceptCompatibleChange:
    Given: BookingLifecycleEvent v1 registered
    When: POST a v2 schema that adds an optional 'metadata' field with null default
    Assert: Schema Registry returns HTTP 200 with new schema id

- registerScript_isIdempotent:
    Given: register-schemas.sh already executed
    When: register-schemas.sh executed again
    Assert: exit code = 0; GET /subjects/{subject}/versions still returns exactly 1 version

- shouldBeIdempotent_onDuplicateMessage:
    Given: same Kafka message key delivered twice (with same BookingLifecycleEvent payload)
    Assert: consumer deserializes both messages without error; processed_events has exactly 1 row
```

**Coverage requirements**:
- All 6 test cases must pass before ATOM-KAFKA-006 (BookingService integration) begins
- Idempotency test required: `register-schemas.sh` run twice — no duplicate schema version created

---

## Implementation Constraints

- Schema field names must exactly match `OutboxService.buildPayload()` keys — verified in ATOM-KAFKA-002 unit test
- All schemas must use BACKWARD compatibility policy — never `FULL`, `FORWARD`, or `NONE`
- Adding a required field (no default) to an existing schema is BLOCKED — always add with `"default": null`
- Generated Java classes must not be committed to source control — only `.avsc` files are committed
- `avro-maven-plugin` output directory: `target/generated-sources/avro/` — added to compile source roots in `pom.xml`
- `register-schemas.sh` must be idempotent — use POST which returns existing schema ID if fingerprint matches
- No industry-specific terms in schema namespaces, field names, or doc strings
- Consumers must check `processed_events` table before processing (enforced in ATOM-KAFKA-008 and ATOM-KAFKA-010)
- Outbox event written within `@Transactional` scope (if booking state mutated)

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `SchemaRegistryIT.java` with Testcontainers (Kafka + Schema Registry)
2. Write `shouldHaveAllThreeSchemas_registered` — assert it fails (schemas not yet registered)
3. Write `shouldRejectIncompatibleChange` — assert it fails (no schema to test against yet)
4. Write `registerScript_isIdempotent` — assert it fails

### GREEN — Minimum code to pass

1. Create `infra/kafka/schemas/booking-lifecycle-event.avsc` with all required fields
2. Create `infra/kafka/schemas/notification-command.avsc`
3. Create `infra/kafka/schemas/audit-event.avsc`
4. Create `infra/kafka/register-schemas.sh` with BACKWARD compatibility SET before registration
5. Add `avro-maven-plugin` config to `apps/api/pom.xml`
6. Run `mvn generate-sources` and verify Java classes compile

### REFACTOR — Quality pass

1. Add `"doc"` fields to all Avro schema field definitions
2. Verify `BookingLifecycleEvent` field names match `OutboxService.buildPayload()` keys exactly
3. Add `nullable` fields with `["null", "string"]` union type and `"default": null`
4. Update `docs/KAFKA-SPEC.md` §3 with schema field reference table

---

## Implementation Reference

### BookingLifecycleEvent Avro Schema

**File**: `infra/kafka/schemas/booking-lifecycle-event.avsc`

```json
{
  "namespace": "com.scheduler.avro",
  "type": "record",
  "name": "BookingLifecycleEvent",
  "doc": "Emitted on every booking state transition. Consumed by notification-service and audit-service.",
  "fields": [
    {"name": "eventId",       "type": "string", "doc": "UUID string — unique per event"},
    {"name": "eventType",     "type": "string", "doc": "BookingConfirmed | BookingCancelled | BookingHeld"},
    {"name": "occurredAt",    "type": "string", "doc": "ISO-8601 Instant"},
    {"name": "tenantId",      "type": "string", "doc": "Tenant UUID — required for isolation"},
    {"name": "bookingId",     "type": "string", "doc": "Booking UUID"},
    {"name": "resourceId",    "type": "string", "doc": "Resource UUID"},
    {"name": "serviceTypeId", "type": "string", "doc": "ServiceType UUID"},
    {"name": "userId",        "type": "string", "doc": "User UUID"},
    {"name": "slotStart",     "type": "string", "doc": "ISO-8601 Instant"},
    {"name": "slotEnd",       "type": "string", "doc": "ISO-8601 Instant"},
    {"name": "previousStatus","type": ["null", "string"], "default": null},
    {"name": "newStatus",     "type": ["null", "string"], "default": null},
    {"name": "ipAddress",     "type": ["null", "string"], "default": null}
  ]
}
```

### NotificationCommand Avro Schema

**File**: `infra/kafka/schemas/notification-command.avsc`

```json
{
  "namespace": "com.scheduler.avro",
  "type": "record",
  "name": "NotificationCommand",
  "doc": "Dispatch instruction to notification-service.",
  "fields": [
    {"name": "commandId",   "type": "string"},
    {"name": "tenantId",    "type": "string"},
    {"name": "userId",      "type": "string"},
    {"name": "channel",     "type": "string", "doc": "EMAIL | SMS"},
    {"name": "templateKey", "type": "string"},
    {"name": "variables",   "type": {"type": "map", "values": "string"}, "default": {}}
  ]
}
```

### AuditEvent Avro Schema

**File**: `infra/kafka/schemas/audit-event.avsc`

```json
{
  "namespace": "com.scheduler.avro",
  "type": "record",
  "name": "AuditEvent",
  "doc": "HIPAA-grade audit event consumed by audit-service.",
  "fields": [
    {"name": "eventId",    "type": "string"},
    {"name": "tenantId",   "type": "string"},
    {"name": "who",        "type": "string", "doc": "userId"},
    {"name": "what",       "type": "string", "doc": "eventType"},
    {"name": "when_",      "type": "string", "doc": "ISO-8601 Instant"},
    {"name": "bookingId",  "type": ["null", "string"], "default": null},
    {"name": "resourceId", "type": ["null", "string"], "default": null},
    {"name": "ipAddress",  "type": ["null", "string"], "default": null},
    {"name": "metadata",   "type": ["null", {"type": "map", "values": "string"}], "default": null}
  ]
}
```

### Schema Registration Script

**File**: `infra/kafka/register-schemas.sh`

```bash
#!/bin/bash
# [TASK: ATOM-KAFKA-005] Register Avro schemas with BACKWARD compatibility
# Idempotent: POST returns existing schema ID if fingerprint matches
# NFR-2.2: Schema Registry validates every payload before produce
set -e
REGISTRY_URL=${SCHEMA_REGISTRY_URL:-http://localhost:8081}
SCHEMA_DIR="$(dirname "$0")/schemas"

echo "Waiting for Schema Registry..."
until curl -sf "$REGISTRY_URL/subjects" > /dev/null; do sleep 2; done

register_schema() {
  local subject=$1
  local schema_file=$2
  echo "Registering $subject..."
  # Set BACKWARD compatibility first
  curl -sf -X PUT "$REGISTRY_URL/config/$subject" \
    -H "Content-Type: application/vnd.schemaregistry.v1+json" \
    -d '{"compatibility":"BACKWARD"}'
  # Register schema (idempotent — returns existing ID if same fingerprint)
  SCHEMA=$(cat "$SCHEMA_DIR/$schema_file" | jq -Rs '{"schema": .}')
  curl -sf -X POST "$REGISTRY_URL/subjects/$subject/versions" \
    -H "Content-Type: application/vnd.schemaregistry.v1+json" \
    -d "$SCHEMA"
  echo ""
}

register_schema "tenant.bookings.lifecycle-value"     "booking-lifecycle-event.avsc"
register_schema "tenant.notifications.outbound-value" "notification-command.avsc"
register_schema "tenant.audit.events-value"           "audit-event.avsc"

echo "All schemas registered successfully."
```

### avro-maven-plugin Config

**File**: `apps/api/pom.xml` (addition within `<plugins>`)

```xml
<!-- [TASK: ATOM-KAFKA-005] Generate Java classes from Avro schemas -->
<plugin>
  <groupId>org.apache.avro</groupId>
  <artifactId>avro-maven-plugin</artifactId>
  <version>1.11.3</version>
  <executions>
    <execution>
      <phase>generate-sources</phase>
      <goals><goal>schema</goal></goals>
      <configuration>
        <sourceDirectory>${project.basedir}/../../infra/kafka/schemas</sourceDirectory>
        <outputDirectory>${project.build.directory}/generated-sources/avro</outputDirectory>
        <stringType>String</stringType>
      </configuration>
    </execution>
  </executions>
</plugin>
```

---

## Integration Points

**Depends on**: ATOM-KAFKA-004 (topics exist; Schema Registry running), ATOM-PHASE1-003 (Docker Compose infra)

**Enables**: ATOM-KAFKA-002 (`BookingLifecycleEvent` field names now authoritative for `OutboxService.buildPayload()`), ATOM-KAFKA-006 (BookingService integration), ATOM-KAFKA-008 (NotificationConsumer deserializes `BookingLifecycleEvent`), ATOM-KAFKA-010 (AuditConsumer deserializes `BookingLifecycleEvent`)

**Cascading updates required**:
- `docs/KAFKA-SPEC.md` — update §3 with schema field reference tables
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `infra/kafka/schemas/booking-lifecycle-event.avsc` | New | BookingLifecycleEvent Avro schema |
| `infra/kafka/schemas/notification-command.avsc` | New | NotificationCommand Avro schema |
| `infra/kafka/schemas/audit-event.avsc` | New | AuditEvent Avro schema |
| `infra/kafka/register-schemas.sh` | New | BACKWARD-compatible registration script |
| `apps/api/pom.xml` | Modified | avro-maven-plugin config |
| `apps/api/src/test/java/com/scheduler/kafka/SchemaRegistryIT.java` | New | Schema Registry integration tests |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn generate-sources` succeeds — Java Avro classes compile
- [ ] `mvn verify -P integration` passes (SchemaRegistryIT)
- [ ] All 3 schemas registered with BACKWARD compatibility
- [ ] `BookingLifecycleEvent` field names match `OutboxService.buildPayload()` keys exactly
- [ ] Schema Registry rejects incompatible change (HTTP 409 test passes)
- [ ] `register-schemas.sh` is idempotent — verified by running twice
- [ ] Generated Java classes are in `.gitignore` (not committed)
- [ ] No industry-specific terms in any schema field name or namespace
- [ ] Zero JPA queries without `tenant_id` in WHERE clause
- [ ] `@PreAuthorize` present on every new `@RestController` method
- [ ] Outbox event written within `@Transactional` scope (if booking state mutated)
- [ ] NFR-2.2 referenced in `register-schemas.sh` comments
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: kafka-event-mesh | Phase: 3*
