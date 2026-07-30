// [TASK: ATOM-PERF-503]
package com.scheduler.api.slot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.api.analytics.record.BookingPatternRecord;
import com.scheduler.api.resource.Resource;
import com.scheduler.api.resource.ResourceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * ATOM-PERF-503 — proactive Redis cache warm-up for the top-20% most-booked
 * resources. CONDITIONAL atom: only relevant when ATOM-PERF-501 reports slot
 * availability p99 &gt; 250ms. Disable with {@code app.cache.warmup.enabled=false}
 * (e.g. in unit-test profiles).
 *
 * <p>Fires once on {@link ApplicationReadyEvent} (all beans, incl. JPA, ready)
 * and every 30 minutes thereafter ({@code fixedDelay} — no overlap). Warm-up
 * NEVER writes to Redis directly: it calls {@link SlotCalculatorService}, whose
 * schedule/holiday reads ride the tenant-scoped {@code @Cacheable} layer
 * (ATOM-SLOT-008), so each call transparently populates the cache. Per-resource
 * failures are swallowed at DEBUG level and never crash the application (AC-06).
 */
@Component
@ConditionalOnProperty(name = "app.cache.warmup.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class CacheWarmUpService implements ApplicationListener<ApplicationReadyEvent> {

    /** Next N days of operating matrices to pre-compute per resource. */
    private static final int WARMUP_HORIZON_DAYS = 7;

    private final ResourceRepository resourceRepository;
    private final SlotCalculatorService slotCalculator;
    private final ObjectMapper objectMapper;

    @Value("${app.memory.booking-patterns-path:docs/memory/booking-patterns}")
    private String patternsPath;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        warmUpTopResources();
    }

    @Scheduled(fixedDelay = 1_800_000) // every 30 minutes
    public void warmUpTopResources() {
        Instant start = Instant.now();
        List<ResourceTarget> targets = loadTop20PercentResources();
        LocalDate today = LocalDate.now();

        int warmed = 0;
        for (ResourceTarget target : targets) {
            Resource resource = resourceRepository
                .findByIdAndTenantId(target.resourceId(), target.tenantId())
                .orElse(null);
            if (resource == null) {
                log.debug("Warm-up skip: resource {} not found for tenant {}",
                    target.resourceId(), target.tenantId());
                continue;
            }
            for (int i = 0; i < WARMUP_HORIZON_DAYS; i++) {
                LocalDate date = today.plusDays(i);
                try {
                    // Populates the Redis cache via @Cacheable on schedule/holiday repos.
                    slotCalculator.computeOperatingMatrix(
                        target.resourceId(), resource.getLocationId(), date, target.tenantId());
                    warmed++;
                } catch (Exception e) {
                    log.debug("Warm-up skip for resource {} on {}: {}",
                        target.resourceId(), date, e.getMessage());
                }
            }
        }

        log.info("Cache warm-up complete: {} resources x {} days ({} matrices) in {}ms",
            targets.size(), WARMUP_HORIZON_DAYS, warmed,
            Duration.between(start, Instant.now()).toMillis());
    }

    /**
     * Read {@code by-resource/*.json} and return the top 20% resources ranked by
     * total confirmed booking count (descending). Empty list on any I/O error —
     * warm-up is best-effort and must never fail startup.
     */
    List<ResourceTarget> loadTop20PercentResources() {
        Path byResource = Path.of(patternsPath, "by-resource");
        if (!Files.isDirectory(byResource)) {
            log.debug("Warm-up: booking-patterns dir {} absent — nothing to warm", byResource);
            return List.of();
        }

        List<ResourceTarget> ranked = new ArrayList<>();
        try (Stream<Path> files = Files.list(byResource)) {
            List<ResourceTarget> targets = files
                .filter(p -> p.toString().endsWith(".json"))
                .map(this::toTarget)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(ResourceTarget::bookingCount).reversed())
                .toList();
            ranked.addAll(targets);
        } catch (IOException e) {
            log.debug("Warm-up: failed to list {}: {}", byResource, e.getMessage());
            return List.of();
        }

        int topN = Math.max(1, (int) Math.ceil(ranked.size() * 0.2));
        return ranked.stream().limit(topN).toList();
    }

    /** Aggregate one by-resource file into a single ranking target. */
    private ResourceTarget toTarget(Path file) {
        try {
            BookingPatternRecord[] records =
                objectMapper.readValue(file.toFile(), BookingPatternRecord[].class);
            if (records.length == 0) {
                return null;
            }
            long total = Arrays.stream(records).mapToLong(BookingPatternRecord::bookingCount).sum();
            BookingPatternRecord first = records[0];
            return new ResourceTarget(first.resourceId(), first.tenantId(), total);
        } catch (IOException e) {
            log.debug("Warm-up: unreadable pattern file {}: {}", file, e.getMessage());
            return null;
        }
    }

    /** Internal ranking target — resource + owning tenant + aggregate demand. */
    record ResourceTarget(UUID resourceId, UUID tenantId, long bookingCount) {
    }
}
