// TASK: P1-T05
package com.scheduler.api.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.Map;

/**
 * Public health endpoints per API-SPEC section 8. Liveness is uncond-
 * itional; readiness pings PostgreSQL and Redis.
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class HealthController {

    private static final String VERSION = "1.0.0";

    private final DataSource dataSource;
    private final StringRedisTemplate redis;

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "version", VERSION);
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        boolean dbUp = pingDatabase();
        boolean redisUp = pingRedis();
        Map<String, String> body = Map.of(
            "status", (dbUp && redisUp) ? "UP" : "DOWN",
            "database", dbUp ? "UP" : "DOWN",
            "redis", redisUp ? "UP" : "DOWN");
        return (dbUp && redisUp)
            ? ResponseEntity.ok(body)
            : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    private boolean pingDatabase() {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(2);
        } catch (Exception e) {
            log.warn("Readiness: database ping failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean pingRedis() {
        try {
            var factory = redis.getConnectionFactory();
            if (factory == null) {
                return false;
            }
            try (var conn = factory.getConnection()) {
                return "PONG".equalsIgnoreCase(conn.ping());
            }
        } catch (Exception e) {
            log.warn("Readiness: redis ping failed: {}", e.getMessage());
            return false;
        }
    }
}
