// TASK: ATOM-ANALYTICS-001
package com.scheduler.api.analytics.repository;

import com.scheduler.api.analytics.AuditLogEntry;
import com.scheduler.api.analytics.record.BookingPatternRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only analytics access to the append-only {@code audit_log} ledger.
 *
 * <p>Tenant isolation: this is a system-level aggregate that never crosses
 * tenant boundaries within a bucket — {@code tenant_id} is part of the
 * GROUP BY key on every row, and downstream consumers
 * (ATOM-ANALYTICS-003) filter strictly by {@code tenantId} before use.
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {

    /**
     * Aggregates confirmed bookings for one UTC day into
     * (resource, tenant, service type, ISO day-of-week, hour-of-day) buckets.
     *
     * <p>Notes vs. the atom spec draft:
     * <ul>
     *   <li>Half-open {@code occurred_at} range instead of {@code DATE(...)}
     *       so the V015 partial index is usable (AC-06).</li>
     *   <li>{@code service_type_id} is not an {@code audit_log} column; it is
     *       resolved from the event metadata when present, else via a
     *       tenant-filtered join to {@code bookings}.</li>
     *   <li>Pattern buckets use the booking's {@code slot_start} (when the
     *       reservation takes place) rather than the audit write time, since
     *       slot-window utilization is what the optimization heuristics
     *       reason about; falls back to {@code occurred_at}.</li>
     *   <li>{@code EXTRACT(ISODOW ...)} so 1 = Monday … 7 = Sunday, matching
     *       the documented {@code BookingPatternRecord} contract.</li>
     * </ul>
     */
    @Query(value = """
        SELECT
            a.resource_id AS "resourceId",
            a.tenant_id AS "tenantId",
            CAST(COALESCE(NULLIF(a.metadata->>'serviceTypeId', ''),
                          CAST(b.service_type_id AS text)) AS uuid) AS "serviceTypeId",
            CAST(EXTRACT(ISODOW FROM COALESCE(b.slot_start, a.occurred_at)) AS int) AS "dayOfWeek",
            CAST(EXTRACT(HOUR   FROM COALESCE(b.slot_start, a.occurred_at)) AS int) AS "hourOfDay",
            COUNT(*) AS "bookingCount"
        FROM audit_log a
        LEFT JOIN bookings b
               ON b.id = a.booking_id
              AND b.tenant_id = a.tenant_id
        WHERE a.event_type = 'BookingConfirmed'
          AND a.occurred_at >= :dayStart
          AND a.occurred_at <  :dayEnd
          AND a.tenant_id IS NOT NULL
          AND a.resource_id IS NOT NULL
        GROUP BY 1, 2, 3, 4, 5
        """, nativeQuery = true)
    List<BookingPatternRow> aggregateBookingPatterns(
        @Param("dayStart") Instant dayStart,
        @Param("dayEnd") Instant dayEnd);
}
