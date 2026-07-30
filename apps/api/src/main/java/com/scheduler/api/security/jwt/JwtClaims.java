// TASK: P1-T07
package com.scheduler.api.security.jwt;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Parsed, validated token data. {@code tenantId} is never null. */
public record JwtClaims(
    UUID tenantId,
    UUID userId,
    List<String> roleClaims,
    String jti,
    Instant issuedAt,
    Instant expiresAt
) {
}
