---
description: Spring Boot scaffold for the notification-service microservice — Kafka consumer group, processed_events JPA, health endpoint, Docker Compose container
---

# ATOM-KAFKA-007: Notification Service Project Scaffold

**Status**: ✅ Complete
**Feature**: kafka-event-mesh
**Phase**: 3 (Kafka)
**Tags**: [KAFKA]
**Complexity**: Low
**Agent**: coder
**Dependencies**: ATOM-PHASE1-001 (Docker Compose infra running)
**Blocks**: ATOM-KAFKA-008
**PR**: TBD

---

## Overview

This atom creates the `services/notification-service` Spring Boot microservice — the empty shell that ATOM-KAFKA-008 will fill with consumer logic. The scaffold includes all Maven dependencies (spring-kafka, spring-data-jpa, aws-java-sdk-sesv2, Twilio, Confluent Avro serializer), the application configuration connecting to Kafka/PostgreSQL/Schema Registry, a health endpoint, and a Docker Compose container definition. The `notification-consumers` consumer group is registered at this stage. No consumer logic is implemented here.

---

## User Story

```
As a System
I want a standalone notification-service microservice that connects to Kafka and PostgreSQL
So that booking lifecycle events can be consumed and email/SMS notifications dispatched independently of the main API
```

---

## Acceptance Criteria

- [ ] **AC-01**: `notification-service` starts without errors — `mvn spring-boot:run` succeeds
- [ ] **AC-02**: Service connects to Kafka broker on startup without `WARN` or `ERROR` connection logs
- [ ] **AC-03**: `GET /health` returns HTTP 200 with body `{"status":"UP"}`
- [ ] **AC-04**: Service is included in `infra/docker-compose.yml` as a separate container with its own network alias
- [ ] **AC-05**: Service connects to PostgreSQL using the `processed_events` table (JPA entity `ProcessedEvent` maps correctly)
- [ ] **AC-06**: Consumer group `notification-consumers` is visible in Kafka consumer groups list after service starts
- [ ] **AC-07 (Idempotency)**: `ProcessedEventRepository.existsByConsumerGroupAndMessageKey()` returns `false` for an unseen key and `true` for a seen key — verified in repository unit test
- [ ] **AC-08 (Tenant isolation)**: All new JPA queries include `tenant_id` in WHERE clause — zero cross-tenant rows returned; `ProcessedEvent` entity has no `tenant_id` column — deduplication is consumer-group scoped, not tenant-scoped (correct by design; tenant isolation is in the booking payload)
- [ ] **AC-09 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) in service name, package names, config keys, or endpoint paths

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `NotificationServiceSmokeIT.java` — `shouldStartWithoutErrors` | `NotificationServiceApplication.java` | 🔜 Planned |
| AC-02 | `NotificationServiceSmokeIT.java` — `shouldConnectToKafka` | `application.yml` kafka config | 🔜 Planned |
| AC-03 | `NotificationServiceSmokeIT.java` — `shouldReturnHealthUp` | `HealthController.java` | 🔜 Planned |
| AC-05 | `ProcessedEventRepositoryTest.java` — `shouldMapProcessedEventTable` | `ProcessedEvent.java` | 🔜 Planned |
| AC-07 | `ProcessedEventRepositoryTest.java` — `shouldReturnFalse_forUnseenKey` | `ProcessedEventRepository.java` | 🔜 Planned |
| AC-08 | TBD | TBD | 🔜 Planned |
| AC-09 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 0 criteria rewritten, 0 marked TBD -->

---

## Technical Design

### Architecture

`notification-service` is a separate Spring Boot application (its own JAR, its own Docker container). It shares the PostgreSQL instance for the `processed_events` table (using the same DB user as the main API for local dev; a separate role in production). It connects to the same Kafka cluster and Schema Registry. The service has no REST API beyond `/health` — it is entirely event-driven. This atom creates only the scaffold; the `@KafkaListener` is added in ATOM-KAFKA-008.

### Data Flow / Sequence

```
docker compose up
  → notification-service container starts
  → Spring Boot connects to Kafka (consumer group: notification-consumers)
  → Spring Boot connects to PostgreSQL (processed_events JPA)
  → Spring Boot connects to Schema Registry (Avro deserializer config)
  → /health returns {"status":"UP"}
  [Consumer logic added in ATOM-KAFKA-008]
```

### File Structure

```
services/notification-service/
├── pom.xml                                          ← Spring Boot 3.x, spring-kafka, JPA, SES, Twilio
├── src/main/java/com/scheduler/notification/
│   ├── NotificationServiceApplication.java         ← @SpringBootApplication
│   ├── controller/
│   │   └── HealthController.java                   ← GET /health
│   ├── domain/
│   │   └── ProcessedEvent.java                     ← JPA entity for processed_events table
│   └── repository/
│       └── ProcessedEventRepository.java           ← existsByConsumerGroupAndMessageKey()
└── src/main/resources/
    └── application.yml                             ← kafka, datasource, schema-registry config

infra/docker-compose.yml                            ← notification-service container definition
```

