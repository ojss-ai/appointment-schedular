---
description: Testcontainers chaos test suite verifying outbox pattern reliability under Kafka downtime, DB rollback, Debezium restart, and slow consumer lag
---

# ATOM-KAFKA-011: Outbox Chaos Test

**Status**: 🟡 Planned
**Feature**: kafka-event-mesh
**Phase**: 3 (Kafka)
**Tags**: [KAFKA] [TEST]
**Complexity**: High
**Agent**: testgen
**Dependencies**: ATOM-KAFKA-006 (BookingService emits outbox events), ATOM-KAFKA-003 (Debezium connector configured)
**Blocks**: None
**PR**: TBD

---

## Overview

This atom verifies that the transactional outbox pattern (ADR-003) is reliable under four failure conditions: Kafka unavailability during booking confirmation, DB rollback mid-transaction, Debezium restart mid-relay, and slow consumer lag with a 100-event backlog. All four scenarios are implemented as Testcontainers integration tests that start/stop actual Docker containers to simulate failure conditions. Passing this test suite is a prerequisite for Phase 3 sign-off.

---

## User Story

```
As a System
I want the outbox pattern to guarantee at-least-once event delivery even under infrastructure failures
So that no booking lifecycle event is ever silently lost regardless of Kafka or Debezium availability
```

---

## Acceptance Criteria

- [ ] **AC-01 (Kafka down)**: Kafka container stopped → `confirmBooking()` completes with DB status=CONFIRMED and outbox row written → Kafka restarted → event appears in `tenant.bookings.lifecycle` topic (eventually consistent, within 60 seconds)
- [ ] **AC-02 (DB rollback)**: Exception injected after `UPDATE bookings` but before `outboxService.writeBookingEvent()` returns → transaction rolls back → booking status unchanged AND zero outbox rows written
- [ ] **AC-03 (Debezium restart)**: Outbox row inserted → Debezium container stopped before relay → Debezium restarted → exactly 1 Kafka message produced (no duplicate, no loss)
- [ ] **AC-04 (Slow consumer)**: 100 booking events published → consumer processes 1 per second (artificial delay) → all 100 events eventually processed with zero data loss
- [ ] **AC-05 (Idempotency)**: After Debezium restart (AC-03), Kafka message key matches `partition_key` column value — no phantom or corrupted messages; `processed_events` row count equals distinct message keys
- [ ] **AC-06**: No events lost in any of the 4 scenarios — final event count in Kafka equals events written to outbox
- [ ] **AC-07 (Tenant isolation)**: All new JPA queries include `tenant_id` in WHERE clause — zero cross-tenant rows returned in any scenario
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) in any test class, test data, or assertion message

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `OutboxChaosIT.java` — `shouldDeliverEvent_afterKafkaRecovery` | `BookingService` + Debezium | 🔜 Planned |
| AC-02 | `OutboxChaosIT.java` — `shouldRollbackOutbox_whenDbRollsBack` | `BookingService` + `OutboxService` | 🔜 Planned |
| AC-03 | `OutboxChaosIT.java` — `shouldNotDuplicate_afterDebeziumRestart` | Debezium connector | 🔜 Planned |
| AC-04 | `OutboxChaosIT.java` — `shouldProcessAllEvents_withSlowConsumer` | Consumer + Kafka | 🔜 Planned |
| AC-05 | `OutboxChaosIT.java` — `shouldPreserveMessageKey_afterDebeziumRestart` | Debezium EventRouter | 🔜 Planned |
| AC-06 | `OutboxChaosIT.java` — assertion in each scenario | All | 🔜 Planned |
| AC-07 | TBD | TBD | 🔜 Planned |
| AC-08 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 0 criteria rewritten, 0 marked TBD -->

---

## Technical Design

### Architecture

The chaos test class uses Testcontainers to run PostgreSQL, Kafka, Kafka Connect (with Debezium), and the notification-service container. Each test scenario calls Testcontainers APIs (`container.stop()`, `container.start()`) to simulate failure. The test class interacts with `BookingService` via the Spring application context (loaded via `@SpringBootTest`) and verifies outcomes by querying the database and polling a `KafkaConsumer`. Test isolation: each test method starts with a clean `outbox` table and a clean Kafka consumer offset.

