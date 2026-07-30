// TASK: ATOM-ANALYTICS-004
package com.scheduler.api.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.scheduler.api.analytics.job.AnomalyDetector;
import com.scheduler.api.analytics.record.AnomalyRecord;
import com.scheduler.api.analytics.record.BookingPatternRecord;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AnomalyDetectorTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID RESOURCE_X = UUID.randomUUID();
    private static final UUID SERVICE_S = UUID.randomUUID();

    @TempDir
    Path tempDir;

    private ObjectMapper objectMapper;
    private AnalyticsFileWriter fileWriter;
    private SimpleMeterRegistry meterRegistry;
    private AnomalyDetector detector;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        fileWriter = new AnalyticsFileWriter(objectMapper);
        meterRegistry = new SimpleMeterRegistry();
        detector = new AnomalyDetector(meterRegistry, fileWriter);
        ReflectionTestUtils.setField(detector, "patternsPath", tempDir.toString());
        ReflectionTestUtils.invokeMethod(detector, "registerGauge");
    }

    @Test
    void shouldWriteAnomaliesFile_whenResourceDropsAbove80Percent() throws Exception {
        // 7 baseline days at 30 bookings/day, most recent day at 4 (86.7% drop)
        seedBaselineAndLatest(30, 4);

        detector.detectAnomalies();

        Path outFile = tempDir.resolve("anomalies.json");
        assertThat(outFile).exists();
        AnomalyRecord[] anomalies =
            objectMapper.readValue(outFile.toFile(), AnomalyRecord[].class);
        assertThat(anomalies).hasSize(1);
        assertThat(anomalies[0].resourceId()).isEqualTo(RESOURCE_X);
        assertThat(anomalies[0].tenantId()).isEqualTo(TENANT_A);
        assertThat(anomalies[0].dropPercent()).isGreaterThanOrEqualTo(80.0);
        assertThat(anomalies[0].sevenDayAvg()).isEqualTo(30.0);
        assertThat(anomalies[0].yesterday()).isEqualTo(4L);
    }

    @Test
    void shouldEmitPrometheusGauge_whenAnomaliesDetected() {
        seedBaselineAndLatest(30, 4);

        detector.detectAnomalies();

        double value = meterRegistry
            .get("scheduling_booking_anomalies_detected")
            .gauge()
            .value();
        assertThat(value).isEqualTo(1.0);
    }

    @Test
    void shouldReportZeroAnomalies_whenVolumeIsStable() {
        seedBaselineAndLatest(30, 28);

        detector.detectAnomalies();

        assertThat(tempDir.resolve("anomalies.json")).doesNotExist();
        double value = meterRegistry
            .get("scheduling_booking_anomalies_detected")
            .gauge()
            .value();
        assertThat(value).isEqualTo(0.0);
    }

    @Test
    void shouldGracefullyExit_whenFewerThan8DaysOfAggregateData() {
        LocalDate start = LocalDate.parse("2026-06-01");
        for (int d = 0; d < 3; d++) {
            writeSummary(start.plusDays(d), 30);
        }

        assertThatCode(() -> detector.detectAnomalies()).doesNotThrowAnyException();
        assertThat(tempDir.resolve("anomalies.json")).doesNotExist();
    }

    @Test
    void shouldGracefullyExit_whenAggregateDirectoryMissing() {
        assertThatCode(() -> detector.detectAnomalies()).doesNotThrowAnyException();
    }

    /** 7 baseline daily summaries plus one most-recent day. */
    private void seedBaselineAndLatest(long baselineCount, long latestCount) {
        LocalDate start = LocalDate.parse("2026-06-01");
        for (int d = 0; d < 7; d++) {
            writeSummary(start.plusDays(d), baselineCount);
        }
        writeSummary(start.plusDays(7), latestCount);
    }

    private void writeSummary(LocalDate date, long count) {
        List<BookingPatternRecord> records = List.of(new BookingPatternRecord(
            RESOURCE_X, TENANT_A, SERVICE_S, 1, 9, count,
            Math.min(1.0, count / 4.0), Instant.now()));
        fileWriter.writeJson(
            tempDir.resolve("aggregate").resolve("summary-" + date + ".json"), records);
    }
}
