// [TASK: ATOM-SEC-504]
package com.scheduler.api.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the {@link RateLimitInterceptor} on the authenticated API surface
 * (SECURITY-SPEC 4.1). Disable in tests/dev with
 * {@code app.security.rate-limit.enabled=false}. Excludes {@code /api/v1/auth/**}
 * (OTP has its own rate limiter) and actuator endpoints.
 *
 * <p>{@link StringRedisTemplate} is resolved lazily via {@link ObjectProvider}
 * so MVC test slices ({@code @WebMvcTest}) that omit the Redis auto-config still
 * load cleanly — the interceptor is simply skipped when no template is present.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final boolean rateLimitEnabled;

    public WebMvcConfig(ObjectProvider<StringRedisTemplate> redisProvider,
                        @Value("${app.security.rate-limit.enabled:true}") boolean rateLimitEnabled) {
        this.redisProvider = redisProvider;
        this.rateLimitEnabled = rateLimitEnabled;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        if (!rateLimitEnabled) {
            return;
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        registry.addInterceptor(new RateLimitInterceptor(redis))
            .addPathPatterns("/api/v1/**")
            .excludePathPatterns("/api/v1/auth/**");
    }
}
