// TASK: P1-T09
package com.scheduler.api.auth.dto;

public record VerifyOtpResponse(
    String status,  // "SUCCESS" | "OTP_INVALID" | "OTP_EXPIRED"
    String token,   // null on failure
    String message  // null on success
) {
}
