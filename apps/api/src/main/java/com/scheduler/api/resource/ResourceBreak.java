// TASK: ATOM-RESOURCE-002
package com.scheduler.api.resource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalTime;
import java.util.UUID;

/**
 * A recurring weekly break window for a Resource (local wall-clock times).
 * Maps V008. day_of_week: 0=Sunday .. 6=Saturday.
 */
@Entity
@Table(name = "resource_breaks")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceBreak {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "break_start", nullable = false)
    private LocalTime breakStart;

    @Column(name = "break_end", nullable = false)
    private LocalTime breakEnd;

    @Column
    private String label;
}
