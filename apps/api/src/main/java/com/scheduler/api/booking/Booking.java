// TASK: ATOM-BOOKING-009 / ATOM-BOOKING-010 / ATOM-BOOKING-011
package com.scheduler.api.booking;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * A reservation of a Resource for a Service at a time slot (Glossary:
 * Booking). Maps V010 + V011. State machine:
 *
 * <pre>
 * PENDING_HOLD ──confirm──▶ CONFIRMED ──cancel──▶ CANCELLED
 *      │                        └────────────────▶ COMPLETED / NO_SHOW
 *      └── GC delete after holdExpiresAt (ATOM-BOOKING-012)
 * </pre>
 *
 * The {@code extension} JSONB column stores tenant intake form responses
 * verbatim; core logic never reads it (ADR-005).
 */
@Entity
@Table(name = "bookings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Booking {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "service_type_id", nullable = false)
    private UUID serviceTypeId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    @Builder.Default
    private String status = BookingStatus.PENDING_HOLD;

    @Column(name = "slot_start", nullable = false)
    private Instant slotStart;

    @Column(name = "slot_end", nullable = false)
    private Instant slotEnd;

    /** slot_start − bufferBeforeMin. */
    @Column(name = "buffer_start", nullable = false)
    private Instant bufferStart;

    /** slot_end + bufferAfterMin. */
    @Column(name = "buffer_end", nullable = false)
    private Instant bufferEnd;

    /** Null once confirmed. */
    @Column(name = "hold_expires_at")
    private Instant holdExpiresAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancellation_reason")
    private String cancellationReason;

    /** Tenant intake form responses — opaque to core logic (ADR-005). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private String extension = "{}";

    @Column(name = "confirmation_code")
    private String confirmationCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