### Interface Contracts

```java
// JPA Entity — processed_events table
@Entity
@Table(name = "processed_events",
       uniqueConstraints = @UniqueConstraint(columnNames = {"consumer_group", "message_key"}))
public class ProcessedEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumer_group", nullable = false)
    private String consumerGroup;

    @Column(name = "message_key", nullable = false)
    private String messageKey;

    @Column(nullable = false)
    private Instant processedAt;
}

// Repository — deduplication query
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    boolean existsByConsumerGroupAndMessageKey(String consumerGroup, String messageKey);
}

// Health endpoint response shape
record HealthResponse(String status) {}

// HealthController
@RestController
public class HealthController {
    @GetMapping("/health")
    HealthResponse health();
}
```

```yaml
# application.yml config shape:
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/scheduler}
    username: ${DB_USER:scheduler}
    password: ${DB_PASSWORD:scheduler_dev}
  jpa:
    hibernate.ddl-auto: validate
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: notification-consumers
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: io.confluent.kafka.serializers.KafkaAvroDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false
      properties:
        schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8081}
        specific.avro.reader: true
```

### Design Rationale

- **Why a separate service (not a module in apps/api)**: Notification dispatch (SES, Twilio) has different deployment, scaling, and failure characteristics than the booking API. A separate process isolates SES/Twilio connection pool exhaustion from booking throughput.
- **Why `enable-auto-commit: false`**: Manual offset commit (via `acknowledgment.acknowledge()`) is required for the idempotency pattern — the consumer must write to `processed_events` before committing the offset so a crash-before-ack causes a safe redelivery.
- **Why `specific.avro.reader: true`**: Tells the Avro deserializer to use generated `BookingLifecycleEvent` class (type-safe) rather than `GenericRecord` (stringly-typed).
- **Why `auto-offset-reset: earliest`**: On first start (no committed offset), the consumer reads from the beginning of the topic — ensures no events are silently skipped during initial deployment.
- **ADR-003**: The `processed_events` table (written by this service) is the consumer-side deduplication mechanism that pairs with the outbox producer-side guarantee.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL + Kafka) and Unit (JUnit 5)

```
- shouldStartWithoutErrors:
    Given: Testcontainers PostgreSQL + Kafka running; migrations applied
    Assert: Spring application context loads without exceptions

- shouldConnectToKafka:
    Given: application context loaded
    Assert: KafkaListenerEndpointRegistry bean exists; no connection ERROR in logs

- shouldReturnHealthUp:
    Given: application started
    Assert: GET /health returns HTTP 200, body = {"status":"UP"}

- shouldMapProcessedEventTable:
    Given: processed_events table exists (V012 migration)
    Assert: ProcessedEventRepository.save(new ProcessedEvent(...)) succeeds; findById() returns saved row

- shouldReturnFalse_forUnseenKey:
    Given: empty processed_events table
    Assert: existsByConsumerGroupAndMessageKey("notification-consumers", "new-key") = false

- shouldReturnTrue_forSeenKey:
    Given: one ProcessedEvent row (consumerGroup="notification-consumers", messageKey="key-abc")
    Assert: existsByConsumerGroupAndMessageKey("notification-consumers", "key-abc") = true

- shouldBeIdempotent_onDuplicateMessage:
    Given: same message key saved twice to processed_events
    Assert: second save throws DataIntegrityViolationException (unique constraint on consumer_group, message_key)
```

**Coverage requirements**:
- Line coverage ≥ 80% on `ProcessedEventRepository`
- Idempotency test required: duplicate `(consumerGroup, messageKey)` insert throws constraint violation

---

## Implementation Constraints

