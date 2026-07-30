// TASK: ATOM-SERVICE-003
package com.scheduler.api.servicetype;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A bookable service definition: duration, pre/post buffers, allowed
 * resource types and the JSON Schema driving the dynamic intake form
 * (Glossary: Service). Maps V009. The {@code intakeSchema} document is
 * stored verbatim; core logic only hands it to the schema validator.
 */
@Entity
@Table(name = "service_types")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceType {

    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_INACTIVE = "inactive";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column
    private String description;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(name = "buffer_before_min", nullable = false)
    @Builder.Default
    private int bufferBeforeMin = 0;

    @Column(name = "buffer_after_min", nullable = false)
    @Builder.Default
    private int bufferAfterMin = 0;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "allowed_resource_types", nullable = false, columnDefinition = "text[]")
    @Builder.Default
    private List<String> allowedResourceTypes = new ArrayList<>();

    /** JSON Schema draft-07 document for the customer intake form. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "intake_schema", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private String intakeSchema = "{}";

    @Column(nullable = false)
    @Builder.Default
    private String status = STATUS_ACTIVE;

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
