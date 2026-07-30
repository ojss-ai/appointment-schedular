// TASK: ATOM-HOLIDAY-004
package com.scheduler.api.holiday;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A closed date for a Location. Recurring holidays block the same month/day
 * in every future year. Maps V005; UNIQUE(location_id, holiday_date).
 */
@Entity
@Table(name = "branch_holidays")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BranchHoliday {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Column(name = "holiday_date", nullable = false)
    private LocalDate holidayDate;

    @Column
    private String name;

    @Column(name = "is_recurring", nullable = false)
    @Builder.Default
    private boolean isRecurring = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * True when this holiday closes the location on {@code date}: either an
     * exact date match, or a recurring annual match on month/day for any
     * year at or after the anchor year.
     */
    public boolean blocks(LocalDate date) {
        if (holidayDate.equals(date)) {
            return true;
        }
        return isRecurring
            && !date.isBefore(holidayDate)
            && holidayDate.getMonth() == date.getMonth()
            && holidayDate.getDayOfMonth() == date.getDayOfMonth();
    }
}
