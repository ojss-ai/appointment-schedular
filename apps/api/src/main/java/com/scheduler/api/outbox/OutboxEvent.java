// TASK: ATOM-KAFKA-002
package com.scheduler.api.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * One row per domain event, staged in the same ACID transaction as the
 * business state change (ADR-003 transactional outbox). Debezium relays
 * INSERTs on this table to Kafka — application code NEVER produces to Kafka
 * directly from a business transaction. Maps V012.
 *
 * <p>The {@code id} is assigned via {@code UUID.randomUUID()} by
 * {@link OutboxService} before insert (never {@code @GeneratedValue}) so the
 * value is known to the caller pre-commit for distributed tracing. The
 * {@link Persistable} contract keeps Spring Data on the persist path (no
 * merge SELECT) despite the pre-assigned id.
 */
@Entity
@Table(name = "outbox")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent implements Persistable<UUID> {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_PUBLISHED = "PUBLISHED";
    public static final String STATUS_FAILED = "FAILED";

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "aggregate_type", nullable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String topic;

    @Column(name = "partition_key", nullable = false)
    private String partitionKey;

    /** JSON matching the BookingLifecycleEvent Avro schema exactly. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(nullable = false)
    @Builder.Default
    private String status = STATUS_PENDING;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Transient
    @Builder.Default
    private boolean newRow = true;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        newRow = false;
    }

    @Override
    public boolean isNew() {
        return newRow;
    }
}
