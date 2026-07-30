// TASK: P1-T05 / ATOM-BOOKING-012
package com.scheduler.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Multi-Tenant Omni-Industry Scheduling Framework — core API.
 *
 * <p>Domain model is intentionally generic (Tenant / Location / Resource /
 * Service / Booking) per ADR-005. No industry-specific terms anywhere.
 *
 * <p>{@code @EnableScheduling} drives the PENDING_HOLD garbage collector
 * (ATOM-BOOKING-012).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class SchedulerApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchedulerApiApplication.class, args);
    }
}