### Data Flow / Sequence

```
Scenario 1 — Kafka down:
  kafkaContainer.stop()
  → confirmBooking() — DB commit succeeds (bookings + outbox written)
  → Debezium attempts relay, Kafka unreachable, buffers in WAL
  kafkaContainer.start()
  → Debezium resumes, relays buffered outbox row
  → Assert: Kafka message received within 60 seconds

Scenario 2 — DB rollback:
  @Transactional test wrapper
  → confirmBooking() with injected RuntimeException after UPDATE
  → transaction rolls back
  → Assert: bookings.status = PENDING_HOLD (unchanged); outbox.count = 0

Scenario 3 — Debezium restart:
  INSERT INTO outbox(...)
  debeziumContainer.stop()
  debeziumContainer.start()
  → Debezium resumes from WAL position
  → Assert: exactly 1 Kafka message (no duplicate)

Scenario 4 — Slow consumer:
  Publish 100 BookingLifecycleEvents
  Consumer configured with 1-second processing delay
  → Poll consumer until all 100 messages acknowledged (timeout: 300 seconds)
  → Assert: count = 100; no duplicates
```

### File Structure

```
apps/api/src/test/java/com/scheduler/
└── kafka/OutboxChaosIT.java            ← 4 chaos scenario integration tests

apps/api/src/test/java/com/scheduler/
└── kafka/TestContainerConfig.java      ← shared Testcontainers setup (PostgreSQL + Kafka + Connect)
```

### Interface Contracts

No new production interfaces — this is a pure test atom.

```java
// OutboxChaosIT — test class skeleton (no method bodies)
@SpringBootTest
@Testcontainers
@Tag("chaos")
class OutboxChaosIT {

    @Test
    void shouldDeliverEvent_afterKafkaRecovery();

    @Test
    void shouldRollbackOutbox_whenDbRollsBack();

    @Test
    void shouldNotDuplicate_afterDebeziumRestart();

    @Test
    void shouldProcessAllEvents_withSlowConsumer();

    @Test
    void shouldPreserveMessageKey_afterDebeziumRestart();
}
```

### Design Rationale

- **ADR-003 (Transactional Outbox)**: This test suite is the formal verification of ADR-003's reliability claims. The four scenarios cover all failure modes identified during ADR decision: Kafka unavailability, DB rollback atomicity, CDC relay deduplication, and consumer lag.
- **Why Testcontainers (not mocks)**: Chaos scenarios require actual container lifecycle control — mocks cannot simulate network partition or WAL slot recovery. Testcontainers provides real Docker containers with programmatic start/stop.
- **Why 100-event slow consumer (not 10)**: Ten events could complete within a single Kafka poll batch. One hundred events forces the consumer through multiple poll cycles, verifying no offset loss across batch boundaries.
- **Why 60-second timeout for Kafka recovery**: After a Kafka container restart, ZooKeeper/KRaft re-election takes time; Debezium reconnect takes time. 60 seconds is conservative but deterministic in CI.
- **Why test isolation via clean outbox table**: Leftover rows from a previous test would cause false-positive or false-negative Kafka message counts. Each test method truncates `outbox` (or uses a unique `partition_key` prefix) and resets the consumer offset.

---

## Test Strategy

**Test type**: Integration (Testcontainers — PostgreSQL + Kafka + Kafka Connect/Debezium)

