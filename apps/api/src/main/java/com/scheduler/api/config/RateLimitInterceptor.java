// [TASK: ATOM-SEC-504]
package com.scheduler.api.config;

import com.scheduler.api.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Redis fixed-window rate limiter (SECURITY-SPEC 4.1). Keyed per authenticated
 * JWT user; the booking-hold path gets a tighter budget than the general API.
 * OTP endpoints are rate-limited separately in {@code OtpService} and are not
 * routed through this interceptor (they are unauthenticated).
 *
 * <table>
 *   <tr><td>POST /bookings/hold</td><td>20 / minute / user</td></tr>
 *   <tr><td>all other authenticated endpoints</td><td>300 / minute / user</td></tr>
 * </table>
 *
 * On breach: HTTP 429 + {@code Retry-After: 60}. Fails OPEN if Redis is
 * unavailable — availability of the booking path is not sacrificed for the
 * limiter (a transient Redis outage must not lock out all tenants).
 */
@RequiredArgsConstructor
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final String KEY_PREFIX = "rate:api:";
    private static final int WINDOW_SECONDS = 60;
    private static final int HOLD_LIMIT = 20;
    private static final int DEFAULT_LIMIT = 300;

    private final StringRedisTemplate redis;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        UUID userId = TenantContext.getUserId();
        if (userId == null) {
            return true; // unauthenticated (permitted) route — not limited here
        }

        boolean isHold = "POST".equalsIgnoreCase(request.getMethod())
            && request.getRequestURI().endsWith("/bookings/hold");
        int limit = isHold ? HOLD_LIMIT : DEFAULT_LIMIT;
        String bucket = isHold ? "hold" : "general";
        String key = KEY_PREFIX + bucket + ":" + userId;

        try {
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) {
                redis.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
            }
            if (count != null && count > limit) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setHeader("Retry-After", String.valueOf(WINDOW_SECONDS));
                log.debug("Rate limit exceeded: user={} bucket={} count={}", userId, bucket, count);
                return false;
            }
        } catch (RuntimeException e) {
            // Fail open — never let a Redis blip take down the API.
            log.warn("Rate limiter unavailable, allowing request: {}", e.getMessage());
        }
        return true;
    }
}
