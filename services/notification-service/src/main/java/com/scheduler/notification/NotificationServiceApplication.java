// TASK: ATOM-KAFKA-007
package com.scheduler.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Event-driven notification dispatcher. Consumes
 * {@code tenant.bookings.lifecycle} (consumer group
 * {@code notification-consumers}) and sends SES email / Twilio SMS. No REST
 * API beyond {@code /health}; schema is owned by apps/api Flyway migrations
 * (ddl-auto: validate).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class NotificationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationServiceApplication.class, args);
    }
}
