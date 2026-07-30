// TASK: ATOM-KAFKA-007
package com.scheduler.notification.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Liveness endpoint — the service is otherwise purely event-driven. */
@RestController
public class HealthController {

    record HealthResponse(String status) {}

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP");
    }
}
