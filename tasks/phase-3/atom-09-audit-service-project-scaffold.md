---
description: Spring Boot scaffold for the audit-service microservice — audit_writer role connection, processed_events JPA, Kafka consumer group, Docker Compose container
---

# ATOM-KAFKA-009: Audit Service Project Scaffold

**Status**: 🟡 Planned
**Feature**: kafka-event-mesh
**Phase**: 3 (Kafka)
**Tags**: [KAFKA]
**Complexity**: Low
**Agent**: coder
**Dependencies**: ATOM-PHASE1-001 (Docker Compose infra), ATOM-KAFKA-001 (audit_log table + audit_writer role)
**Blocks**: ATOM-KAFKA-010
**PR**: TBD

---

## Overview

This atom creates the `services/audit-service` Spring Boot microservice — the scaffold that ATOM-KAFKA-010 will fill with audit consumer logic. The critical constraint distinguishing this service from `notification-service` is its PostgreSQL connection: it must connect as the `audit_writer` role (INSERT-only on `audit_log`), never as the main application user. This enforces the HIPAA append-only audit trail at the DB connection level — even if a bug were introduced in the consumer code, the DB would reject any UPDATE or DELETE on `audit_log`.

---

## User Story

```
As a System
I want a standalone audit-service microservice connecting to PostgreSQL as the audit_writer role
So that the append-only HIPAA audit trail is enforced at the database connection level, independent of application logic
```

---

## Acceptance Criteria

- [ ] **AC-01**: `audit-service` starts without errors — `mvn spring-boot:run` succeeds
- [ ] **AC-02**: Service connects to Kafka broker on startup without `WARN` or `ERROR` connection logs
- [ ] **AC-03**: `GET /health` returns HTTP 200 with body `{"status":"UP"}`
- [ ] **AC-04**: Service is included in `infra/docker-compose.yml` as a separate container
- [ ] **AC-05**: PostgreSQL datasource is configured with the `audit_writer` role credentials — verified by checking the effective DB user via `SELECT current_user` in an integration test
- [ ] **AC-06**: `UPDATE audit_log SET what='test'` executed within `audit-service` JPA context throws `DataAccessException` (permission denied) — RLS enforcement verified at the application layer
- [ ] **AC-07**: Consumer group `audit-consumers` is visible in Kafka consumer groups list after service starts
- [ ] **AC-08 (Idempotency)**: `ProcessedEventRepository.existsByConsumerGroupAndMessageKey()` returns `false` for an unseen key and `true` for a seen key — same pattern as notification-service
- [ ] **AC-09 (Tenant isolation)**: `AuditLogRecord` entity has `tenant_id UUID NOT NULL` column — all audit records are tenant-scoped; all new JPA queries include `tenant_id` in WHERE clause
- [ ] **AC-10 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) in service name, package names, config keys, or endpoint paths

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `AuditServiceSmokeIT.java` — `shouldStartWithoutErrors` | `AuditServiceApplication.java` | 🔜 Planned |
| AC-03 | `AuditServiceSmokeIT.java` — `shouldReturnHealthUp` | `HealthController.java` | 🔜 Planned |
| AC-05 | `AuditServiceSmokeIT.java` — `shouldConnectAsAuditWriterRole` | `application.yml` datasource | 🔜 Planned |
| AC-06 | `AuditRlsIT.java` — `shouldRejectUpdate_whenConnectedAsAuditWriter` | `application.yml` datasource + V013 RLS | 🔜 Planned |
| AC-07 | `AuditServiceSmokeIT.java` — `shouldHaveAuditConsumersGroup` | `application.yml` kafka config | 🔜 Planned |
| AC-08 | `ProcessedEventRepositoryTest.java` — `shouldReturnFalse_forUnseenKey` | `ProcessedEventRepository.java` | 🔜 Planned |
| AC-09 | `AuditLogRecordTest.java` — `shouldHaveTenantIdNotNull` | `AuditLogRecord.java` | 🔜 Planned |
| AC-10 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 0 criteria rewritten, 0 marked TBD -->

---

## Technical Design

### Architecture

`audit-service` is a separate Spring Boot application (its own JAR, its own Docker container). Unlike `notification-service`, it connects to PostgreSQL using the `audit_writer` role — a dedicated DB user granted INSERT on `audit_log` and SEQUENCE usage only (no UPDATE, no DELETE, enforced by the RLS policy from V013 migration). This service also maintains its own `ProcessedEvent` JPA entity for the same `processed_events` table, but with consumer group `audit-consumers`. The service has no REST API beyond `/health`.