```
- shouldDeliverEvent_afterKafkaRecovery:
    Given: Kafka container stopped; confirmBooking() called
    Assert phase 1: booking.status = CONFIRMED in DB; outbox has 1 row with status=PENDING
    When: Kafka container restarted
    Assert phase 2: within 60 seconds, KafkaConsumer.poll() receives message with
                    key = bookingId; outbox row status eventually = RELAYED

- shouldRollbackOutbox_whenDbRollsBack:
    Given: BookingService instrumented to throw RuntimeException after UPDATE bookings
    When: confirmBooking() called
    Assert: booking.status = PENDING_HOLD (no change); outbox.count() = 0

- shouldNotDuplicate_afterDebeziumRestart:
    Given: 1 outbox row inserted; Debezium container stopped
    When: Debezium container restarted
    Assert: KafkaConsumer.poll() receives exactly 1 message for that partition_key
            (poll with 30-second window, verify no second message arrives)

- shouldProcessAllEvents_withSlowConsumer:
    Given: 100 BookingLifecycleEvents produced to tenant.bookings.lifecycle
           consumer configured with Thread.sleep(1000) per message
    Assert: within 300 seconds, consumer has processed all 100 messages
            processed_events.count(consumer_group='notification-consumers') = 100
            no duplicate rows in processed_events

- shouldPreserveMessageKey_afterDebeziumRestart:
    Given: outbox row with partition_key='test-booking-uuid-123' inserted; Debezium restarted
    Assert: Kafka message key = 'test-booking-uuid-123' (not null, not corrupted)

- shouldBeIdempotent_onDuplicateMessage:
    Given: same Kafka message key manually published twice
    Assert: processed_events has exactly 1 row for that key after consumer processes both
```

**Coverage requirements**:
- All 4 chaos scenarios (AC-01 through AC-04) must pass for Phase 3 sign-off
- Idempotency test (AC-05 via AC-03) is mandatory
- No test timeouts shorter than 60 seconds for network recovery scenarios

---

## Implementation Constraints

- Must use Testcontainers (not mocks) for container lifecycle control
- Each test method must start with clean state — truncate `outbox` table or use unique `partition_key` prefix
- Kafka recovery timeout: 60 seconds maximum (AC-01)
- Slow consumer timeout: 300 seconds maximum (AC-04)
- No `System.out.println` — use SLF4J in test helpers
- Consumers must check `processed_events` table before processing (verified indirectly via no-duplicate assertion)
- Audit log is INSERT-only — chaos tests must not attempt UPDATE on `audit_log`
- Direct Kafka writes are BLOCKED in production code — chaos tests may produce test messages directly via `KafkaTemplate` for scenarios that don't go through BookingService
- Test class must be tagged `@Tag("chaos")` so it can be excluded from fast unit test runs (`mvn test -Dgroups=!chaos`)
- Outbox event written within `@Transactional` scope (if booking state mutated)

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `OutboxChaosIT.java` with Testcontainers setup (PostgreSQL + Kafka + Kafka Connect)
2. Write `shouldDeliverEvent_afterKafkaRecovery` with container stop/start logic — assert it fails (Debezium not yet configured to reconnect)
3. Write `shouldRollbackOutbox_whenDbRollsBack` — assert it fails (exception injection not wired)
4. Write `shouldNotDuplicate_afterDebeziumRestart` — assert it fails
5. Write `shouldProcessAllEvents_withSlowConsumer` — assert it fails (no slow consumer config)

### GREEN — Minimum code to pass

1. Configure Testcontainers to start PostgreSQL + Kafka + KafkaConnect containers with Debezium plugin
2. Register Debezium connector against Testcontainers Kafka Connect endpoint
3. Wire exception injection for rollback test (Spring AOP test interceptor or direct `BookingService` subclass mock)
4. Configure slow consumer via `@KafkaListener` factory override in test config
5. Implement poll-until-timeout helpers for eventual consistency assertions

### REFACTOR — Quality pass

1. Extract `TestContainerConfig.java` for shared container setup (reused by ATOM-KAFKA-012)
2. Add `@Tag("chaos")` annotation to class
3. Add descriptive failure messages to all assertions (assert message includes expected vs actual counts)
4. Document timeout values with rationale in test Javadoc

---

## Implementation Reference

### OutboxChaosIT

**File**: `apps/api/src/test/java/com/scheduler/kafka/OutboxChaosIT.java`