- `enable-auto-commit: false` — manual offset commit required (ATOM-KAFKA-008 calls `acknowledgment.acknowledge()`)
- `specific.avro.reader: true` — use generated Avro class, not GenericRecord
- `ProcessedEvent` table must use the same `processed_events` schema from V012 migration (ATOM-KAFKA-001)
- `hibernate.ddl-auto: validate` — never `create` or `update`; schema managed by Flyway only
- No direct Kafka writes from this service — it is a consumer only
- Audit log is INSERT-only; this service never touches `audit_log`
- No `System.out.println` — use SLF4J structured logging
- All environment-specific config via environment variables with local defaults
- Service must be in `docker-compose.yml` with `depends_on: [kafka, postgres, schema-registry]`
- Outbox event written within `@Transactional` scope (if booking state mutated)

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `NotificationServiceSmokeIT.java` with Testcontainers setup
2. Write `shouldStartWithoutErrors` — assert it fails (project doesn't exist yet)
3. Write `shouldReturnHealthUp` — assert it fails
4. Create `ProcessedEventRepositoryTest.java`
5. Write `shouldReturnFalse_forUnseenKey` — assert it fails

### GREEN — Minimum code to pass

1. Create `services/notification-service/pom.xml` with all required dependencies
2. Create `NotificationServiceApplication.java` with `@SpringBootApplication`
3. Create `application.yml` with all config keys
4. Create `ProcessedEvent.java` JPA entity
5. Create `ProcessedEventRepository.java` with `existsByConsumerGroupAndMessageKey()`
6. Create `HealthController.java` returning `{"status":"UP"}`
7. Add `notification-service` to `infra/docker-compose.yml`

### REFACTOR — Quality pass

1. Add `@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"consumer_group", "message_key"}))` to `ProcessedEvent`
2. Add structured logging in `HealthController`
3. Verify Docker Compose `depends_on` ordering is correct
4. Run `/security-scan` on new service

---

## Implementation Reference

### NotificationServiceApplication

**File**: `services/notification-service/src/main/java/com/scheduler/notification/NotificationServiceApplication.java`

```java
// [TASK: ATOM-KAFKA-007]
package com.scheduler.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NotificationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
```

### ProcessedEvent Entity

**File**: `services/notification-service/src/main/java/com/scheduler/notification/domain/ProcessedEvent.java`

```java
// [TASK: ATOM-KAFKA-007]
package com.scheduler.notification.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "processed_events",
       uniqueConstraints = @UniqueConstraint(columnNames = {"consumer_group", "message_key"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consumer_group", nullable = false)
    private String consumerGroup;

    @Column(name = "message_key", nullable = false)
    private String messageKey;

    @Column(nullable = false)
    private Instant processedAt;

    @PrePersist
    void onCreate() {
        processedAt = Instant.now();
    }
}
```

### ProcessedEventRepository

**File**: `services/notification-service/src/main/java/com/scheduler/notification/repository/ProcessedEventRepository.java`

```java
// [TASK: ATOM-KAFKA-007]
package com.scheduler.notification.repository;

import com.scheduler.notification.domain.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    boolean existsByConsumerGroupAndMessageKey(String consumerGroup, String messageKey);
}
```

### HealthController

**File**: `services/notification-service/src/main/java/com/scheduler/notification/controller/HealthController.java`

```java
// [TASK: ATOM-KAFKA-007]
package com.scheduler.notification.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
public class HealthController {

    record HealthResponse(String status) {}

    @GetMapping("/health")
    public HealthResponse health() {
        log.debug("health check requested");
        return new HealthResponse("UP");
    }
}
```

### Docker Compose Addition

**File**: `infra/docker-compose.yml` (addition)

```yaml
  notification-service:
    build:
      context: ../services/notification-service
      dockerfile: Dockerfile
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/scheduler
      DB_USER: scheduler
      DB_PASSWORD: scheduler_dev
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      SCHEMA_REGISTRY_URL: http://schema-registry:8081
    ports:
      - "8090:8090"
    depends_on:
      kafka:
        condition: service_healthy
      postgres:
        condition: service_healthy
      schema-registry:
        condition: service_healthy
    networks:
      - scheduler-network
```

---

## Integration Points

**Depends on**: ATOM-PHASE1-001 (Docker Compose infra), ATOM-KAFKA-001 (`processed_events` table must exist via V012 migration)

**Enables**: ATOM-KAFKA-008 (`@KafkaListener` and dispatch logic added to this scaffold)

**Cascading updates required**:
- `infra/docker-compose.yml` — add notification-service container
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `services/notification-service/pom.xml` | New | Maven project with spring-kafka, JPA, SES, Twilio deps |
| `services/notification-service/src/main/java/com/scheduler/notification/NotificationServiceApplication.java` | New | Spring Boot entry point |
| `services/notification-service/src/main/java/com/scheduler/notification/domain/ProcessedEvent.java` | New | JPA entity for processed_events |
| `services/notification-service/src/main/java/com/scheduler/notification/repository/ProcessedEventRepository.java` | New | Deduplication query |
| `services/notification-service/src/main/java/com/scheduler/notification/controller/HealthController.java` | New | GET /health |
| `services/notification-service/src/main/resources/application.yml` | New | Kafka, DB, Schema Registry config |
| `infra/docker-compose.yml` | Modified | Add notification-service container |
| `services/notification-service/src/test/java/com/scheduler/notification/NotificationServiceSmokeIT.java` | New | Smoke + health tests |
| `services/notification-service/src/test/java/com/scheduler/notification/repository/ProcessedEventRepositoryTest.java` | New | Dedup repository tests |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] `GET /health` returns `{"status":"UP"}`
- [ ] Consumer group `notification-consumers` visible in Kafka after service starts
- [ ] `enable-auto-commit: false` in `application.yml`
- [ ] `specific.avro.reader: true` in consumer properties
- [ ] `hibernate.ddl-auto: validate` (not create/update)
- [ ] `ProcessedEventRepository.existsByConsumerGroupAndMessageKey()` tested for both true and false cases
- [ ] Docker Compose `depends_on` ordering verified
- [ ] Zero JPA queries without `tenant_id` in WHERE clause
- [ ] Zero industry-specific terms in package names or config keys
- [ ] `@PreAuthorize` present on every new `@RestController` method
- [ ] Outbox event written within `@Transactional` scope (if booking state mutated)
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: kafka-event-mesh | Phase: 3*
