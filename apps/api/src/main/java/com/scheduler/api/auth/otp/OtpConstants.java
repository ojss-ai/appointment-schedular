// TASK: P1-T06
package com.scheduler.api.auth.otp;

/** All OTP magic numbers in one place (SECURITY-SPEC 1.2). */
public final class OtpConstants {

    public static final int TTL_SECONDS = 300;        // 5 minutes
    public static final int RATE_LIMIT_WINDOW = 3600; // 1 hour
    public static final int RATE_LIMIT_MAX = 5;
    public static final int OTP_LENGTH = 6;
    /** Excludes ambiguous characters: 0, O, 1, I. */
    public static final String OTP_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    public static final String OTP_KEY_PREFIX = "otp:";
    public static final String RATE_KEY_PREFIX = "otp-rate:";

    private OtpConstants() {
    }
}
