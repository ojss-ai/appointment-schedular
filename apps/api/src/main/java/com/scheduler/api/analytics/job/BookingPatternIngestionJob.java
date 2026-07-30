// TASK: ATOM-ANALYTICS-001
package com.scheduler.api.analytics.job;

import com.scheduler.api.analytics.AnalyticsFileWriter;
import com.scheduler.api.analytics.record.BookingPatternRecord;
import com.scheduler.api.analytics.record.BookingPatternRow;
import com.scheduler.api.analytics.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Nightly booking pattern ingestion (02:00 UTC — after the P3 Debezium CDC
 * lag window, so the previous day's {@code audit_log} rows are complete;
 * ADR-003). Aggregates confirmed bookings into the flat-file memory
 * namespace consumed by ATOM-ANALYTICS-003/-004.
 *
 * <p>Per-resource and per-service-type files are merged with their previous
 * content (records from earlier ingestion dates are retained) so that after
 * seven consecutive runs each file covers ≥ 7 distinct dates (AC-03). The
 * aggregate {@code summary-{date}.json} is a fresh snapshot per date.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookingPatternIngestionJob {

    private final AuditLogRepository auditLogRepository;
    private final AnalyticsFileWriter fileWriter;

    @Value("${app.memory.booking-patterns-path:docs/memory/booking-patterns}")
    private String outputBasePath;

    /**
     * Assumed bookable slots per resource-hour used for the utilization
     * denominator (slots are never stored — ADR-001 — so capacity is an
     * explicit, configurable assumption; default 4 = 15-minute granularity).
     */
    @Value("${app.analytics.assumed-slots-per-hour:4}")
    private int assumedSlotsPerHour;

    @Scheduled(cron = "0 0 2 * * *", zone = "UTC")   // 02:00 UTC nightly
    public void ingestYesterdaysPatterns() {
        LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
        log.info("BookingPatternIngestionJob: ingesting patterns for {}", yesterday);

        try {
            Instant dayStart = yesterday.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant dayEnd = yesterday.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

            List<BookingPatternRow> rows =
                auditLogRepository.aggregateBookingPatterns(dayStart, dayEnd);
            if (rows.isEmpty()) {
                log.info("BookingPatternIngestionJob: no confirmed bookings for {} — nothing written",
                    yesterday);
                return;
            }

            Instant now = Instant.now();
            List<BookingPatternRecord> records = rows.stream()
                .map(r -> toRecord(r, now))
                .toList();

            groupMergeAndWrite(records, BookingPatternRecord::resourceId, "by-resource");
            groupMergeAndWrite(records.stream()
                    .filter(r -> r.serviceTypeId() != null)
                    .toList(),
                BookingPatternRecord::serviceTypeId, "by-service-type");

            Path summary = Path.of(outputBasePath, "aggregate", "summary-" + yesterday + ".json");
            fileWriter.writeJson(summary, records);

            log.info("BookingPatternIngestionJob: wrote {} pattern records for date {}",
                records.size(), yesterday);

        } catch (Exception e) {
            // AC-05: never propagate to the scheduler thread pool.
            log.error("BookingPatternIngestionJob: failed for date {}: {}",
                yesterday, e.getMessage(), e);
        }
    }

    private BookingPatternRecord toRecord(BookingPatternRow row, Instant updatedAt) {
        double utilization = Math.min(1.0,
            row.bookingCount() / (double) Math.max(1, assumedSlotsPerHour));
        return new BookingPatternRecord(
            row.resourceId(), row.tenantId(), row.serviceTypeId(),
            row.dayOfWeek(), row.hourOfDay(), row.bookingCount(),
            utilization, updatedAt);
    }

    /** Groups records by {@code keyFn} and merge-writes one file per key. */
    private void groupMergeAndWrite(List<BookingPatternRecord> records,
                                    Function<BookingPatternRecord, UUID> keyFn,
                                    String subDir) {
        Map<UUID, List<BookingPatternRecord>> grouped =
            records.stream().collect(Collectors.groupingBy(keyFn));
        grouped.forEach((id, fresh) -> {
            Path file = Path.of(outputBasePath, subDir, id + ".json");
            fileWriter.writeJson(file, merge(fileWriter.readRecords(file), fresh));
        });
    }

    /**
     * Replaces previously ingested records occupying the same
     * (tenant, service type, day-of-week, hour-of-day) bucket and keeps
     * everything else, so pattern files accumulate history across runs.
     */
    private List<BookingPatternRecord> merge(List<BookingPatternRecord> existing,
                                             List<BookingPatternRecord> fresh) {
        Set<String> freshKeys = fresh.stream()
            .map(this::bucketKey)
            .collect(Collectors.toSet());
        List<BookingPatternRecord> merged = existing.stream()
            .filter(r -> !freshKeys.contains(bucketKey(r)))
            .collect(Collectors.toCollection(ArrayList::new));
        merged.addAll(fresh);
        merged.sort(Comparator
            .comparingInt(BookingPatternRecord::dayOfWeek)
            .thenComparingInt(BookingPatternRecord::hourOfDay));
        return merged;
    }

    private String bucketKey(BookingPatternRecord r) {
        return r.tenantId() + "|" + r.serviceTypeId() + "|" + r.dayOfWeek() + "|" + r.hourOfDay();
    }
}