```java
// [TASK: ATOM-KAFKA-011]
package com.scheduler.kafka;

import org.junit.jupiter.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@Tag("chaos")
class OutboxChaosIT {

    private static final Logger log = LoggerFactory.getLogger(OutboxChaosIT.class);

    @Autowired
    private BookingService bookingService;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @BeforeEach
    void cleanState() {
        // Each test starts with clean outbox and processed_events
        outboxRepository.deleteAll();
        processedEventRepository.deleteAll();
    }

    // Scenario 1: Kafka down at time of booking confirmation
    @Test
    @DisplayName("booking confirmed in DB when Kafka is down; event delivered after Kafka recovers")
    void shouldDeliverEvent_afterKafkaRecovery() {
        // 1. Stop Kafka container (shared via TestContainerConfig)
        TestContainerConfig.kafkaContainer.stop();
        log.info("Kafka container stopped — simulating downtime");

        // 2. confirmBooking() — DB commit succeeds (bookings + outbox written; Debezium buffers)
        UUID bookingId = createAndConfirmBooking();

        // Assert phase 1: booking confirmed, outbox row present
        assertThat(outboxRepository.findByAggregateId(bookingId)).hasSize(1);
        assertThat(outboxRepository.findByAggregateId(bookingId).get(0).getStatus())
            .isEqualTo("PENDING");

        // 3. Restart Kafka container
        TestContainerConfig.kafkaContainer.start();
        log.info("Kafka container restarted — Debezium should relay buffered row");

        // 4. Assert phase 2: message arrives in Kafka within 60 seconds
        await().atMost(60, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                long messageCount = pollKafkaForMessage("tenant.bookings.lifecycle", bookingId.toString());
                assertThat(messageCount)
                    .as("expected 1 Kafka message for bookingId=%s after Kafka recovery", bookingId)
                    .isEqualTo(1L);
            });
    }

    // Scenario 2: DB rollback mid-transaction
    @Test
    @DisplayName("booking status and outbox both rolled back when exception thrown mid-transaction")
    void shouldRollbackOutbox_whenDbRollsBack() {
        // Inject exception after UPDATE bookings, before writeBookingEvent() returns
        // Use test-only BookingService override or AOP interceptor
        UUID bookingId = createPendingHoldBooking();
        String originalStatus = bookingRepository.findById(bookingId).get().getStatus().name();

        assertThatThrownBy(() -> bookingService.confirmBookingWithInjectedFailure(tenantId, bookingId))
            .isInstanceOf(RuntimeException.class);

        // Assert: booking status unchanged (still PENDING_HOLD)
        assertThat(bookingRepository.findById(bookingId).get().getStatus().name())
            .as("booking status must be unchanged after rollback")
            .isEqualTo(originalStatus);

        // Assert: zero outbox rows
        assertThat(outboxRepository.findByAggregateId(bookingId))
            .as("outbox must have zero rows after rollback")
            .isEmpty();
    }

    // Scenario 3: Debezium restart mid-relay
    @Test
    @DisplayName("outbox row relayed exactly once after Debezium restart — no duplicate")
    void shouldNotDuplicate_afterDebeziumRestart() {
        // 1. Insert outbox row (bypass BookingService for isolation)
        UUID partitionKey = UUID.randomUUID();
        insertOutboxRowDirectly(partitionKey);

        // 2. Stop Debezium before relay
        TestContainerConfig.debeziumContainer.stop();

        // 3. Restart Debezium
        TestContainerConfig.debeziumContainer.start();

        // 4. Poll Kafka — assert exactly 1 message in 30-second window
        await().atMost(30, TimeUnit.SECONDS)
            .untilAsserted(() ->
                assertThat(pollKafkaForMessage("tenant.bookings.lifecycle", partitionKey.toString()))
                    .as("expected exactly 1 message after Debezium restart")
                    .isEqualTo(1L)
            );

        // Wait additional 5 seconds to verify no second message arrives
        assertThat(pollKafkaForMessage("tenant.bookings.lifecycle", partitionKey.toString()))
            .as("no duplicate message expected after Debezium restart")
            .isEqualTo(1L);
    }

    // Scenario 4: Slow consumer with high lag
    @Test
    @DisplayName("all 100 events processed with no data loss even with 1-second per-message delay")
    void shouldProcessAllEvents_withSlowConsumer() {
        // 1. Produce 100 booking events directly to Kafka
        int eventCount = 100;
        for (int i = 0; i < eventCount; i++) {
            publishTestBookingEvent("tenant.bookings.lifecycle",
                UUID.randomUUID().toString());
        }

        // 2. Consumer processes with 1-second delay (configured in TestContainerConfig)
        // 3. Wait up to 300 seconds for all 100 to be processed
        await().atMost(300, TimeUnit.SECONDS)
            .untilAsserted(() -> {
                long processed = processedEventRepository
                    .countByConsumerGroup("notification-consumers");
                assertThat(processed)
                    .as("expected all %d events processed, got %d", eventCount, processed)
                    .isEqualTo(eventCount);
            });

        // Assert: no duplicates in processed_events
        long distinctKeys = processedEventRepository
            .countDistinctMessageKeysByConsumerGroup("notification-consumers");
        assertThat(distinctKeys)
            .as("no duplicate processed_events rows expected")
            .isEqualTo(eventCount);
    }

    // --- helpers ---

    private long pollKafkaForMessage(String topic, String messageKey) {
        // Poll Kafka consumer group for messages matching messageKey
        // Returns count of messages with matching key
        // Implementation uses AdminClient + KafkaConsumer API
        throw new UnsupportedOperationException("implement in GREEN phase");
    }

    private UUID createAndConfirmBooking() {
        throw new UnsupportedOperationException("implement in GREEN phase");
    }

    private UUID createPendingHoldBooking() {
        throw new UnsupportedOperationException("implement in GREEN phase");
    }

    private void insertOutboxRowDirectly(UUID partitionKey) {
        throw new UnsupportedOperationException("implement in GREEN phase");
    }

    private void publishTestBookingEvent(String topic, String key) {
        throw new UnsupportedOperationException("implement in GREEN phase");
    }
}
```

