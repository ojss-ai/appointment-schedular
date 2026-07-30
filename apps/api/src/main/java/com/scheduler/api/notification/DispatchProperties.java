// TASK: P1-T08
package com.scheduler.api.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dispatch configuration bound from {@code app.dispatch.*}. Values come from
 * SES_FROM_ADDRESS / TWILIO_FROM_NUMBER environment variables. Credentials
 * are never configured here — the SDKs read them from the environment.
 */
@ConfigurationProperties(prefix = "app.dispatch")
public record DispatchProperties(
    String sesFromAddress,
    String twilioFromNumber
) {
}
