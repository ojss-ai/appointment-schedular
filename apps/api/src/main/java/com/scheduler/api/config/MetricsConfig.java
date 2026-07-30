// [TASK: ATOM-INFRA-508]
package com.scheduler.api.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Observability wiring (ATOM-INFRA-508). Registers the Micrometer
 * {@link TimedAspect} so {@code @Timed}-annotated service methods (e.g.
 * {@code SlotCalculatorService.computeAvailableSlots}) publish the
 * {@code scheduling.slot.calc.duration} timer + histogram buckets to the
 * already-configured Prometheus registry. spring-boot-starter-aop is on the
 * classpath, so the aspect proxies Spring-managed beans transparently — no
 * changes to the service constructor or method bodies.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
