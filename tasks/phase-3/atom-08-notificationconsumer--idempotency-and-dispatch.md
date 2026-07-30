---
description: NotificationConsumer @KafkaListener with processed_events idempotency check, SES/Twilio dispatch, DLQ routing, and manual offset commit
---

# ATOM-KAFKA-008: NotificationConsumer — Idempotency and Dispatch

**Status**: ✅ Complete
**Feature**: kafka-event-mesh
**Phase**: 3 (Kafka)
**Tags**: [KAFKA]
**Complexity**: High
**Agent**: coder
**Dependencies**: ATOM-KAFKA-007 (notification-service scaffold), ATOM-KAFKA-006 (BookingService emits events)
**Blocks**: ATOM-KAFKA-012
**PR**: TBD

---

## Overview

This atom implements the `NotificationConsumer` — a `@KafkaListener` on `tenant.bookings.lifecycle` that dispatches email (AWS SES) and SMS (Twilio) notifications for `BookingConfirmed` and `BookingCancelled` events. Before any dispatch, the consumer checks the `processed_events` table for a matching `(consumerGroup, messageKey)` entry — if found, the message is skipped immediately. After successful dispatch, the `ProcessedEvent` row is saved and the Kafka offset is manually committed. On SES/Twilio failure, the consumer retries up to 3 times; on the 3rd failure, the message is routed to `tenant.bookings.lifecycle.DLQ`.

---

## User Story

```
As a Booking User
I want to receive a confirmation email/SMS when my booking is confirmed and a cancellation notice when it is cancelled
So that I am always informed of my booking status in near-real-time
```

---

## Acceptance Criteria

- [ ] **AC-01**: `BookingConfirmed` event → email and/or SMS sent to the user (based on user's contact identifier type)
- [ ] **AC-02**: `BookingCancelled` event → cancellation notice sent (email or SMS)
- [ ] **AC-03 (Idempotency)**: Duplicate message delivery (same message key) → exactly 1 email/SMS sent; `processed_events` has exactly 1 row for `(notification-consumers, messageKey)` — second delivery is skipped at the idempotency check
- [ ] **AC-04**: SES/Twilio transient error → consumer retries up to 3 times before routing to DLQ
- [ ] **AC-05**: After 3 failures, message key visible in `tenant.bookings.lifecycle.DLQ` topic
- [ ] **AC-06**: Kafka offset committed only after `processed_events` row is saved (`acknowledgment.acknowledge()` called last)
- [ ] **AC-07**: Consumer crash before `acknowledgment.acknowledge()` → message redelivered on restart; idempotency check prevents duplicate notification (only 1 email sent total)
- [ ] **AC-08 (Tenant isolation)**: All new JPA queries include `tenant_id` in WHERE clause — zero cross-tenant rows returned; notification template variables include `tenantId` — no cross-tenant data leaks in notification content
- [ ] **AC-09 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) in consumer class, template keys, or log messages

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `NotificationConsumerIT.java` — `shouldSendConfirmation_onBookingConfirmed` | `NotificationConsumer.sendConfirmation()` | 🔜 Planned |
| AC-03 | `NotificationConsumerIT.java` — `shouldSkipDuplicate_whenAlreadyProcessed` | `NotificationConsumer.onBookingEvent()` | 🔜 Planned |
| AC-04 | `NotificationConsumerIT.java` — `shouldRetryThreeTimes_onSesError` | `NotificationConsumer` retry config | 🔜 Planned |
| AC-05 | `NotificationConsumerIT.java` — `shouldRouteToDlq_afterThreeFailures` | `KafkaListenerErrorHandler` | 🔜 Planned |
| AC-06 | `NotificationConsumerIT.java` — `shouldCommitOffset_afterSave` | `NotificationConsumer.onBookingEvent()` | 🔜 Planned |
| AC-07 | `ConsumerIdempotencyIT.java` (ATOM-KAFKA-012) — `shouldSendOnce_afterCrashAndRedelivery` | `processed_events` check | 🔜 Planned |
| AC-08 | TBD | TBD | 🔜 Planned |
| AC-09 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 0 criteria rewritten, 0 marked TBD -->

---

## Technical Design

### Architecture

`NotificationConsumer` is a `@KafkaListener` method inside the `notification-service`. It processes messages with manual acknowledgment (`AckMode.MANUAL_IMMEDIATE`). The idempotency check, dispatch, `processed_events` save, and offset commit are all wrapped in a single `@Transactional` method — if the DB save fails after dispatch, the transaction rolls back and the offset is not committed, causing a safe redelivery. The idempotency check at the top of the method ensures the redelivered message is a no-op.

