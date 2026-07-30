---
description: Create the four Kafka topics and configure the Spring Kafka Avro producer with acks=all and idempotent delivery
---

# ATOM-KAFKA-004: Kafka Topic Creation and Producer Config

**Status**: ✅ Complete
**Feature**: kafka-event-mesh
**Phase**: 3 (Kafka)
**Tags**: [KAFKA]
**Complexity**: Low
**Agent**: coder
**Dependencies**: ATOM-PHASE1-003 (Kafka running in Docker Compose)
**Blocks**: ATOM-KAFKA-005, ATOM-KAFKA-006
**PR**: TBD

---

## Overview

This atom creates the four Kafka topics required for Phase 3 and configures the Spring Kafka producer in `apps/api` with idempotent, exactly-once delivery semantics. Topics are created via an init script (`topics.sh`) wired to the `kafka-init` Docker Compose service so they exist before any application starts. The producer is configured with `acks=all`, `enable.idempotence=true`, and `KafkaAvroSerializer` pointing at the Confluent Schema Registry. Note: the Spring API application itself never writes directly to Kafka — Debezium is the sole Kafka producer for booking events. This producer config is used only for any future direct-produce paths (e.g., Phase 4 agentic layer).

---

## User Story

```
As a System
I want all Kafka topics pre-created with correct partition counts and the producer configured for idempotent delivery
So that Debezium and application consumers can operate reliably with no topic-not-found errors at startup
```

---

## Acceptance Criteria

- [ ] **AC-01**: All 4 topics are visible in Kafka UI at `http://localhost:8080` after `docker compose up`
- [ ] **AC-02**: `tenant.bookings.lifecycle` has 12 partitions; `tenant.bookings.lifecycle.DLQ` has 3 partitions; `tenant.notifications.outbound` has 6 partitions; `tenant.audit.events` has 6 partitions
- [ ] **AC-03**: Producer config in `application.yml` matches `docs/KAFKA-SPEC.md` §7 exactly: `acks=all`, `retries=3`, `enable.idempotence=true`, `max.in.flight.requests.per.connection=5`
- [ ] **AC-04**: Value serializer is `io.confluent.kafka.serializers.KafkaAvroSerializer`; key serializer is `org.apache.kafka.common.serialization.StringSerializer`
- [ ] **AC-05**: Topics are created automatically on `docker compose up` — `topics.sh` is wired to the `kafka-init` service
- [ ] **AC-06**: Producing a test message to each topic succeeds without error (verified in integration test)
- [ ] **AC-07 (Idempotency)**: Producer config `enable.idempotence=true` is set — verified by inspecting `ProducerConfig` bean in Spring context
- [ ] **AC-08 (Tenant isolation)**: All new JPA queries include `tenant_id` in WHERE clause — zero cross-tenant rows returned
- [ ] **AC-09 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) in any topic name, producer config key, or identifier

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | Manual / `KafkaTopicIT.java` — `shouldHaveAllFourTopics` | `topics.sh` | 🔜 Planned |
| AC-02 | `KafkaTopicIT.java` — `shouldHaveCorrectPartitionCounts` | `topics.sh` | 🔜 Planned |
| AC-03 | `KafkaProducerConfigTest.java` — `shouldHaveCorrectProducerConfig` | `application.yml` | 🔜 Planned |
| AC-06 | `KafkaTopicIT.java` — `shouldProduceTestMessage_toEachTopic` | `topics.sh` + producer config | 🔜 Planned |
| AC-07 | `KafkaProducerConfigTest.java` — `shouldHaveIdempotenceEnabled` | `application.yml` | 🔜 Planned |
| AC-08 | TBD | TBD | 🔜 Planned |
| AC-09 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 0 criteria rewritten, 0 marked TBD -->

---

## Technical Design

### Architecture

