// TASK: P1-T09
package com.scheduler.api.auth.dto;

import java.time.Instant;

/** The raw identifier is never echoed back — always masked (AC-08). */
public record RequestOtpResponse(
    String status,           // "OTP_SENT"
    String maskedIdentifier, // e.g. "us***@example.com"
    Instant expiresAt
) {
}
