// TASK: ATOM-ANALYTICS-001 / ATOM-ANALYTICS-004
package com.scheduler.api.analytics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.analytics.record.BookingPatternRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Shared JSON I/O for the flat-file analytics memory namespace
 * ({@code docs/memory/booking-patterns/}). All failures are caught and
 * logged — analytics file I/O must never propagate into the scheduler
 * thread pool or an API request (ATOM-ANALYTICS-001 AC-05).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnalyticsFileWriter {

    private final ObjectMapper objectMapper;

    /** Writes {@code data} as pretty-printed JSON, creating parent dirs. */
    public void writeJson(Path file, Object data) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), data);
        } catch (IOException e) {
            log.error("AnalyticsFileWriter: failed to write file {}: {}", file, e.getMessage());
        }
    }

    /**
     * Reads a booking pattern JSON array file; returns an empty list when
     * the file is absent or unreadable (graceful degradation).
     */
    public List<BookingPatternRecord> readRecords(Path file) {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            return List.of(objectMapper.readValue(file.toFile(), BookingPatternRecord[].class));
        } catch (IOException e) {
            log.warn("AnalyticsFileWriter: could not read pattern file {}: {}", file, e.getMessage());
            return List.of();
        }
    }
}