Topic creation is separated from application startup to avoid race conditions. The `kafka-init` Docker Compose service runs `topics.sh` after the Kafka broker passes its healthcheck, ensuring all topics exist before Debezium or any consumer tries to subscribe. The Spring Kafka producer config is defined in `application.yml` and validated at application startup by Spring Boot's auto-configuration. Idempotent producer (`enable.idempotence=true`) ensures no duplicate messages on retry after a transient broker failure.

### Data Flow / Sequence

```
docker compose up
  → kafka broker starts → healthcheck passes
  → kafka-init service runs topics.sh
      → creates tenant.bookings.lifecycle (12 partitions)
      → creates tenant.bookings.lifecycle.DLQ (3 partitions)
      → creates tenant.notifications.outbound (6 partitions)
      → creates tenant.audit.events (6 partitions)
  → apps/api starts → Spring Kafka ProducerFactory initialized with application.yml config
  → Debezium connector registered (ATOM-KAFKA-003) → begins relaying to tenant.bookings.lifecycle
```

### File Structure

```
infra/kafka/
├── topics.sh                            ← topic creation script (idempotent)
└── debezium-outbox-connector.json       ← (ATOM-KAFKA-003)

infra/docker-compose.yml                 ← kafka-init service wired to topics.sh

apps/api/src/main/resources/
└── application.yml                      ← spring.kafka.producer config

apps/api/src/test/java/com/scheduler/
├── kafka/KafkaTopicIT.java              ← topic existence + partition count tests
└── kafka/KafkaProducerConfigTest.java   ← producer config validation
```

### Interface Contracts

No Java service interfaces for this atom — infrastructure config and Spring YAML properties.

```yaml
# application.yml producer shape:
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      acks: all
      retries: 3
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5
        schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8081}
```

```bash
# topics.sh — topic names and partition counts:
# tenant.bookings.lifecycle          — 12 partitions (high throughput, tenant-keyed)
# tenant.bookings.lifecycle.DLQ      — 3 partitions  (dead letter queue)
# tenant.notifications.outbound      — 6 partitions
# tenant.audit.events                — 6 partitions
```

### Design Rationale

- **ADR-003**: Topics must exist before Debezium registers its connector — a missing topic causes connector startup failure. Pre-creation via `kafka-init` eliminates this ordering risk.
- **Why `acks=all`**: Guarantees the message is written to all in-sync replicas before the producer considers it sent — prevents data loss on leader failure.
- **Why `enable.idempotence=true`**: Combined with `acks=all` and `retries=3`, this prevents duplicate messages on network retries. Required for NFR-2.1 (idempotent producer).
- **Why 12 partitions for `tenant.bookings.lifecycle`**: Allows up to 12 parallel consumer instances per consumer group, supporting horizontal scale for notification and audit consumers.
- **Why 3 partitions for DLQ**: DLQ traffic is expected to be low; 3 partitions allow inspection and replay without over-provisioning.
- **Replication factor 1 for local dev**: Production deployments should use replication factor 3 — controlled via environment-specific Helm values or Kafka AdminClient config.

---

## Test Strategy

**Test type**: Integration (Testcontainers + Kafka)

```
- shouldHaveAllFourTopics:
    Given: docker compose up with kafka-init service; topics.sh executed
    Assert: AdminClient.listTopics() returns set containing all 4 topic names

- shouldHaveCorrectPartitionCounts:
    Given: topics created by topics.sh
    Assert: tenant.bookings.lifecycle has partitionCount=12
            tenant.bookings.lifecycle.DLQ has partitionCount=3
            tenant.notifications.outbound has partitionCount=6
            tenant.audit.events has partitionCount=6

- shouldProduceTestMessage_toEachTopic:
    Given: Spring KafkaTemplate initialized with application.yml producer config
    Assert: sending a test String message to each topic returns Future.get() without exception

- shouldHaveCorrectProducerConfig:
    Given: Spring application context loaded
    Assert: ProducerFactory config contains acks=all, retries=3,
            value-serializer=KafkaAvroSerializer, enable.idempotence=true

- shouldHaveIdempotenceEnabled:
    Given: Spring application context loaded
    Assert: producerFactory.configurationProperties["enable.idempotence"] = true

- topicsScript_isIdempotent:
    Given: topics.sh already executed once (topics exist)
    When: topics.sh executed again
    Assert: exit code = 0; no duplicate topics created (kafka-topics.sh --if-not-exists flag used)

- shouldBeIdempotent_onDuplicateMessage:
    Given: same Kafka message key delivered twice
    Assert: processed_events has exactly 1 row for that key; side effect triggered exactly once
```

