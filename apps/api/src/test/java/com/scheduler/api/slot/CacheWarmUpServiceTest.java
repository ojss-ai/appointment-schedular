// [TASK: ATOM-PERF-503]
package com.scheduler.api.slot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.resource.Resource;
import com.scheduler.api.resource.ResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CacheWarmUpService}: timing budget, per-resource error
 * isolation, and top-20% selection. Pattern files are written to a temp dir so
 * the test never touches the real memory namespace.
 */
@ExtendWith(MockitoExtension.class)
class CacheWarmUpServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ResourceRepository resourceRepository;
    @Mock
    private SlotCalculatorService slotCalculator;

    @TempDir
    Path patternsDir;

    private CacheWarmUpService service;

    @BeforeEach
    void setUp() {
        service = new CacheWarmUpService(resourceRepository, slotCalculator, objectMapper);
        ReflectionTestUtils.setField(service, "patternsPath", patternsDir.toString());
    }

    @Test
    void loadTop20PercentResources_returnsCorrectSubset() throws IOException {
        // 10 resources with ascending demand → top 20% == the 2 highest.
        UUID tenantId = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            writePattern(tenantId, UUID.randomUUID(), (i + 1) * 100L);
        }

        List<CacheWarmUpService.ResourceTarget> top = service.loadTop20PercentResources();

        assertThat(top).hasSize(2);
        assertThat(top.get(0).bookingCount()).isEqualTo(1000L);
        assertThat(top.get(1).bookingCount()).isEqualTo(900L);
    }

    @Test
    void loadTop20PercentResources_emptyDir_returnsEmptyList() {
        assertThat(service.loadTop20PercentResources()).isEmpty();
    }

    @Test
    void warmUpCompletesUnder30Seconds() throws IOException {
        UUID tenantId = UUID.randomUUID();
        for (int i = 0; i < 20; i++) {
            UUID resourceId = UUID.randomUUID();
            writePattern(tenantId, resourceId, (i + 1) * 10L);
        }
        lenient().when(resourceRepository.findByIdAndTenantId(any(), any()))
            .thenReturn(Optional.of(resourceWithLocation(tenantId)));
        lenient().when(slotCalculator.computeOperatingMatrix(any(), any(), any(), any()))
            .thenReturn(List.of());

        Instant start = Instant.now();
        service.warmUpTopResources();
        assertThat(Duration.between(start, Instant.now()).toMillis()).isLessThan(30_000L);
    }

    @Test
    void warmUpSkipsOnException_doesNotPropagateError() throws IOException {
        UUID tenantId = UUID.randomUUID();
        writePattern(tenantId, UUID.randomUUID(), 500L);
        when(resourceRepository.findByIdAndTenantId(any(), any()))
            .thenReturn(Optional.of(resourceWithLocation(tenantId)));
        when(slotCalculator.computeOperatingMatrix(any(), any(), any(), any()))
            .thenThrow(new RuntimeException("simulated compute failure"));

        // Must NOT propagate — warm-up is best-effort (AC-06).
        assertThatCode(() -> service.warmUpTopResources()).doesNotThrowAnyException();
    }

    @Test
    void warmUpSkipsMissingResource_withoutError() throws IOException {
        UUID tenantId = UUID.randomUUID();
        writePattern(tenantId, UUID.randomUUID(), 500L);
        when(resourceRepository.findByIdAndTenantId(any(), any())).thenReturn(Optional.empty());

        assertThatCode(() -> service.warmUpTopResources()).doesNotThrowAnyException();
    }

    // --- helpers -------------------------------------------------------

    private void writePattern(UUID tenantId, UUID resourceId, long bookingCount) throws IOException {
        Path byResource = patternsDir.resolve("by-resource");
        Files.createDirectories(byResource);
        String json = objectMapper.writeValueAsString(List.of(
            new com.scheduler.api.analytics.record.BookingPatternRecord(
                resourceId, tenantId, UUID.randomUUID(), 1, 9, bookingCount, 0.5, Instant.now())
        ));
        Files.writeString(byResource.resolve(resourceId + ".json"), json);
    }

    private Resource resourceWithLocation(UUID tenantId) {
        return Resource.builder()
            .tenantId(tenantId)
            .locationId(UUID.randomUUID())
            .name("Warm-up Resource")
            .resourceType("GENERAL")
            .build();
    }
}
