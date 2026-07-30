// TASK: ATOM-KAFKA-008 (mirrors apps/api P1-T08)
package com.scheduler.notification.dispatch;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code app.dispatch.*}; values come from SES_FROM_ADDRESS /
 * TWILIO_FROM_NUMBER. Credentials never live here — the SDKs read
 * AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / TWILIO_ACCOUNT_SID /
 * TWILIO_AUTH_TOKEN from the environment.
 */
@ConfigurationProperties(prefix = "app.dispatch")
public record DispatchProperties(
    String sesFromAddress,
    String twilioFromNumber
) {
}
