// TASK: ATOM-ANALYTICS-003
package com.scheduler.api.analytics.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.analytics.client.AnthropicClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Anthropic Messages API wiring. The API key comes from the
 * {@code ANTHROPIC_API_KEY} environment variable ONLY — never from source
 * or committed configuration (AC-05). The bean is always created but stays
 * inert (heuristic-only fallback) unless the feature flag is on and the key
 * is present, so the application runs without any Anthropic credentials.
 */
@Configuration
public class AnthropicConfig {

    @Bean
    public AnthropicClient anthropicClient(
            @Value("${app.ai.anthropic.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${ANTHROPIC_API_KEY:}") String apiKey,
            @Value("${app.ai.anthropic.model:claude-haiku-4-5}") String model,
            @Value("${app.ai.slot-optimization.enabled:false}") boolean enabled,
            ObjectMapper objectMapper) {
        return new AnthropicClient(baseUrl, apiKey, model, enabled, objectMapper);
    }
}
