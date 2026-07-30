// TASK: P1-T05
package com.scheduler.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis wiring (Lettuce via spring-data-redis). Used for OTP hash storage
 * with TTL and the per-identifier rate-limit counters (ATOM-OTP-REDIS-006);
 * later phases add slot-cache and the distributed-lock fallback (ADR-002).
 */
@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
