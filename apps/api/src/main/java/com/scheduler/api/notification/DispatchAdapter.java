// TASK: P1-T08
package com.scheduler.api.notification;

/** Strategy contract for OTP/magic-link delivery channels. */
public interface DispatchAdapter {

    DispatchResult sendOtp(String recipient, String rawOtp, String tenantName);

    DispatchResult sendMagicLink(String recipient, String magicLink, String tenantName);

    boolean supports(String identifierType);
}
