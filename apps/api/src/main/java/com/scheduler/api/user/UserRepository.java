// TASK: P1-T09
package com.scheduler.api.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** All queries are tenant-filtered (ADR-004) — zero exceptions. */
public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByTenantIdAndIdentifier(UUID tenantId, String identifier);

    /**
     * Lazily provisions the passwordless user on first successful OTP
     * verification. Tenant-scoped by construction.
     */
    default User findOrCreate(String identifier, UUID tenantId) {
        return findByTenantIdAndIdentifier(tenantId, identifier)
            .orElseGet(() -> save(User.builder()
                .tenantId(tenantId)
                .identifier(identifier)
                .identifierType(identifier.contains("@") ? "EMAIL" : "PHONE")
                .role("customer")
                .status("active")
                .build()));
    }
}
