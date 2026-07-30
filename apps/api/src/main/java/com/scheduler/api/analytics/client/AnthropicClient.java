// TASK: ATOM-ANALYTICS-003
package com.scheduler.api.analytics.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client for the Anthropic Messages API
 * ({@code POST /v1/messages}). Deliberately SDK-free: Maven Central access
 * is restricted in this environment, and the only capability needed is a
 * single bounded completion call.
 *
 * <p>Feature-flagged: the client is inert unless
 * {@code app.ai.slot-optimization.enabled=true} AND the
 * {@code ANTHROPIC_API_KEY} environment variable is set. The application
 * starts and serves heuristic-only suggestions without either.
 */
@Slf4j
public class AnthropicClient {

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_TOKENS = 1024;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final boolean enabled;

    public AnthropicClient(String baseUrl, String apiKey, String model,
                           boolean enabled, ObjectMapper objectMapper) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = apiKey;
        this.model = model;
        this.enabled = enabled;
        this.objectMapper = objectMapper;
    }

    /** True when the feature flag is on and an API key is present. */
    public boolean isEnabled() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    /** Model identifier sent with every request (default {@code claude-haiku-4-5}). */
    public String model() {
        return model;
    }

    /**
     * Sends a single user prompt and returns the first text content block.
     *
     * @throws IllegalStateException when the client is disabled or the
     *         response contains no text content
     * @throws RuntimeException on transport or HTTP errors (caller falls
     *         back to heuristic-only suggestions — AC-08)
     */
    public String complete(String prompt) {
        if (!isEnabled()) {
            throw new IllegalStateException(
                "Anthropic client is disabled — enable app.ai.slot-optimization.enabled "
                    + "and set ANTHROPIC_API_KEY");
        }
        Map<String, Object> body = Map.of(
            "model", model,
            "max_tokens", MAX_TOKENS,
            "messages", List.of(Map.of("role", "user", "content", prompt)));

        String raw = restClient.post()
            .uri("/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(String.class);

        try {
            JsonNode content = objectMapper.readTree(raw).path("content");
            if (!content.isArray() || content.isEmpty()) {
                throw new IllegalStateException("Anthropic response contained no content blocks");
            }
            return content.get(0).path("text").asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Failed to parse Anthropic response", e);
        }
    }
}
