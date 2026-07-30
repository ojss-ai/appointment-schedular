// TASK: ATOM-KAFKA-008
package com.scheduler.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Read-only projection of the {@code users} table (V002) used to resolve the
 * notification recipient — BookingLifecycleEvent intentionally carries only
 * the userId, never contact details (data minimisation). Immutable by
 * design: no setters, never saved by this service.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
public class UserContact {

    public static final String TYPE_EMAIL = "EMAIL";
    public static final String TYPE_PHONE = "PHONE";

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Email address or E.164 phone number. */
    @Column(nullable = false)
    private String identifier;

    @Column(name = "identifier_type", nullable = false)
    private String identifierType;
}
