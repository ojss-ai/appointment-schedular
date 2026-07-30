// TASK: ATOM-ANALYTICS-001
package com.scheduler.api.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.scheduler.api.analytics.job.BookingPatternIngestionJob;
import com.scheduler.api.analytics.record.BookingPatternRecord;
import com.scheduler.api.analytics.record.BookingPatternRow;
import com.scheduler.api.analytics.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the nightly booking pattern ingestion job (file-system
 * behaviour with a mocked repository; the native aggregate query itself is
 * covered by the Testcontainers IT suite).
 */
class BookingPatternIngestionJobTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID RESOURCE_X = UUID.randomUUID();
    private static final UUID SERVICE_S = UUID.randomUUID();

    @TempDir
    Path tempDir;

    private AuditLogRepository auditLogRepository;
    private BookingPatternIngestionJob job;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        AnalyticsFileWriter fileWriter = new AnalyticsFileWriter(objectMapper);
        job = new BookingPatternIngestionJob(auditLogRepository, fileWriter);
        ReflectionTestUtils.setField(job, "outputBasePath", tempDir.toString());
        ReflectionTestUtils.setField(job, "assumedSlotsPerHour", 4);
    }

    @Test
    void shouldWriteResourcePatternFile_whenBookingsExistForDate() throws Exception {
        when(auditLogRepository.aggregateBookingPatterns(any(), any()))
            .thenReturn(List.of(row(1, 9, 3), row(1, 10, 4)));

        job.ingestYesterdaysPatterns();

        Path file = tempDir.resolve("by-resource").resolve(RESOURCE_X + ".json");
        assertThat(file).exists();
        BookingPatternRecord[] records =
            objectMapper.readValue(file.toFile(), BookingPatternRecord[].class);
        assertThat(records).hasSize(2);
        assertThat(records[0].tenantId()).isEqualTo(TENANT_A);
        assertThat(records[1].utilization()).isEqualTo(1.0);   // 4 bookings / 4 slots
    }

    @Test
    void shouldWriteAggregateSummaryFile_withCorrectDate() throws Exception {
        when(auditLogRepository.aggregateBookingPatterns(any(), any()))
            .thenReturn(List.of(row(2, 14, 5)));

        job.ingestYesterdaysPatterns();

        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        Path summary = tempDir.resolve("aggregate").resolve("summary-" + yesterday + ".json");
        assertThat(summary).exists();
        BookingPatternRecord[] records =
            objectMapper.readValue(summary.toFile(), BookingPatternRecord[].class);
        assertThat(records).hasSize(1);
    }

    @Test
    void shouldAccumulateRecordsAcrossRuns_forDistinctBuckets() throws Exception {
        when(auditLogRepository.aggregateBookingPatterns(any(), any()))
            .thenReturn(List.of(row(1, 9, 3)))
            .thenReturn(List.of(row(2, 9, 6)));

        job.ingestYesterdaysPatterns();
        job.ingestYesterdaysPatterns();

        Path file = tempDir.resolve("by-resource").resolve(RESOURCE_X + ".json");
        BookingPatternRecord[] records =
            objectMapper.readValue(file.toFile(), BookingPatternRecord[].class);
        assertThat(records).hasSize(2);   // merged, not overwritten (AC-03)
    }

    @Test
    void shouldNotThrow_whenAggregationFails() {
        when(auditLogRepository.aggregateBookingPatterns(any(), any()))
            .thenThrow(new RuntimeException("db unavailable"));

        assertThatCode(() -> job.ingestYesterdaysPatterns()).doesNotThrowAnyException();
    }

    @Test
    void shouldProduceEmptyResult_whenNoBookingsOnDate() {
        when(auditLogRepository.aggregateBookingPatterns(any(), any()))
            .thenReturn(List.of());

        job.ingestYesterdaysPatterns();

        assertThat(tempDir.resolve("by-resource")).doesNotExist();
        assertThat(tempDir.resolve("aggregate")).doesNotExist();
    }

    @Test
    void shouldNotIncludeIndustrySpecificTerms_inOutputJson() throws Exception {
        when(auditLogRepository.aggregateBookingPatterns(any(), any()))
            .thenReturn(List.of(row(1, 9, 3)));

        job.ingestYesterdaysPatterns();

        Path file = tempDir.resolve("by-resource").resolve(RESOURCE_X + ".json");
        String json = Files.readString(file);
        assertThat(json.toLowerCase())
            .doesNotContain("doctor", "patient", "vehicle", "mechanic");
    }

    private BookingPatternRow row(int dayOfWeek, int hourOfDay, long count) {
        return new BookingPatternRow(RESOURCE_X, TENANT_A, SERVICE_S, dayOfWeek, hourOfDay, count);
    }
}