### Data Flow / Sequence

```
Kafka delivers ConsumerRecord to NotificationConsumer.onBookingEvent()
  1. Check processed_events: existsByConsumerGroupAndMessageKey("notification-consumers", record.key())
     → if true: log "duplicate skipped"; acknowledgment.acknowledge(); return
  2. switch(record.value().getEventType()):
       "BookingConfirmed" → sendConfirmation(event)   [SES email + Twilio SMS]
       "BookingCancelled" → sendCancellation(event)   [SES email]
  3. processedEventsRepository.save(ProcessedEvent{consumerGroup, messageKey=record.key()})
  4. acknowledgment.acknowledge()                     [manual offset commit]

On SES/Twilio error (step 2):
  → Spring Kafka retry (up to 3 attempts, exponential backoff)
  → After 3rd failure: DefaultErrorHandler routes to DLQ topic
```

### File Structure

```
services/notification-service/src/main/java/com/scheduler/notification/
├── consumer/
│   └── NotificationConsumer.java           ← @KafkaListener, idempotency, dispatch
├── service/
│   ├── EmailDispatchService.java           ← AWS SES integration
│   └── SmsDispatchService.java             ← Twilio integration
├── config/
│   └── KafkaConsumerConfig.java            ← AckMode, error handler, DLQ routing
└── domain/
    └── ProcessedEvent.java                 ← (from ATOM-KAFKA-007)

services/notification-service/src/test/java/com/scheduler/notification/
└── consumer/NotificationConsumerIT.java    ← integration tests
```

### Interface Contracts

```java
// Consumer — method signatures only
@Component
public class NotificationConsumer {

    @KafkaListener(
        topics = "tenant.bookings.lifecycle",
        groupId = "notification-consumers",
        containerFactory = "bookingEventListenerContainerFactory"
    )
    @Transactional
    public void onBookingEvent(
        ConsumerRecord<String, BookingLifecycleEvent> record,
        Acknowledgment acknowledgment
    );
}

// Dispatch service interfaces
public interface EmailDispatchService {
    void sendConfirmation(BookingLifecycleEvent event);
    void sendCancellation(BookingLifecycleEvent event);
}

public interface SmsDispatchService {
    void sendConfirmation(BookingLifecycleEvent event);
}

// Kafka consumer config — factory and error handler
@Configuration
public class KafkaConsumerConfig {
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, BookingLifecycleEvent>
        bookingEventListenerContainerFactory(
            ConsumerFactory<String, BookingLifecycleEvent> consumerFactory
        );
}
```

### Design Rationale

- **ADR-003 (Transactional Outbox)**: The consumer-side counterpart to the outbox pattern — at-least-once delivery from Debezium means duplicates are possible; the `processed_events` check converts at-least-once into effectively-once for notification dispatch.
- **Why `AckMode.MANUAL_IMMEDIATE`**: Prevents Spring from auto-committing the offset before the `processed_events` save completes. A crash between dispatch and DB save would otherwise result in a lost notification with a committed offset.
- **Why DLQ after 3 retries (not infinite retry)**: Infinite retry on a bad message can block the partition, starving all subsequent messages. The DLQ preserves the message for manual inspection and replay.
- **Why exponential backoff on retry**: SES/Twilio rate limits respond better to back-off than to immediate re-hammer.
- **NFR-2.1 compliance**: The `processed_events` deduplication table combined with `AckMode.MANUAL_IMMEDIATE` is the enforcement mechanism for idempotent consumers.

---

## Test Strategy

**Test type**: Integration (Testcontainers + Kafka + PostgreSQL) with mocked SES/Twilio

```
- shouldSendConfirmation_onBookingConfirmed:
    Given: BookingLifecycleEvent with eventType='BookingConfirmed' published to tenant.bookings.lifecycle
    Assert: EmailDispatchService.sendConfirmation() called once; SmsDispatchService.sendConfirmation() called once

- shouldSendCancellation_onBookingCancelled:
    Given: BookingLifecycleEvent with eventType='BookingCancelled' published
    Assert: EmailDispatchService.sendCancellation() called once

- shouldSkipDuplicate_whenAlreadyProcessed:
    Given: ProcessedEvent row exists for (notification-consumers, messageKey)
    When: same message delivered again
    Assert: EmailDispatchService.sendConfirmation() never called (mock verifies 0 invocations)
            processed_events row count unchanged

- shouldRetryThreeTimes_onSesError:
    Given: EmailDispatchService.sendConfirmation() throws SesException on first 2 calls
    Assert: method called 3 times total; on 3rd success, processed_events row saved

- shouldRouteToDlq_afterThreeFailures:
    Given: EmailDispatchService.sendConfirmation() always throws SesException
    Assert: after 3 retries, message visible in tenant.bookings.lifecycle.DLQ
            processed_events has 0 rows for that key

- shouldCommitOffset_afterSave:
    Given: successful dispatch + processed_events save
    Assert: Acknowledgment.acknowledge() called; consumer group lag = 0 for that partition

- shouldBeIdempotent_onDuplicateMessage:
    Given: same Kafka message key delivered twice
    Assert: processed_events has exactly 1 row for that key;
            EmailDispatchService called exactly once total
```

