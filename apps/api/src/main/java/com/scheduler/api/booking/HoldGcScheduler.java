// TASK: ATOM-BOOKING-012
package com.scheduler.api.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Deletes PENDING_HOLD bookings whose {@code holdExpiresAt} has passed,
 * every 60 seconds, batched at 500 rows to keep transactions short and
 * avoid lock contention with concurrent {@code createHold} calls.
 *
 * <p>System-level operation: deliberately NOT tenant-scoped — one sweep
 * cleans expired holds across all tenants (ATOM-BOOKING-012 AC-07).
 * Requires {@code @EnableScheduling} (on {@code SchedulerApiApplication}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HoldGcScheduler {

    static final int BATCH_SIZE = 500;

    private final BookingRepository bookingRepository;

    @Scheduled(fixedDelay = 60_000)
    @Transactional
    public void expireStaleHolds() {
        int deleted = bookingRepository.deleteExpiredHolds(Instant.now(), BATCH_SIZE);
        if (deleted > 0) {
            log.info("Hold GC removed {} expired PENDING_HOLD bookings", deleted);
        } else {
            log.debug("Hold GC run — nothing to expire");
        }
    }
}
