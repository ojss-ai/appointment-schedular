// TASK: ATOM-SLOT-008
package com.scheduler.api.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

/**
 * Redis-backed Spring Cache for the STATIC inputs of slot calculation:
 * resource schedules, resource breaks and branch holidays (ATOM-SLOT-008,
 * NFR-1.2). Booking data is NEVER cached — stale bookings would cause
 * double-booking (ADR-002).
 *
 * <p>Cache keys are tenant-scoped ({@code tenantId} is the first key
 * component on every {@code @Cacheable} method) so no cross-tenant cache
 * bleed is possible. A 5-minute TTL backstops any missed eviction.
 *
 * <p>The {@link CacheErrorHandler} degrades gracefully: if Redis is down,
 * every cache operation is logged and swallowed, so reads fall through to
 * PostgreSQL instead of surfacing a 500.
 */
@Configuration
@EnableCaching
@Slf4j
public class CacheConfig implements CachingConfigurer {

    public static final String CACHE_RESOURCE_SCHEDULES = "resource-schedules";
    public static final String CACHE_RESOURCE_BREAKS = "resource-breaks";
    public static final String CACHE_BRANCH_HOLIDAYS = "branch-holidays";

    private static final Duration TTL = Duration.ofMinutes(5);

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.activateDefaultTyping(
            BasicPolymorphicTypeValidator.builder().allowIfBaseType(Object.class).build(),
            ObjectMapper.DefaultTyping.EVERYTHING,
            JsonTypeInfo.As.PROPERTY);

        RedisCacheConfiguration cacheConfig = RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(TTL)
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                new GenericJackson2JsonRedisSerializer(mapper)))
            .disableCachingNullValues();

        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(cacheConfig)
            .withCacheConfiguration(CACHE_RESOURCE_SCHEDULES, cacheConfig)
            .withCacheConfiguration(CACHE_RESOURCE_BREAKS, cacheConfig)
            .withCacheConfiguration(CACHE_BRANCH_HOLIDAYS, cacheConfig)
            .build();
    }

    /** Redis unavailability must degrade to direct DB reads, never a 500. */
    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache GET failed on {} — falling through to DB: {}",
                    cache.getName(), e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key,
                                            Object value) {
                log.warn("Cache PUT failed on {}: {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache EVICT failed on {}: {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.warn("Cache CLEAR failed on {}: {}", cache.getName(), e.getMessage());
            }
        };
    }
}
