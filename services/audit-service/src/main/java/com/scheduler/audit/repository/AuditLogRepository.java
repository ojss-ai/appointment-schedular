// TASK: ATOM-KAFKA-009 / ATOM-KAFKA-010
package com.scheduler.audit.repository;

import com.scheduler.audit.domain.AuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Append-only access to {@code audit_log}. Production code uses
 * {@code save()} exclusively; the finders exist for test verification only
 * (tenant-filtered per ADR-004) and fail at runtime under the
 * {@code audit_writer} role, which has no SELECT grant. NO update or delete
 * methods may ever be added here.
 */
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {

    List<AuditLogEntry> findByTenantIdAndBookingId(UUID tenantId, UUID bookingId);

    long countByTenantIdAndBookingId(UUID tenantId, UUID bookingId);
}