**Coverage requirements**:
- All 6 test cases must pass before ATOM-KAFKA-005 (Avro schema registration) begins
- Idempotency test: `topics.sh` run twice — second run is a no-op

---

## Implementation Constraints

- `topics.sh` must use `--if-not-exists` flag to be idempotent on re-run
- Producer `acks` must be `all` — never `1` or `0`
- `enable.idempotence=true` requires `max.in.flight.requests.per.connection ≤ 5`
- Value serializer must be `KafkaAvroSerializer` — never `StringSerializer` for Avro payloads
- Replication factor = 1 for local dev; production override via environment config
- No direct Kafka writes from booking business transactions — Debezium is the outbox relay
- Consumers must check `processed_events` table before processing
- No `System.out.println` — use SLF4J in any Java test helpers
- Outbox event written within `@Transactional` scope (if booking state mutated)

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `KafkaTopicIT.java` with Testcontainers Kafka setup
2. Write `shouldHaveAllFourTopics` — assert it fails (topics don't exist yet)
3. Write `shouldHaveCorrectPartitionCounts` — assert it fails
4. Create `KafkaProducerConfigTest.java`
5. Write `shouldHaveIdempotenceEnabled` — assert it fails (config not set)

### GREEN — Minimum code to pass

1. Create `infra/kafka/topics.sh` with all 4 `kafka-topics.sh --create --if-not-exists` commands
2. Add `kafka-init` service to `infra/docker-compose.yml` that runs `topics.sh`
3. Add `spring.kafka.producer` config block to `apps/api/src/main/resources/application.yml`
4. Add `schema.registry.url` to producer properties

### REFACTOR — Quality pass

1. Add comments to `topics.sh` referencing KAFKA-SPEC.md §1
2. Verify `application.yml` producer config against KAFKA-SPEC.md §7 line by line
3. Add test that produces and consumes a round-trip message through each topic
4. Document topic partition strategy in `docs/KAFKA-SPEC.md` §1

---

## Implementation Reference

### Topic Creation Script

**File**: `infra/kafka/topics.sh`

```bash
#!/bin/bash
# [TASK: ATOM-KAFKA-004] Create all Kafka topics for the scheduling framework
# See docs/KAFKA-SPEC.md §1 for topic design rationale
# Idempotent: --if-not-exists prevents error on re-run
set -e

KAFKA_BIN=${KAFKA_HOME:-/opt/kafka}/bin
BOOTSTRAP=${BOOTSTRAP_SERVERS:-kafka:9092}

echo "Creating Kafka topics..."

# Primary booking lifecycle topic — 12 partitions for horizontal consumer scale
$KAFKA_BIN/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" \
  --create --if-not-exists \
  --topic tenant.bookings.lifecycle \
  --partitions 12 \
  --replication-factor 1

# Dead letter queue for failed booking lifecycle events
$KAFKA_BIN/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" \
  --create --if-not-exists \
  --topic tenant.bookings.lifecycle.DLQ \
  --partitions 3 \
  --replication-factor 1

# Outbound notification commands
$KAFKA_BIN/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" \
  --create --if-not-exists \
  --topic tenant.notifications.outbound \
  --partitions 6 \
  --replication-factor 1

# Audit event stream
$KAFKA_BIN/kafka-topics.sh --bootstrap-server "$BOOTSTRAP" \
  --create --if-not-exists \
  --topic tenant.audit.events \
  --partitions 6 \
  --replication-factor 1

echo "All topics created successfully."
```

### Spring Kafka Producer Configuration

**File**: `apps/api/src/main/resources/application.yml` (additions)

```yaml
spring:
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    producer:
      acks: all
      retries: 3
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: io.confluent.kafka.serializers.KafkaAvroSerializer
      properties:
        enable.idempotence: true
        max.in.flight.requests.per.connection: 5
        schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8081}
```

### Docker Compose kafka-init Service

**File**: `infra/docker-compose.yml` (addition)

```yaml
  kafka-init:
    image: confluentinc/cp-kafka:7.5.0
    depends_on:
      kafka:
        condition: service_healthy
    entrypoint: ["/bin/bash", "/scripts/topics.sh"]
    volumes:
      - ./kafka/topics.sh:/scripts/topics.sh
    environment:
      BOOTSTRAP_SERVERS: kafka:9092
    networks:
      - scheduler-network
```

### KafkaTopicIT Integration Test

**File**: `apps/api/src/test/java/com/scheduler/kafka/KafkaTopicIT.java`

```java
// [TASK: ATOM-KAFKA-004]
package com.scheduler.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.TopicDescription;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class KafkaTopicIT {

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Test
    void shouldHaveAllFourTopics() throws ExecutionException, InterruptedException {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Set<String> topics = admin.listTopics().names().get();
            assertThat(topics).contains(
                "tenant.bookings.lifecycle",
                "tenant.bookings.lifecycle.DLQ",
                "tenant.notifications.outbound",
                "tenant.audit.events"
            );
        }
    }

    @Test
    void shouldHaveCorrectPartitionCounts() throws ExecutionException, InterruptedException {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Map<String, TopicDescription> descriptions = admin.describeTopics(Set.of(
                "tenant.bookings.lifecycle",
                "tenant.bookings.lifecycle.DLQ",
                "tenant.notifications.outbound",
                "tenant.audit.events"
            )).allTopicNames().get();

            assertThat(descriptions.get("tenant.bookings.lifecycle").partitions()).hasSize(12);
            assertThat(descriptions.get("tenant.bookings.lifecycle.DLQ").partitions()).hasSize(3);
            assertThat(descriptions.get("tenant.notifications.outbound").partitions()).hasSize(6);
            assertThat(descriptions.get("tenant.audit.events").partitions()).hasSize(6);
        }
    }
}
```

---

## Integration Points

**Depends on**: ATOM-PHASE1-003 (Kafka + Kafka Connect running in Docker Compose)

**Enables**: ATOM-KAFKA-005 (Avro schemas registered against existing topics), ATOM-KAFKA-006 (BookingService outbox integration requires topics to exist)

**Cascading updates required**:
- `docs/KAFKA-SPEC.md` — confirm topic names, partition counts, and producer config in §1 and §7
- `infra/docker-compose.yml` — add `kafka-init` service
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `infra/kafka/topics.sh` | New | Idempotent topic creation script |
| `infra/docker-compose.yml` | Modified | Add kafka-init service running topics.sh |
| `apps/api/src/main/resources/application.yml` | Modified | Add spring.kafka.producer config |
| `apps/api/src/test/java/com/scheduler/kafka/KafkaTopicIT.java` | New | Topic existence and partition tests |
| `apps/api/src/test/java/com/scheduler/kafka/KafkaProducerConfigTest.java` | New | Producer config validation |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] All 4 topics created with correct partition counts
- [ ] `topics.sh` uses `--if-not-exists` — idempotent on re-run
- [ ] Producer config: `acks=all`, `retries=3`, `enable.idempotence=true`
- [ ] Value serializer = `KafkaAvroSerializer`
- [ ] `schema.registry.url` uses environment variable with local default
- [ ] `kafka-init` service wired to Docker Compose startup sequence
- [ ] No direct Kafka writes from booking business transactions
- [ ] Zero JPA queries without `tenant_id` in WHERE clause
- [ ] Zero industry-specific terms in any identifier or API path
- [ ] `@PreAuthorize` present on every new `@RestController` method
- [ ] Outbox event written within `@Transactional` scope (if booking state mutated)
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: kafka-event-mesh | Phase: 3*
