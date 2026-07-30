// TASK: P1-T06
package com.scheduler.api.auth.otp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Audit-trail row for an issued OTP (maps V003). Redis is the verification
 * and TTL source of truth; this row records who requested what and when.
 * The raw OTP lives only in the {@code @Transient} field so the caller can
 * deliver it — it is never persisted, only its bcrypt hash is.
 */
@Entity
@Table(name = "otp_records")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OtpRecord {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_USED = "USED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String identifier;

    @Column(name = "otp_hash", nullable = false)
    private String otpHash;

    @Column(nullable = false)
    private String channel; // 'EMAIL' or 'SMS'

    @Column(nullable = false)
    @Builder.Default
    private String status = STATUS_PENDING;

    @Column(name = "attempt_count", nullable = false)
    @Builder.Default
    private int attemptCount = 0;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Raw OTP for delivery to the user — never persisted. */
    @Transient
    private String rawOtp;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