### TestContainerConfig (shared)

**File**: `apps/api/src/test/java/com/scheduler/kafka/TestContainerConfig.java`

```java
// [TASK: ATOM-KAFKA-011] Shared Testcontainers config — reused by ATOM-KAFKA-012
package com.scheduler.kafka;

import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

public class TestContainerConfig {

    // Shared containers — started once per test suite run
    public static final KafkaContainer kafkaContainer =
        new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"))
            .withReuse(false);

    public static final PostgreSQLContainer<?> postgresContainer =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:15"))
            .withDatabaseName("scheduler_test")
            .withUsername("scheduler")
            .withPassword("scheduler_test");

    public static final GenericContainer<?> debeziumContainer =
        new GenericContainer<>(DockerImageName.parse("debezium/connect:2.4"))
            .withEnv("BOOTSTRAP_SERVERS", kafkaContainer.getBootstrapServers())
            .withExposedPorts(8083);

    static {
        kafkaContainer.start();
        postgresContainer.start();
        debeziumContainer.start();
    }
}
```

---

## Integration Points

**Depends on**: ATOM-KAFKA-006 (BookingService emits events — needed for scenario 1 end-to-end), ATOM-KAFKA-003 (Debezium connector — needed for scenarios 1 and 3)

**Enables**: Phase 3 sign-off (all 4 chaos scenarios must pass)

**Cascading updates required**:
- `tasks/MASTER-TASK-LIST.md` — mark atom complete
- Phase 3 milestone: chaos suite passing is a hard gate

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/test/java/com/scheduler/kafka/OutboxChaosIT.java` | New | 4 chaos scenario integration tests |
| `apps/api/src/test/java/com/scheduler/kafka/TestContainerConfig.java` | New | Shared Testcontainers setup (PostgreSQL + Kafka + Connect) |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] All 4 chaos scenarios pass (AC-01 through AC-04)
- [ ] Idempotency test (AC-03/AC-05) present and passing
- [ ] No data loss in slow consumer scenario (AC-04) — count assertion at 100
- [ ] No duplicate messages in Debezium restart scenario (AC-03)
- [ ] DB rollback scenario (AC-02) verified — zero outbox rows after rollback
- [ ] `@Tag("chaos")` on test class — excluded from fast unit test profile
- [ ] Test timeouts documented: 60s for Kafka recovery, 300s for slow consumer
- [ ] `TestContainerConfig.java` reusable by ATOM-KAFKA-012
- [ ] No direct Kafka writes in production code paths exercised by tests
- [ ] Zero JPA queries without `tenant_id` in WHERE clause
- [ ] Zero industry-specific terms in any identifier or API path
- [ ] `@PreAuthorize` present on every new `@RestController` method
- [ ] Outbox event written within `@Transactional` scope (if booking state mutated)
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: kafka-event-mesh | Phase: 3*
