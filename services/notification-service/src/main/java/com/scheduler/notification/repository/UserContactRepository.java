// TASK: ATOM-KAFKA-008
package com.scheduler.notification.repository;

import com.scheduler.notification.domain.UserContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Recipient lookup — tenant-filtered per ADR-004, read-only. */
public interface UserContactRepository extends JpaRepository<UserContact, UUID> {

    Optional<UserContact> findByIdAndTenantId(UUID id, UUID tenantId);
}