**Coverage requirements**:
- Line coverage ≥ 80% on `NotificationConsumer`
- Idempotency test is mandatory — no merge without it
- DLQ routing test is mandatory

---

## Implementation Constraints

- `processed_events` table must be checked BEFORE any dispatch — idempotency check is the first statement
- `acknowledgment.acknowledge()` must be the LAST statement in the happy path
- `AckMode.MANUAL_IMMEDIATE` must be set in `KafkaConsumerConfig` — never `BATCH` or `AUTO`
- Consumers must check `processed_events` table before processing (NFR-2.1)
- Direct Kafka writes are BLOCKED — DLQ routing via `DefaultErrorHandler`, not manual produce
- Audit log is INSERT-only — `NotificationConsumer` never touches `audit_log`
- No `System.out.println` — use SLF4J structured logging
- SES/Twilio credentials must be in environment variables — never hardcoded
- `@Transactional` on `onBookingEvent()` ensures DB save and offset commit are atomic on success
- All JPA queries on `processed_events` must include both `consumer_group` and `message_key` — no full table scans
- Outbox event written within `@Transactional` scope (if booking state mutated)

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `NotificationConsumerIT.java` with Testcontainers (Kafka + PostgreSQL) + Mockito mocks for SES/Twilio
2. Write `shouldSendConfirmation_onBookingConfirmed` — assert it fails (no consumer yet)
3. Write `shouldSkipDuplicate_whenAlreadyProcessed` — assert it fails
4. Write `shouldRouteToDlq_afterThreeFailures` — assert it fails

### GREEN — Minimum code to pass

1. Create `KafkaConsumerConfig.java` with `ConcurrentKafkaListenerContainerFactory` + `AckMode.MANUAL_IMMEDIATE` + `DefaultErrorHandler` with DLQ routing
2. Create `NotificationConsumer.java` with `@KafkaListener`, idempotency check, switch dispatch, `processed_events` save, acknowledge
3. Create `EmailDispatchService.java` stub (SES integration body — minimum to pass tests with mock)
4. Create `SmsDispatchService.java` stub (Twilio integration body)

### REFACTOR — Quality pass

1. Add structured logging for each dispatch: `log.info("notification dispatched: eventType={}, userId={}, channel={}", ...)`
2. Extract template key constants to avoid stringly-typed values
3. Add retry backoff configuration (`RetryTopicConfiguration` or `FixedBackOff`)
4. Run `/security-scan` on `NotificationConsumer`

---

## Implementation Reference

### NotificationConsumer

**File**: `services/notification-service/src/main/java/com/scheduler/notification/consumer/NotificationConsumer.java`

