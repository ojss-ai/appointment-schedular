// TASK: P1-T08
package com.scheduler.api.config;

import com.twilio.http.TwilioRestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * External dispatch clients (ATOM-NOTIFICATION-DISPATCH-008).
 *
 * <p>All credentials come exclusively from environment variables:
 * AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY / AWS_REGION for SES and
 * TWILIO_ACCOUNT_SID / TWILIO_AUTH_TOKEN for Twilio. Sandbox-friendly
 * defaults keep local startup working without real credentials — dispatch
 * calls then fail gracefully as {@code DispatchResult.failure(...)}.
 */
@Configuration
public class DispatchClientConfig {

    @Bean
    public SesV2Client sesV2Client() {
        String region = envOrDefault("AWS_REGION", "us-east-1");
        return SesV2Client.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
    }

    @Bean
    public TwilioRestClient twilioRestClient() {
        // Twilio's published test credentials pattern; real values from env.
        String sid = envOrDefault("TWILIO_ACCOUNT_SID", "ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        String token = envOrDefault("TWILIO_AUTH_TOKEN", "sandbox-auth-token");
        return new TwilioRestClient.Builder(sid, token).build();
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
