// TASK: ATOM-ANALYTICS-004
package com.scheduler.api.analytics.job;

import com.scheduler.api.analytics.AnalyticsFileWriter;
import com.scheduler.api.analytics.record.AnomalyRecord;
import com.scheduler.api.analytics.record.BookingPatternRecord;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Nightly booking anomaly detection (02:45 UTC). Flags resources whose
 * confirmed booking count on the most recent ingested day dropped more than
 * 80% versus their 7-day daily average — a signal of misconfigured
 * availability, accidental closure, or a system error.
 *
 * <p>Publishes the Prometheus gauge
 * {@code scheduling_booking_anomalies_detected} (AlertManager alerts on
 * {@code > 0}). The gauge is registered once and updated every run — it
 * returns to 0 on a clean night so alerts auto-resolve.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnomalyDetector {

    /** Metric name is referenced by AlertManager rules — do not rename. */
    static final String ANOMALY_METRIC = "scheduling_booking_anomalies_detected";
    static final double DROP_THRESHOLD = 0.80;
    /** 7-day baseline + the most recent day under test. */
    static final int REQUIRED_DAYS = 8;

    private final MeterRegistry meterRegistry;
    private final AnalyticsFileWriter fileWriter;

    private final AtomicLong anomalyCount = new AtomicLong(0);

    @Value("${app.memory.booking-patterns-path:docs/memory/booking-patterns}")
    private String patternsPath;

    @PostConstruct
    void registerGauge() {
        Gauge.builder(ANOMALY_METRIC, anomalyCount, AtomicLong::doubleValue)
            .description("Number of booking anomalies detected in the latest nightly scan")
            .register(meterRegistry);
    }

    @Scheduled(cron = "0 45 2 * * *", zone = "UTC")   // 02:45 UTC nightly
    public void detectAnomalies() {
        long start = System.currentTimeMillis();
        try {
            List<AnomalyRecord> anomalies = computeAnomalies();
            anomalyCount.set(anomalies.size());

            if (!anomalies.isEmpty()) {
                Path outFile = Path.of(patternsPath, "anomalies.json");
                fileWriter.writeJson(outFile, anomalies);
                log.warn("AnomalyDetector: {} anomalies detected — see {}", anomalies.size(), outFile);
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("AnomalyDetector: completed in {}ms, anomalies={}", elapsed, anomalies.size());

        } catch (Exception e) {
            // Never propagate to the scheduler thread pool.
            log.error("AnomalyDetector: run failed: {}", e.getMessage(), e);
        }
    }

    private List<AnomalyRecord> computeAnomalies() {
        List<Path> summaryFiles =
            listRecentSummaries(Path.of(patternsPath, "aggregate"), REQUIRED_DAYS);
        if (summaryFiles.size() < REQUIRED_DAYS) {
            log.info("AnomalyDetector: insufficient data — {} aggregate day(s) available, {} required",
                summaryFiles.size(), REQUIRED_DAYS);
            return List.of();
        }

        // Newest file = the day under test; the 7 before it = baseline.
        List<BookingPatternRecord> latestDay = fileWriter.readRecords(summaryFiles.get(0));
        List<BookingPatternRecord> baseline = summaryFiles.subList(1, REQUIRED_DAYS).stream()
            .map(fileWriter::readRecords)
            .flatMap(List::stream)
            .toList();
        if (baseline.isEmpty()) {
            log.info("AnomalyDetector: insufficient data — baseline files contain no records");
            return List.of();
        }

        Map<UUID, Long> baselineTotals = totalsByResource(baseline);
        Map<UUID, Long> latestTotals = totalsByResource(latestDay);
        Map<UUID, UUID> tenantByResource = baseline.stream()
            .collect(Collectors.toMap(
                BookingPatternRecord::resourceId,
                BookingPatternRecord::tenantId,
                (first, dup) -> first));

        Instant now = Instant.now();
        List<AnomalyRecord> anomalies = new ArrayList<>();
        for (Map.Entry<UUID, Long> entry : baselineTotals.entrySet()) {
            double sevenDayAvg = entry.getValue() / 7.0;
            long latest = latestTotals.getOrDefault(entry.getKey(), 0L);
            if (sevenDayAvg > 0 && latest < sevenDayAvg * (1.0 - DROP_THRESHOLD)) {
                double dropPercent = (1.0 - latest / sevenDayAvg) * 100.0;
                anomalies.add(new AnomalyRecord(
                    entry.getKey(), tenantByResource.get(entry.getKey()),
                    dropPercent, sevenDayAvg, latest, now));
            }
        }
        return anomalies;
    }

    private Map<UUID, Long> totalsByResource(List<BookingPatternRecord> records) {
        return records.stream().collect(Collectors.groupingBy(
            BookingPatternRecord::resourceId,
            Collectors.summingLong(BookingPatternRecord::bookingCount)));
    }

    /** Most recent {@code limit} daily summary files, newest first. */
    private List<Path> listRecentSummaries(Path aggregateDir, int limit) {
        if (!Files.exists(aggregateDir)) {
            log.info("AnomalyDetector: insufficient data — aggregate directory does not exist");
            return List.of();
        }
        try (var stream = Files.list(aggregateDir)) {
            return stream
                .filter(f -> f.getFileName().toString().startsWith("summary-"))
                .sorted(Comparator.comparing((Path f) -> f.getFileName().toString()).reversed())
                .limit(limit)
                .toList();
        } catch (IOException e) {
            log.error("AnomalyDetector: could not list aggregate directory: {}", e.getMessage());
            return List.of();
        }
    }
}
