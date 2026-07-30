// TASK: ATOM-KAFKA-002
package com.scheduler.api.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Data access for the outbox staging table. Only {@code save()} is used by
 * production code; the finder exists for integration-test verification and is
 * tenant-filtered per ADR-004.
 */
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findByTenantIdAndAggregateId(UUID tenantId, UUID aggregateId);
}
