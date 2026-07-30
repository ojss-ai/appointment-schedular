// TASK: ATOM-ANALYTICS-004
package com.scheduler.api.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.scheduler.api.analytics.job.PeakWindowDetector;
import com.scheduler.api.analytics.record.BookingPatternRecord;
import com.scheduler.api.analytics.record.PeakWindowRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PeakWindowDetectorTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID RESOURCE_X = UUID.randomUUID();
    private static final UUID RESOURCE_Y = UUID.randomUUID();
    private static final UUID SERVICE_S = UUID.randomUUID();

    @TempDir
    Path tempDir;

    private ObjectMapper objectMapper;
    private AnalyticsFileWriter fileWriter;
    private PeakWindowDetector detector;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        fileWriter = new AnalyticsFileWriter(objectMapper);
        detector = new PeakWindowDetector(fileWriter);
        ReflectionTestUtils.setField(detector, "patternsPath", tempDir.toString());
    }

    @Test
    void shouldWritePeakWindowsFile_whenBookingCountExceedsThreshold() throws Exception {
        // 14 days of baseline noise (count 2) + one hot window (count 30, >= 10 bookings)
        seedSummaryFiles(14, day -> {
            List<BookingPatternRecord> records = new ArrayList<>();
            records.add(record(RESOURCE_Y, 3, 11, 2));
            records.add(record(RESOURCE_X, 1, 9, 30));
            return records;
        });

        detector.detectPeakWindows();

        Path outFile = tempDir.resolve("peak-windows.json");
        assertThat(outFile).exists();
        PeakWindowRecord[] peaks =
            objectMapper.readValue(outFile.toFile(), PeakWindowRecord[].class);
        assertThat(peaks).isNotEmpty();
        assertThat(peaks[0].resourceId()).isEqualTo(RESOURCE_X);
        assertThat(peaks[0].confidenceScore()).isGreaterThanOrEqualTo(1.5);
    }

    @Test
    void shouldWriteEmptyPeakList_whenAllCountsBelowThreshold() throws Exception {
        seedSummaryFiles(14, day -> List.of(
            record(RESOURCE_X, 1, 9, 12),
            record(RESOURCE_Y, 2, 10, 12)));

        detector.detectPeakWindows();

        Path outFile = tempDir.resolve("peak-windows.json");
        assertThat(outFile).exists();
        PeakWindowRecord[] peaks =
            objectMapper.readValue(outFile.toFile(), PeakWindowRecord[].class);
        assertThat(peaks).isEmpty();
    }

    @Test
    void shouldGracefullyExit_whenFewerThanRequiredDaysOfAggregateData() {
        seedSummaryFiles(5, day -> List.of(record(RESOURCE_X, 1, 9, 30)));

        assertThatCode(() -> detector.detectPeakWindows()).doesNotThrowAnyException();
        assertThat(tempDir.resolve("peak-windows.json")).doesNotExist();
    }

    @Test
    void shouldGracefullyExit_whenAggregateDirectoryMissing() {
        assertThatCode(() -> detector.detectPeakWindows()).doesNotThrowAnyException();
        assertThat(tempDir.resolve("peak-windows.json")).doesNotExist();
    }

    private interface DayRecords {
        List<BookingPatternRecord> forDay(int day);
    }

    private void seedSummaryFiles(int days, DayRecords generator) {
        LocalDate start = LocalDate.parse("2026-06-01");
        for (int d = 0; d < days; d++) {
            fileWriter.writeJson(
                tempDir.resolve("aggregate").resolve("summary-" + start.plusDays(d) + ".json"),
                generator.forDay(d));
        }
    }

    private BookingPatternRecord record(UUID resourceId, int dayOfWeek, int hourOfDay, long count) {
        return new BookingPatternRecord(resourceId, TENANT_A, SERVICE_S,
            dayOfWeek, hourOfDay, count, Math.min(1.0, count / 4.0), Instant.now());
    }
}
