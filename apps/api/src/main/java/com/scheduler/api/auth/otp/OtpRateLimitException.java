// TASK: P1-T06
package com.scheduler.api.auth.otp;

/** Thrown when an identifier exceeds 5 OTP requests per hour — mapped to HTTP 429. */
public class OtpRateLimitException extends RuntimeException {

    public OtpRateLimitException(String message) {
        super(message);
    }
}