### Data Flow / Sequence

```
docker compose up
  → audit-service container starts
  → Spring Boot connects to Kafka (consumer group: audit-consumers)
  → Spring Boot connects to PostgreSQL as audit_writer role
  → Spring Boot connects to Schema Registry (Avro deserializer)
  → /health returns {"status":"UP"}
  [Consumer logic added in ATOM-KAFKA-010]
```

### File Structure

```
services/audit-service/
├── pom.xml                                       ← Spring Boot 3.x, spring-kafka, JPA
├── src/main/java/com/scheduler/audit/
│   ├── AuditServiceApplication.java              ← @SpringBootApplication
│   ├── controller/
│   │   └── HealthController.java                 ← GET /health
│   ├── domain/
│   │   ├── AuditLogRecord.java                   ← JPA entity for audit_log (INSERT only)
│   │   └── ProcessedEvent.java                   ← JPA entity for processed_events
│   └── repository/
│       ├── AuditLogRepository.java               ← INSERT only — no update/delete methods
│       └── ProcessedEventRepository.java         ← existsByConsumerGroupAndMessageKey()
└── src/main/resources/
    └── application.yml                           ← audit_writer datasource, kafka, schema-registry

infra/docker-compose.yml                          ← audit-service container definition
```

### Interface Contracts

```java
// AuditLogRecord JPA entity — maps to audit_log table (INSERT only)
@Entity
@Table(name = "audit_log")
public class AuditLogRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;                    // required — tenant isolation

    @Column(name = "who", nullable = false)
    private UUID who;                         // userId

    @Column(name = "what", nullable = false)
    private String what;                      // eventType

    @Column(name = "when_", nullable = false)
    private Instant when_;

    @Column(name = "booking_id")
    private UUID bookingId;                   // nullable

    @Column(name = "resource_id")
    private UUID resourceId;                  // nullable

    @Column(name = "ip_address", columnDefinition = "inet")
    private String ipAddress;                 // nullable

    @Column(columnDefinition = "jsonb")
    private Map<String, String> metadata;     // nullable
}

// AuditLogRepository — only save() is intended for use; no update/delete methods exposed
public interface AuditLogRepository extends JpaRepository<AuditLogRecord, Long> {
    // No custom queries — INSERT only via save()
    // findBy* available for test verification only
}

// ProcessedEventRepository — same interface as notification-service
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {
    boolean existsByConsumerGroupAndMessageKey(String consumerGroup, String messageKey);
}

// HealthController
@RestController
public class HealthController {
    @GetMapping("/health")
    HealthResponse health();
}
```

```yaml
# application.yml — key datasource config:
spring:
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/scheduler}
    username: ${AUDIT_DB_USER:audit_writer}       # audit_writer role — not main app user
    password: ${AUDIT_DB_PASSWORD:audit_writer_dev}
  jpa:
    hibernate.ddl-auto: validate
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:9092}
    consumer:
      group-id: audit-consumers
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: io.confluent.kafka.serializers.KafkaAvroDeserializer
      auto-offset-reset: earliest
      enable-auto-commit: false
      properties:
        schema.registry.url: ${SCHEMA_REGISTRY_URL:http://localhost:8081}
        specific.avro.reader: true
```

### Design Rationale

- **HIPAA append-only enforcement**: Connecting as `audit_writer` role (INSERT only) ensures that even a buggy consumer implementation cannot UPDATE or DELETE audit records. The PostgreSQL RLS from V013 is the last-resort defense.
- **Why a separate service (not a module in notification-service)**: Audit has different compliance, retention, and access control requirements than notification. Mixing them in one process risks one service's failure taking down the other's audit trail.
- **Why `audit_writer` at the datasource level (not via `SET ROLE` at runtime)**: Runtime role switching is error-prone and easy to forget. Datasource-level configuration makes the constraint operational from the first connection attempt.
- **Why `enable-auto-commit: false`**: Same rationale as notification-service — manual offset commit ensures the `audit_log` INSERT and offset commit are atomically ordered; a crash before acknowledge causes safe redelivery caught by the idempotency check.
- **ADR-003**: The consumer-side `processed_events` deduplication completes the at-least-once → effectively-once conversion for the audit trail.

