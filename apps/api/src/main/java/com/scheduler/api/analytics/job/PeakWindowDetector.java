// TASK: ATOM-ANALYTICS-004
package com.scheduler.api.analytics.job;

import com.scheduler.api.analytics.AnalyticsFileWriter;
import com.scheduler.api.analytics.record.BookingPatternRecord;
import com.scheduler.api.analytics.record.PeakWindowRecord;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Nightly peak booking window detection (02:30 UTC — 30 minutes after the
 * ingestion job so aggregate summary files are fully written; no explicit
 * coordination needed). A window is a peak when its booking count exceeds
 * 1.5x the 30-day mean and has at least {@value #MIN_BOOKINGS} bookings
 * (guards against low-volume false positives).
 *
 * <p>Requires ≥ {@value #MIN_DAYS} daily aggregate files (AC-01); exits
 * gracefully with an INFO log otherwise (AC-06).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PeakWindowDetector {

    static final int MIN_DAYS = 14;
    static final int MAX_DAYS = 30;
    static final int MIN_BOOKINGS = 10;
    static final double PEAK_MULTIPLIER = 1.5;

    private final AnalyticsFileWriter fileWriter;

    @Value("${app.memory.booking-patterns-path:docs/memory/booking-patterns}")
    private String patternsPath;

    @Scheduled(cron = "0 30 2 * * *", zone = "UTC")   // 02:30 UTC nightly
    public void detectPeakWindows() {
        long start = System.currentTimeMillis();
        try {
            List<Path> summaryFiles = listRecentSummaries(Path.of(patternsPath, "aggregate"), MAX_DAYS);
            if (summaryFiles.size() < MIN_DAYS) {
                log.info("PeakWindowDetector: insufficient data — {} aggregate day(s) available, {} required",
                    summaryFiles.size(), MIN_DAYS);
                return;
            }

            List<BookingPatternRecord> allPatterns = summaryFiles.stream()
                .map(fileWriter::readRecords)
                .flatMap(List::stream)
                .toList();
            if (allPatterns.isEmpty()) {
                log.info("PeakWindowDetector: insufficient data — aggregate files contain no records");
                return;
            }

            double mean = allPatterns.stream()
                .mapToLong(BookingPatternRecord::bookingCount)
                .average()
                .orElse(0.0);
            double threshold = mean * PEAK_MULTIPLIER;
            Instant now = Instant.now();

            List<PeakWindowRecord> peaks = allPatterns.stream()
                .filter(p -> p.bookingCount() >= MIN_BOOKINGS && p.bookingCount() > threshold)
                .map(p -> new PeakWindowRecord(
                    p.resourceId(), p.dayOfWeek(), p.hourOfDay(),
                    p.bookingCount(), p.bookingCount() / mean, now))
                .toList();

            fileWriter.writeJson(Path.of(patternsPath, "peak-windows.json"), peaks);

            long elapsed = System.currentTimeMillis() - start;
            log.info("PeakWindowDetector: identified {} peak windows in {}ms", peaks.size(), elapsed);

        } catch (Exception e) {
            // Never propagate to the scheduler thread pool.
            log.error("PeakWindowDetector: run failed: {}", e.getMessage(), e);
        }
    }

    /** Most recent {@code limit} daily summary files, newest first. */
    private List<Path> listRecentSummaries(Path aggregateDir, int limit) {
        if (!Files.exists(aggregateDir)) {
            return List.of();
        }
        try (var stream = Files.list(aggregateDir)) {
            return stream
                .filter(f -> f.getFileName().toString().startsWith("summary-"))
                // summary-{YYYY-MM-DD}.json — lexicographic order == date order
                .sorted(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed())
                .limit(limit)
                .toList();
        } catch (IOException e) {
            log.error("PeakWindowDetector: could not list aggregate directory: {}", e.getMessage());
            return List.of();
        }
    }
}
