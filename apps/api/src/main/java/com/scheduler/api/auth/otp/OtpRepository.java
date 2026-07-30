// TASK: P1-T06
package com.scheduler.api.auth.otp;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Every mutation carries {@code tenantId} in the WHERE clause (ADR-004) so a
 * cross-tenant identifier collision can never touch another tenant's rows.
 */
public interface OtpRepository extends JpaRepository<OtpRecord, UUID> {

    /** Marks the pending OTP consumed after a successful verification. */
    @Modifying
    @Query("""
        UPDATE OtpRecord o SET o.status = 'USED'
        WHERE o.tenantId = :tenantId AND o.identifier = :identifier
          AND o.status = 'PENDING'
        """)
    void markUsed(@Param("tenantId") UUID tenantId,
                  @Param("identifier") String identifier);

    /**
     * Invalidates the pending OTP after a failed attempt
     * (SECURITY-SPEC 1.2: invalidation on first failure).
     */
    @Modifying
    @Query("""
        UPDATE OtpRecord o SET o.status = 'EXPIRED', o.attemptCount = o.attemptCount + 1
        WHERE o.tenantId = :tenantId AND o.identifier = :identifier
          AND o.status = 'PENDING'
        """)
    void markFailed(@Param("tenantId") UUID tenantId,
                    @Param("identifier") String identifier);
}