---

## Test Strategy

**Test type**: Integration (Testcontainers + PostgreSQL + Kafka) and Unit (JUnit 5)

```
- shouldStartWithoutErrors:
    Given: Testcontainers PostgreSQL + Kafka running; V013 migration applied
    Assert: Spring application context loads without exceptions

- shouldReturnHealthUp:
    Given: application started
    Assert: GET /health returns HTTP 200, body = {"status":"UP"}

- shouldConnectAsAuditWriterRole:
    Given: datasource connected
    Assert: EntityManager.createNativeQuery("SELECT current_user").getSingleResult() = "audit_writer"

- shouldRejectUpdate_whenConnectedAsAuditWriter:
    Given: 1 audit_log row exists; audit-service JPA context active
    Assert: EntityManager.createNativeQuery("UPDATE audit_log SET what='X' WHERE id=1").executeUpdate()
            throws DataAccessException with cause: permission denied

- shouldHaveAuditConsumersGroup:
    Given: application started
    Assert: Kafka AdminClient.listConsumerGroups() includes "audit-consumers"

- shouldMapAuditLogTable:
    Given: audit_log table exists; connected as audit_writer
    Assert: auditLogRepository.save(new AuditLogRecord{tenantId, who, what, when_,...}) returns saved entity

- shouldReturnFalse_forUnseenKey:
    Given: empty processed_events for consumer_group='audit-consumers'
    Assert: existsByConsumerGroupAndMessageKey("audit-consumers", "new-key") = false

- shouldBeIdempotent_onDuplicateMessage:
    Given: same message key saved twice to processed_events (consumer_group='audit-consumers')
    Assert: second save throws DataIntegrityViolationException (unique constraint)
```

**Coverage requirements**:
- Line coverage ≥ 80% on `AuditLogRepository` and `ProcessedEventRepository`
- Idempotency test required
- `audit_writer` role rejection test (AC-06) is mandatory — no merge without it

---

## Implementation Constraints

