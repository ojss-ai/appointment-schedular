// TASK: P1-T06
package com.scheduler.api.auth.otp;

/** Outcome of an OTP verification attempt. */
public record VerificationResult(Status status) {

    public enum Status { SUCCESS, INVALID, EXPIRED }

    public static VerificationResult success() {
        return new VerificationResult(Status.SUCCESS);
    }

    public static VerificationResult invalid() {
        return new VerificationResult(Status.INVALID);
    }

    public static VerificationResult expired() {
        return new VerificationResult(Status.EXPIRED);
    }

    public boolean isSuccess() {
        return status == Status.SUCCESS;
    }
}
