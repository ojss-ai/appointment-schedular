// TASK: ATOM-KAFKA-007
package com.scheduler.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumer-side deduplication record (NFR-2.1). Maps the shared
 * {@code processed_events} table (V013, docs/DATABASE-SCHEMA.md 2.12). The
 * UNIQUE (consumer_group, message_key) constraint is the hard guarantee; the
 * pre-check in the consumer is the fast path.
 */
@Entity
@Table(name = "processed_events",
       uniqueConstraints = @UniqueConstraint(columnNames = {"consumer_group", "message_key"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "consumer_group", nullable = false)
    private String consumerGroup;

    /** Unique per event — the payload eventId, not the partition key. */
    @Column(name = "message_key", nullable = false)
    private String messageKey;

    @Column(nullable = false)
    private String topic;

    @Column(name = "partition", nullable = false)
    private int partition;

    @Column(name = "offset_value", nullable = false)
    private long offsetValue;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @PrePersist
    void onCreate() {
        if (processedAt == null) {
            processedAt = Instant.now();
        }
    }
}
