// TASK: ATOM-SLOT-006 / ATOM-BOOKING-009 / ATOM-BOOKING-012
package com.scheduler.api.booking;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Booking queries. Every business query is tenant-filtered (ADR-004); the
 * single exception is the hold-GC delete, a system-level batch operation
 * that intentionally spans tenants (ATOM-BOOKING-012 AC-07).
 *
 * <p>NO {@code @Cacheable} anywhere in this interface — booking data must
 * always be fresh (ATOM-SLOT-008 AC-05).
 */
public interface BookingRepository extends JpaRepository<Booking, UUID>,
        JpaSpecificationExecutor<Booking> {

    Optional<Booking> findByIdAndTenantId(UUID id, UUID tenantId);

    /**
     * Pessimistic conflict probe for hold creation (ADR-002): rows whose
     * buffer window overlaps the candidate window are locked FOR UPDATE.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT b FROM Booking b
        WHERE b.resourceId = :resourceId
          AND b.tenantId   = :tenantId
          AND b.status IN :statuses
          AND b.bufferStart < :bufferEnd
          AND b.bufferEnd   > :bufferStart
        """)
    List<Booking> findConflictingBookingsForUpdate(
        @Param("resourceId") UUID resourceId,
        @Param("tenantId") UUID tenantId,
        @Param("statuses") List<String> statuses,
        @Param("bufferStart") Instant bufferStart,
        @Param("bufferEnd") Instant bufferEnd);

    /**
     * Slot-subtraction feed (ATOM-SLOT-006). Leads with (tenant_id,
     * location_id, slot_start) to ride idx_bookings_tenant_location_start
     * (NFR-1.3). Never cached.
     */
    List<Booking> findByTenantIdAndLocationIdAndResourceIdAndStatusInAndSlotStartBetween(
        UUID tenantId, UUID locationId, UUID resourceId, List<String> statuses,
        Instant from, Instant to);

    long countByResourceIdAndTenantIdAndStatus(UUID resourceId, UUID tenantId, String status);

    // --- Confirmation code support (ATOM-BOOKING-010) ---

    boolean existsByTenantIdAndConfirmationCode(UUID tenantId, String confirmationCode);

    long countByTenantIdAndConfirmationCodeStartingWith(UUID tenantId, String prefix);

    // --- Hold GC (ATOM-BOOKING-012) — system-level, deliberately cross-tenant ---

    @Modifying
    @Query(value = """
        DELETE FROM bookings
        WHERE id IN (
            SELECT id FROM bookings
            WHERE status = 'PENDING_HOLD'
              AND hold_expires_at < :now
            LIMIT :batchSize
        )
        """, nativeQuery = true)
    int deleteExpiredHolds(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
