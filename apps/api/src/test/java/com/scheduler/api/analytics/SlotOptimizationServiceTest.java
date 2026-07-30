// TASK: ATOM-ANALYTICS-003
package com.scheduler.api.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.scheduler.api.analytics.client.AnthropicClient;
import com.scheduler.api.analytics.record.BookingPatternRecord;
import com.scheduler.api.analytics.record.SlotOptimizationResponse;
import com.scheduler.api.analytics.record.Suggestion;
import com.scheduler.api.analytics.service.SlotOptimizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlotOptimizationServiceTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID RESOURCE_A = UUID.randomUUID();
    private static final UUID RESOURCE_B = UUID.randomUUID();
    private static final UUID SERVICE_S = UUID.randomUUID();

    @TempDir
    Path tempDir;

    private StringRedisTemplate redis;
    @SuppressWarnings("unchecked")
    private ValueOperations<String, String> valueOps = mock(ValueOperations.class);
    private AnthropicClient anthropicClient;
    private ObjectMapper objectMapper;
    private AnalyticsFileWriter fileWriter;
    private SlotOptimizationService service;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        anthropicClient = mock(AnthropicClient.class);
        when(anthropicClient.model()).thenReturn("claude-haiku-4-5");
        objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        fileWriter = new AnalyticsFileWriter(objectMapper);
        service = new SlotOptimizationService(redis, objectMapper, anthropicClient, fileWriter);
        ReflectionTestUtils.setField(service, "patternsPath", tempDir.toString());
    }

    @Test
    void shouldReturnSuggestions_whenPatternDataExistsFor7OrMoreDays() throws Exception {
        seedPatternFile(RESOURCE_A, TENANT_A, 7, 0.95, 12);
        when(anthropicClient.isEnabled()).thenReturn(true);
        when(anthropicClient.complete(anyString())).thenReturn("""
            [{"resourceId":"%s","suggestion":"EXTEND_HOURS",
              "details":"High demand window.","confidence":0.9,"dataPoints":7}]
            """.formatted(RESOURCE_A));

        SlotOptimizationResponse response = service.getSuggestions(TENANT_A);

        assertThat(response.message()).isNull();
        assertThat(response.suggestions()).isNotEmpty();
        assertThat(response.suggestions().get(0).suggestion()).isEqualTo("EXTEND_HOURS");
        verify(valueOps).set(anyString(), anyString(), any(Duration.class));
    }

    @Test
    void shouldReturnCachedResponse_withoutCallingClaude() throws Exception {
        SlotOptimizationResponse cached = new SlotOptimizationResponse(
            List.of(new Suggestion(RESOURCE_A, "EXTEND_HOURS", "cached", 0.8, 7)), null);
        when(valueOps.get("slot-opt:" + TENANT_A))
            .thenReturn(objectMapper.writeValueAsString(cached));

        SlotOptimizationResponse response = service.getSuggestions(TENANT_A);

        assertThat(response.suggestions()).hasSize(1);
        assertThat(response.suggestions().get(0).details()).isEqualTo("cached");
        verify(anthropicClient, never()).complete(anyString());
    }

    @Test
    void shouldReturnInsufficientData_whenLessThan7DaysOfPatterns() throws Exception {
        seedPatternFile(RESOURCE_A, TENANT_A, 3, 0.95, 12);

        SlotOptimizationResponse response = service.getSuggestions(TENANT_A);

        assertThat(response.suggestions()).isEmpty();
        assertThat(response.message()).isEqualTo("Insufficient data (< 7 days)");
        verify(anthropicClient, never()).complete(anyString());
    }

    @Test
    void shouldReturnHeuristicsOnly_whenClaudeApiThrows() throws Exception {
        seedPatternFile(RESOURCE_A, TENANT_A, 7, 0.95, 12);
        when(anthropicClient.isEnabled()).thenReturn(true);
        when(anthropicClient.complete(anyString()))
            .thenThrow(new RuntimeException("api down"));

        SlotOptimizationResponse response = service.getSuggestions(TENANT_A);

        assertThat(response.suggestions()).isNotEmpty();   // heuristic fallback, no 500
        assertThat(response.suggestions().get(0).suggestion()).isEqualTo("EXTEND_HOURS");
    }

    @Test
    void shouldReturnHeuristicsOnly_whenAiFeatureFlagDisabled() throws Exception {
        seedPatternFile(RESOURCE_A, TENANT_A, 7, 0.95, 12);
        when(anthropicClient.isEnabled()).thenReturn(false);

        SlotOptimizationResponse response = service.getSuggestions(TENANT_A);

        assertThat(response.suggestions()).isNotEmpty();
        verify(anthropicClient, never()).complete(anyString());
    }

    @Test
    void shouldFilterPatternsByTenantId_excludingOtherTenants() throws Exception {
        seedPatternFile(RESOURCE_A, TENANT_A, 7, 0.95, 12);
        seedPatternFile(RESOURCE_B, TENANT_B, 7, 0.95, 12);
        when(anthropicClient.isEnabled()).thenReturn(false);

        SlotOptimizationResponse response = service.getSuggestions(TENANT_A);

        assertThat(response.suggestions())
            .extracting(Suggestion::resourceId)
            .containsOnly(RESOURCE_A);
    }

    @Test
    void shouldNotThrow_whenRedisUnavailable() throws Exception {
        seedPatternFile(RESOURCE_A, TENANT_A, 7, 0.95, 12);
        when(anthropicClient.isEnabled()).thenReturn(false);
        when(valueOps.get(anyString())).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> service.getSuggestions(TENANT_A)).doesNotThrowAnyException();
    }

    /** Writes a by-resource pattern file covering {@code days} distinct dates. */
    private void seedPatternFile(UUID resourceId, UUID tenantId, int days,
                                 double utilization, long bookingCount) {
        List<BookingPatternRecord> records = new ArrayList<>();
        Instant base = Instant.parse("2026-07-01T02:00:00Z");
        for (int d = 0; d < days; d++) {
            records.add(new BookingPatternRecord(
                resourceId, tenantId, SERVICE_S,
                (d % 7) + 1, 9, bookingCount, utilization,
                base.plus(Duration.ofDays(d))));
        }
        fileWriter.writeJson(
            tempDir.resolve("by-resource").resolve(resourceId + ".json"), records);
    }
}
