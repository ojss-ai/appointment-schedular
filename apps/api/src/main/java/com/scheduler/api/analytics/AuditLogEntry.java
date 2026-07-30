// TASK: ATOM-ANALYTICS-001
package com.scheduler.api.analytics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * Read-only mapping of the append-only {@code audit_log} ledger (V014,
 * docs/DATABASE-SCHEMA.md 2.13) for the analytics ingestion query.
 *
 * <p>The API service never writes this table — rows are inserted exclusively
 * by the audit-service consumer under the {@code audit_writer} role. This
 * entity exists only so Spring Data can anchor the native aggregate query
 * in {@link com.scheduler.api.analytics.repository.AuditLogRepository};
 * {@code @Immutable} makes any accidental write path a Hibernate no-op.
 */
@Entity
@Table(name = "audit_log")
@Immutable
@Getter
@NoArgsConstructor
public class AuditLogEntry {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "resource_id")
    private UUID resourceId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
