// TASK: ATOM-ANALYTICS-003
package com.scheduler.api.analytics.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.analytics.AnalyticsFileWriter;
import com.scheduler.api.analytics.client.AnthropicClient;
import com.scheduler.api.analytics.record.BookingPatternRecord;
import com.scheduler.api.analytics.record.OptimizationHint;
import com.scheduler.api.analytics.record.SlotOptimizationResponse;
import com.scheduler.api.analytics.record.Suggestion;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI slot optimization pipeline (three stages):
 * <ol>
 *   <li><b>Redis cache</b> — key {@code slot-opt:{tenantId}}, 24h TTL
 *       (aligned with the nightly ingestion cadence; contains API spend).</li>
 *   <li><b>Heuristic pre-filter</b> — tenant-scoped pattern records reduced
 *       to bounded {@link OptimizationHint}s (over/under-utilization).</li>
 *   <li><b>Claude API</b> — hints rephrased into human-readable admin
 *       suggestions. Feature-flagged: when the AI integration is disabled,
 *       unconfigured, or fails, the endpoint degrades to heuristic-only
 *       suggestions — never a 500 (AC-08).</li>
 * </ol>
 *
 * <p>Tenant isolation (ADR-004): {@link #loadPatternsForTenant} filters
 * every pattern record by {@code tenantId} before anything reaches the
 * prompt or the response.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlotOptimizationService {

    static final String CACHE_KEY_PREFIX = "slot-opt:";
    static final String INSUFFICIENT_DATA_MESSAGE = "Insufficient data (< 7 days)";
    static final int MIN_DISTINCT_DAYS = 7;
    private static final Duration CACHE_TTL = Duration.ofHours(24);
    private static final double OVER_UTILIZATION_THRESHOLD = 0.90;
    private static final long OVER_UTILIZATION_MIN_BOOKINGS = 10;
    private static final double UNDER_UTILIZATION_THRESHOLD = 0.20;
    private static final double FALLBACK_CONFIDENCE = 0.5;

    private static final TypeReference<List<Suggestion>> LIST_OF_SUGGESTION =
        new TypeReference<>() { };

    private static final String PROMPT_TEMPLATE = """
        You are a scheduling optimization assistant for a multi-tenant booking platform.
        Analyze these resource utilization hints and return a JSON array of suggestions.
        Each suggestion must have exactly these fields:
          resourceId (string UUID), suggestion (the hint code string),
          details (1-2 sentences), confidence (number 0-1), dataPoints (integer).

        Hints:
        %s

        Return ONLY a valid JSON array. No markdown, no explanation.
        """;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AnthropicClient anthropicClient;
    private final AnalyticsFileWriter fileWriter;

    @Value("${app.memory.booking-patterns-path:docs/memory/booking-patterns}")
    private String patternsPath;

    /**
     * Returns cached or freshly generated slot optimization suggestions for
     * the tenant. Requires ≥ {@value #MIN_DISTINCT_DAYS} distinct ingested
     * dates of pattern data (AC-07).
     */
    public SlotOptimizationResponse getSuggestions(UUID tenantId) {
        String cacheKey = CACHE_KEY_PREFIX + tenantId;
        SlotOptimizationResponse cached = readCache(cacheKey, tenantId);
        if (cached != null) {
            return cached;
        }

        List<BookingPatternRecord> patterns = loadPatternsForTenant(tenantId);
        long distinctDays = patterns.stream()
            .map(p -> p.updatedAt().atZone(ZoneOffset.UTC).toLocalDate())
            .distinct()
            .count();
        if (distinctDays < MIN_DISTINCT_DAYS) {
            return new SlotOptimizationResponse(List.of(), INSUFFICIENT_DATA_MESSAGE);
        }

        List<OptimizationHint> hints = applyHeuristics(patterns);
        List<Suggestion> suggestions;
        if (!anthropicClient.isEnabled()) {
            log.info("SlotOptimizationService: AI integration disabled — heuristic-only "
                + "suggestions for tenantId={}", tenantId);
            suggestions = heuristicFallback(hints);
        } else {
            try {
                suggestions = generateSuggestionsWithClaude(hints, tenantId);
            } catch (Exception e) {
                log.warn("SlotOptimizationService: Claude API failure for tenantId={}, "
                    + "falling back to heuristics: {}", tenantId, e.getMessage());
                suggestions = heuristicFallback(hints);
            }
        }

        SlotOptimizationResponse response = new SlotOptimizationResponse(suggestions, null);
        writeCache(cacheKey, tenantId, response);
        return response;
    }

    private SlotOptimizationResponse readCache(String cacheKey, UUID tenantId) {
        try {
            String cached = redis.opsForValue().get(cacheKey);
            if (cached != null) {
                return objectMapper.readValue(cached, SlotOptimizationResponse.class);
            }
        } catch (Exception e) {
            log.warn("SlotOptimizationService: cache read failed for tenantId={}: {}",
                tenantId, e.getMessage());
        }
        return null;
    }

    private void writeCache(String cacheKey, UUID tenantId, SlotOptimizationResponse response) {
        try {
            redis.opsForValue().set(cacheKey, objectMapper.writeValueAsString(response), CACHE_TTL);
        } catch (Exception e) {
            log.warn("SlotOptimizationService: cache write failed for tenantId={}: {}",
                tenantId, e.getMessage());
        }
    }

    /**
     * Loads every per-resource pattern file and keeps ONLY records whose
     * {@code tenantId} matches — zero cross-tenant rows reach the prompt
     * (AC-09, ADR-004).
     */
    private List<BookingPatternRecord> loadPatternsForTenant(UUID tenantId) {
        Path dir = Path.of(patternsPath, "by-resource");
        if (!Files.exists(dir)) {
            return List.of();
        }
        try (var stream = Files.list(dir)) {
            return stream
                .filter(f -> f.getFileName().toString().endsWith(".json"))
                .map(fileWriter::readRecords)
                .flatMap(List::stream)
                .filter(p -> tenantId.equals(p.tenantId()))
                .toList();
        } catch (IOException e) {
            log.warn("SlotOptimizationService: could not list pattern directory: {}",
                e.getMessage());
            return List.of();
        }
    }

    /**
     * Local pre-filter so the AI call is bounded: over-utilized windows
     * (> 90% utilization with ≥ 10 bookings → {@code EXTEND_HOURS}) and
     * under-utilized resources (average < 20% → {@code REDUCE_BUFFER}).
     */
    private List<OptimizationHint> applyHeuristics(List<BookingPatternRecord> patterns) {
        List<OptimizationHint> hints = new ArrayList<>();

        patterns.stream()
            .filter(p -> p.utilization() > OVER_UTILIZATION_THRESHOLD
                && p.bookingCount() >= OVER_UTILIZATION_MIN_BOOKINGS)
            .forEach(p -> hints.add(new OptimizationHint(
                p.resourceId(), "EXTEND_HOURS",
                "Day %d hour %d: utilization %.0f%% over %d bookings"
                    .formatted(p.dayOfWeek(), p.hourOfDay(),
                        p.utilization() * 100, p.bookingCount()),
                1)));

        Map<UUID, List<BookingPatternRecord>> byResource = patterns.stream()
            .collect(Collectors.groupingBy(BookingPatternRecord::resourceId));
        byResource.forEach((resourceId, resourcePatterns) -> {
            double avgUtilization = resourcePatterns.stream()
                .mapToDouble(BookingPatternRecord::utilization)
                .average()
                .orElse(0.0);
            if (avgUtilization < UNDER_UTILIZATION_THRESHOLD) {
                hints.add(new OptimizationHint(
                    resourceId, "REDUCE_BUFFER",
                    "Average utilization %.0f%% — consider reducing buffer time"
                        .formatted(avgUtilization * 100),
                    resourcePatterns.size()));
            }
        });

        return hints;
    }

    /** Direct hint-to-suggestion mapping used when the AI call is off or fails. */
    private List<Suggestion> heuristicFallback(List<OptimizationHint> hints) {
        return hints.stream()
            .map(h -> new Suggestion(h.resourceId(), h.hintCode(), h.description(),
                FALLBACK_CONFIDENCE, h.dataPoints()))
            .toList();
    }

    /**
     * Serializes the bounded hints into the prompt, calls the Claude API
     * ({@code claude-haiku-4-5}) and parses the returned JSON array.
     */
    private List<Suggestion> generateSuggestionsWithClaude(
            List<OptimizationHint> hints, UUID tenantId) throws IOException {
        if (hints.isEmpty()) {
            return List.of();
        }
        String hintsJson = objectMapper.writeValueAsString(hints);
        String prompt = PROMPT_TEMPLATE.formatted(hintsJson);

        log.info("SlotOptimizationService: calling Claude API (model={}) for tenantId={}, hints={}",
            anthropicClient.model(), tenantId, hints.size());

        String json = stripMarkdownFences(anthropicClient.complete(prompt));
        return objectMapper.readValue(json, LIST_OF_SUGGESTION);
    }

    /** Defensive cleanup — the model is instructed not to, but may fence output. */
    private String stripMarkdownFences(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }
}
