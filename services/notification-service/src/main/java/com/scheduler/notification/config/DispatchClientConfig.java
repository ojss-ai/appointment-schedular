// TASK: ATOM-KAFKA-008 (mirrors apps/api DispatchClientConfig)
package com.scheduler.notification.config;

import com.twilio.http.TwilioRestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * External dispatch clients. Credentials come exclusively from environment
 * variables (AWS_* / TWILIO_*); sandbox-friendly defaults keep local startup
 * working without real credentials.
 */
@Configuration
public class DispatchClientConfig {

    @Bean
    public SesV2Client sesV2Client() {
        return SesV2Client.builder()
            .region(Region.of(envOrDefault("AWS_REGION", "us-east-1")))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
    }

    @Bean
    public TwilioRestClient twilioRestClient() {
        String sid = envOrDefault("TWILIO_ACCOUNT_SID", "ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
        String token = envOrDefault("TWILIO_AUTH_TOKEN", "sandbox-auth-token");
        return new TwilioRestClient.Builder(sid, token).build();
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