- PostgreSQL datasource must use `audit_writer` role — never the main `scheduler` user
- `audit_log` is INSERT-only: `AuditLogRepository` must expose no `update`, `deleteById`, or custom UPDATE queries
- `hibernate.ddl-auto: validate` — never `create` or `update`; schema managed by Flyway only
- `AuditLogRecord.tenantId` must be `NOT NULL` — every audit record is tenant-scoped
- `enable-auto-commit: false` — manual offset commit required
- `specific.avro.reader: true` — use generated Avro class
- Consumers must check `processed_events` table before processing (NFR-2.1)
- No `System.out.println` — use SLF4J
- Service must be in `docker-compose.yml` with `depends_on: [kafka, postgres, schema-registry]`
- `audit_writer` role credentials in environment variables — never hardcoded
- Outbox event written within `@Transactional` scope (if booking state mutated)

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `AuditServiceSmokeIT.java` with Testcontainers (PostgreSQL + Kafka) + V013 migration
2. Write `shouldStartWithoutErrors` — assert it fails (project doesn't exist yet)
3. Write `shouldConnectAsAuditWriterRole` — assert it fails
4. Write `shouldRejectUpdate_whenConnectedAsAuditWriter` — assert it fails (would pass as main user)
5. Create `ProcessedEventRepositoryTest.java`
6. Write `shouldReturnFalse_forUnseenKey` — assert it fails

### GREEN — Minimum code to pass

1. Create `services/audit-service/pom.xml` with spring-kafka, JPA, Confluent deps
2. Create `AuditServiceApplication.java`
3. Create `application.yml` with `AUDIT_DB_USER=audit_writer` datasource
4. Create `AuditLogRecord.java` JPA entity (INSERT-only by convention)
5. Create `AuditLogRepository.java` — no custom update queries
6. Create `ProcessedEvent.java` and `ProcessedEventRepository.java`
7. Create `HealthController.java`
8. Add `audit-service` to `infra/docker-compose.yml`

### REFACTOR — Quality pass

1. Add `@Table(indexes = ...)` annotation to `AuditLogRecord` matching `idx_audit_tenant_when`
2. Add structured logging for service startup
3. Verify Docker Compose `depends_on` ordering correct
4. Run `/security-scan` on new service

---

## Implementation Reference

### AuditServiceApplication

**File**: `services/audit-service/src/main/java/com/scheduler/audit/AuditServiceApplication.java`

```java
// [TASK: ATOM-KAFKA-009]
package com.scheduler.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AuditServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AuditServiceApplication.class, args);
    }
}
```

### AuditLogRecord Entity

**File**: `services/audit-service/src/main/java/com/scheduler/audit/domain/AuditLogRecord.java`

```java
// [TASK: ATOM-KAFKA-009]
package com.scheduler.audit.domain;

import com.vladmihalcea.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "who", nullable = false)
    private UUID who;

    @Column(name = "what", nullable = false)
    private String what;

    @Column(name = "when_", nullable = false)
    private Instant when_;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "ip_address", columnDefinition = "inet")
    private String ipAddress;

    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> metadata;
}
```

### ProcessedEvent Entity (audit-service copy)

**File**: `services/audit-service/src/main/java/com/scheduler/audit/domain/ProcessedEvent.java`

```java
// [TASK: ATOM-KAFKA-009]
package com.scheduler.audit.domain;

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

### Docker Compose Addition

**File**: `infra/docker-compose.yml` (addition)

```yaml
  audit-service:
    build:
      context: ../services/audit-service
      dockerfile: Dockerfile
    environment:
      DB_URL: jdbc:postgresql://postgres:5432/scheduler
      AUDIT_DB_USER: audit_writer
      AUDIT_DB_PASSWORD: audit_writer_dev
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      SCHEMA_REGISTRY_URL: http://schema-registry:8081
    ports:
      - "8091:8091"
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

**Depends on**: ATOM-PHASE1-001 (Docker Compose infra), ATOM-KAFKA-001 (`audit_log` table + `audit_writer` role created in V013)

**Enables**: ATOM-KAFKA-010 (`AuditConsumer` logic added to this scaffold)

**Cascading updates required**:
- `infra/docker-compose.yml` — add audit-service container
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `services/audit-service/pom.xml` | New | Maven project with spring-kafka, JPA deps |
| `services/audit-service/src/main/java/com/scheduler/audit/AuditServiceApplication.java` | New | Spring Boot entry point |
| `services/audit-service/src/main/java/com/scheduler/audit/domain/AuditLogRecord.java` | New | JPA entity for audit_log (INSERT only) |
| `services/audit-service/src/main/java/com/scheduler/audit/domain/ProcessedEvent.java` | New | JPA entity for processed_events |
| `services/audit-service/src/main/java/com/scheduler/audit/repository/AuditLogRepository.java` | New | INSERT-only repository |
| `services/audit-service/src/main/java/com/scheduler/audit/repository/ProcessedEventRepository.java` | New | Dedup query |
| `services/audit-service/src/main/java/com/scheduler/audit/controller/HealthController.java` | New | GET /health |
| `services/audit-service/src/main/resources/application.yml` | New | audit_writer datasource, kafka, schema-registry |
| `infra/docker-compose.yml` | Modified | Add audit-service container |
| `services/audit-service/src/test/java/com/scheduler/audit/AuditServiceSmokeIT.java` | New | Smoke + RLS tests |
| `services/audit-service/src/test/java/com/scheduler/audit/repository/ProcessedEventRepositoryTest.java` | New | Dedup repo tests |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] Datasource uses `audit_writer` role — verified by `SELECT current_user` test (AC-05)
- [ ] UPDATE on `audit_log` throws `DataAccessException` when executed through audit-service context (AC-06)
- [ ] Consumer group `audit-consumers` visible in Kafka after service starts
- [ ] `GET /health` returns `{"status":"UP"}`
- [ ] `enable-auto-commit: false` in `application.yml`
- [ ] `specific.avro.reader: true` in consumer properties
- [ ] `hibernate.ddl-auto: validate` — never create/update
- [ ] `AuditLogRecord.tenantId` is `NOT NULL`
- [ ] `AuditLogRepository` has no update or delete query methods
- [ ] `audit_writer` credentials in environment variables — never hardcoded
- [ ] Zero JPA queries without `tenant_id` in WHERE clause
- [ ] Zero industry-specific terms in any identifier or API path
- [ ] `@PreAuthorize` present on every new `@RestController` method
- [ ] Outbox event written within `@Transactional` scope (if booking state mutated)
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: kafka-event-mesh | Phase: 3*