```java
// [TASK: ATOM-KAFKA-008]
package com.scheduler.notification.consumer;

import com.scheduler.avro.BookingLifecycleEvent;
import com.scheduler.notification.domain.ProcessedEvent;
import com.scheduler.notification.repository.ProcessedEventRepository;
import com.scheduler.notification.service.EmailDispatchService;
import com.scheduler.notification.service.SmsDispatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class NotificationConsumer {

    private static final String CONSUMER_GROUP = "notification-consumers";

    private final ProcessedEventRepository processedEventsRepository;
    private final EmailDispatchService emailDispatchService;
    private final SmsDispatchService smsDispatchService;

    /**
     * Consumes booking lifecycle events and dispatches notifications.
     * Idempotency enforced via processed_events table (NFR-2.1, ADR-003).
     * Manual offset commit via AckMode.MANUAL_IMMEDIATE.
     */
    @KafkaListener(
        topics = "tenant.bookings.lifecycle",
        groupId = CONSUMER_GROUP,
        containerFactory = "bookingEventListenerContainerFactory"
    )
    @Transactional
    public void onBookingEvent(
            ConsumerRecord<String, BookingLifecycleEvent> record,
            Acknowledgment acknowledgment) {

        String messageKey = record.key();

        // Idempotency check — must be first (NFR-2.1, ADR-003)
        if (processedEventsRepository.existsByConsumerGroupAndMessageKey(CONSUMER_GROUP, messageKey)) {
            log.info("duplicate event skipped: consumerGroup={}, messageKey={}", CONSUMER_GROUP, messageKey);
            acknowledgment.acknowledge();
            return;
        }

        BookingLifecycleEvent event = record.value();
        log.info("processing booking event: eventType={}, bookingId={}, tenantId={}",
            event.getEventType(), event.getBookingId(), event.getTenantId());

        // Dispatch notification based on event type
        switch (event.getEventType()) {
            case "BookingConfirmed" -> {
                emailDispatchService.sendConfirmation(event);
                smsDispatchService.sendConfirmation(event);
            }
            case "BookingCancelled" -> emailDispatchService.sendCancellation(event);
            default -> log.debug("unhandled event type for notification dispatch: {}", event.getEventType());
        }

        // Save deduplication record — must precede acknowledge
        processedEventsRepository.save(ProcessedEvent.builder()
            .consumerGroup(CONSUMER_GROUP)
            .messageKey(messageKey)
            .build());

        // Manual offset commit — last statement (AckMode.MANUAL_IMMEDIATE)
        acknowledgment.acknowledge();
        log.info("notification dispatched and offset committed: messageKey={}", messageKey);
    }
}
```

### KafkaConsumerConfig

**File**: `services/notification-service/src/main/java/com/scheduler/notification/config/KafkaConsumerConfig.java`

```java
// [TASK: ATOM-KAFKA-008]
package com.scheduler.notification.config;

import com.scheduler.avro.BookingLifecycleEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.properties.schema.registry.url}")
    private String schemaRegistryUrl;

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BookingLifecycleEvent>
            bookingEventListenerContainerFactory(
                ConsumerFactory<String, BookingLifecycleEvent> consumerFactory,
                KafkaTemplate<String, BookingLifecycleEvent> kafkaTemplate) {

        ConcurrentKafkaListenerContainerFactory<String, BookingLifecycleEvent> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // Manual offset commit — required for idempotency pattern (NFR-2.1)
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        // DLQ routing after 3 retries with 1-second intervals
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            new org.springframework.kafka.listener.DeadLetterPublishingRecoverer(kafkaTemplate),
            new FixedBackOff(1000L, 3L)
        );
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
```

---

## Integration Points

**Depends on**: ATOM-KAFKA-007 (notification-service scaffold, ProcessedEventRepository), ATOM-KAFKA-006 (BookingService emits events to topic)

**Enables**: ATOM-KAFKA-012 (consumer idempotency tests exercise this consumer)

**Cascading updates required**:
- `docs/KAFKA-SPEC.md` — document consumer group `notification-consumers` and DLQ routing in §5
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `services/notification-service/src/main/java/com/scheduler/notification/consumer/NotificationConsumer.java` | New | @KafkaListener with idempotency + dispatch |
| `services/notification-service/src/main/java/com/scheduler/notification/service/EmailDispatchService.java` | New | AWS SES email dispatch |
| `services/notification-service/src/main/java/com/scheduler/notification/service/SmsDispatchService.java` | New | Twilio SMS dispatch |
| `services/notification-service/src/main/java/com/scheduler/notification/config/KafkaConsumerConfig.java` | New | AckMode.MANUAL_IMMEDIATE + DLQ error handler |
| `services/notification-service/src/test/java/com/scheduler/notification/consumer/NotificationConsumerIT.java` | New | Integration tests |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] Idempotency test (AC-03) present and passing — no merge without it
- [ ] DLQ routing test (AC-05) present and passing
- [ ] `processed_events` checked BEFORE dispatch (first statement)
- [ ] `acknowledgment.acknowledge()` is LAST statement in happy path
- [ ] `AckMode.MANUAL_IMMEDIATE` set in `KafkaConsumerConfig`
- [ ] `@Transactional` on `onBookingEvent()`
- [ ] SES/Twilio credentials from environment variables — no hardcoding
- [ ] No direct Kafka writes; DLQ routing via `DefaultErrorHandler`
- [ ] Zero JPA queries without `tenant_id` in WHERE clause
- [ ] Zero industry-specific terms in any identifier or API path
- [ ] `@PreAuthorize` present on every new `@RestController` method
- [ ] Outbox event written within `@Transactional` scope (if booking state mutated)
- [ ] NFR-2.1 referenced in consumer Javadoc
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: kafka-event-mesh | Phase: 3*
