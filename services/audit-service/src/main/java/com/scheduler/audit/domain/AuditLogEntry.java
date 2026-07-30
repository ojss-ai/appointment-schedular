// TASK: ATOM-KAFKA-009
package com.scheduler.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable audit record — maps {@code audit_log} (V014, per
 * docs/DATABASE-SCHEMA.md 2.13). INSERT-only: no setters, no update path;
 * the {@code audit_writer} role has no UPDATE/DELETE grant and RLS allows
 * INSERT only. Client-side UUID generation avoids any post-insert SELECT
 * (which the role could not perform).
 */
@Entity
@Table(name = "audit_log")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "booking_id", nullable = false)
    private UUID bookingId;

    @Column(name = "resource_id")
    private UUID resourceId;

    /** HIPAA: who performed the action. */
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** HIPAA: what happened. */
    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "previous_status")
    private String previousStatus;

    @Column(name = "new_status")
    private String newStatus;

    /** INET column — write-side cast keeps the JDBC bind a plain string. */
    @Column(name = "ip_address", columnDefinition = "inet")
    @ColumnTransformer(write = "?::inet")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private String metadata = "{}";

    /** HIPAA: when it happened. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
