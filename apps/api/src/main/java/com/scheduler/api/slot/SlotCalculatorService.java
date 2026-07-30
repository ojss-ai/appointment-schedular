// TASK: ATOM-SLOT-005 / ATOM-SLOT-006
package com.scheduler.api.slot;

import com.scheduler.api.booking.Booking;
import com.scheduler.api.booking.BookingRepository;
import com.scheduler.api.booking.BookingStatus;
import com.scheduler.api.common.ApiException;
import com.scheduler.api.holiday.BranchHoliday;
import com.scheduler.api.holiday.BranchHolidayRepository;
import com.scheduler.api.location.Location;
import com.scheduler.api.location.LocationRepository;
import com.scheduler.api.resource.ResourceBreakRepository;
import com.scheduler.api.resource.ResourceScheduleRepository;
import com.scheduler.api.servicetype.ServiceType;
import com.scheduler.api.servicetype.ServiceTypeRepository;
import com.scheduler.api.slot.model.AvailableSlot;
import com.scheduler.api.slot.model.TimeWindow;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * On-demand slot computation (ADR-001 — slots are NEVER stored; there is no
 * slots table). Pure read-only service: no writes, no events, no state.
 *
 * <pre>
 * availability = operating matrix (shifts − breaks − holidays)
 *              − blocking bookings (PENDING_HOLD + CONFIRMED, incl. buffers)
 * </pre>
 *
 * Schedule/break/holiday reads ride the tenant-scoped Redis cache
 * (ATOM-SLOT-008); booking reads are ALWAYS fresh from PostgreSQL.
 * Intentionally not {@code @Transactional} — read-only, callable from
 * within an outer transaction ({@code BookingService.createHold}).
 */
@Service
@RequiredArgsConstructor
public class SlotCalculatorService {

    private final LocationRepository locationRepository;
    private final ResourceScheduleRepository scheduleRepository;
    private final ResourceBreakRepository breakRepository;
    private final BranchHolidayRepository holidayRepository;
    private final ServiceTypeRepository serviceTypeRepository;
    private final BookingRepository bookingRepository;

    /** Booking fetch padding around the matrix — covers max buffer (120 min). */
    private static final Duration BOOKING_FETCH_PAD = Duration.ofHours(3);

    /**
     * ATOM-SLOT-005: base shifts − breaks for one resource/date, converted
     * from location-local wall clock to UTC. Holiday dates yield an empty
     * matrix without loading any schedule data (AC-03).
     */
    public List<TimeWindow> computeOperatingMatrix(UUID resourceId, UUID locationId,
                                                   LocalDate date, UUID tenantId) {
        List<BranchHoliday> holidays =
            holidayRepository.findByTenantIdAndLocationId(tenantId, locationId);
        if (holidays.stream().anyMatch(h -> h.blocks(date))) {
            return List.of();
        }

        Location location = locationRepository.findByIdAndTenantId(locationId, tenantId)
            .orElseThrow(() -> ApiException.notFound("LOCATION_NOT_FOUND", "Location not found."));
        ZoneId zone = ZoneId.of(location.getTimezone());
        int dayOfWeek = toScheduleDayOfWeek(date);

        List<TimeWindow> shifts = scheduleRepository
            .findByTenantIdAndResourceIdAndDayOfWeekAndIsActiveTrue(tenantId, resourceId, dayOfWeek)
            .stream()
            .filter(s -> s.isEffectiveOn(date))
            .map(s -> toUtcWindow(date, s.getStartTime(), s.getEndTime(), zone))
            .sorted(Comparator.comparing(TimeWindow::start))
            .toList();
        if (shifts.isEmpty()) {
            return List.of();
        }

        List<TimeWindow> breaks = breakRepository
            .findByTenantIdAndResourceIdAndDayOfWeek(tenantId, resourceId, dayOfWeek)
            .stream()
            .map(b -> toUtcWindow(date, b.getBreakStart(), b.getBreakEnd(), zone))
            .sorted(Comparator.comparing(TimeWindow::start))
            .toList();

        return subtractBreaks(shifts, breaks);
    }

    /**
     * ATOM-SLOT-006: operating matrix − blocking bookings (with buffers on
     * both sides of the comparison). Candidates step at durationMinutes
     * granularity. Entirely transient — nothing is written.
     */
    @Timed(value = "scheduling.slot.calc.duration",
        description = "Slot availability computation latency (NFR-1.2; ATOM-INFRA-508)",
        percentiles = {0.5, 0.95, 0.99}, histogram = true)
    public List<AvailableSlot> computeAvailableSlots(UUID resourceId, UUID serviceTypeId,
                                                     UUID locationId, LocalDate date,
                                                     UUID tenantId) {
        List<TimeWindow> matrix = computeOperatingMatrix(resourceId, locationId, date, tenantId);
        if (matrix.isEmpty()) {
            return List.of();
        }

        ServiceType serviceType = serviceTypeRepository.findByIdAndTenantId(serviceTypeId, tenantId)
            .orElseThrow(() ->
                ApiException.notFound("SERVICE_TYPE_NOT_FOUND", "Service type not found."));
        Duration duration = Duration.ofMinutes(serviceType.getDurationMinutes());
        Duration bufferBefore = Duration.ofMinutes(serviceType.getBufferBeforeMin());
        Duration bufferAfter = Duration.ofMinutes(serviceType.getBufferAfterMin());

        // Bookings are ALWAYS fresh — never cached (ADR-002 / ATOM-SLOT-008 AC-05).
        Instant fetchFrom = matrix.get(0).start().minus(BOOKING_FETCH_PAD);
        Instant fetchTo = matrix.get(matrix.size() - 1).end().plus(BOOKING_FETCH_PAD);
        List<TimeWindow> blocked = bookingRepository
            .findByTenantIdAndLocationIdAndResourceIdAndStatusInAndSlotStartBetween(
                tenantId, locationId, resourceId, BookingStatus.SLOT_BLOCKING, fetchFrom, fetchTo)
            .stream()
            .map(SlotCalculatorService::bufferWindowOf)
            .toList();

        List<AvailableSlot> slots = new ArrayList<>();
        for (TimeWindow window : matrix) {
            Instant cursor = window.start();
            while (!cursor.plus(duration).plus(bufferAfter).isAfter(window.end())) {
                TimeWindow candidate =
                    new TimeWindow(cursor.minus(bufferBefore), cursor.plus(duration).plus(bufferAfter));
                boolean conflicts = blocked.stream().anyMatch(candidate::overlaps);
                if (!conflicts) {
                    slots.add(new AvailableSlot(cursor, cursor.plus(duration),
                        serviceType.getDurationMinutes()));
                }
                cursor = cursor.plus(duration);
            }
        }
        return slots;
    }

    // ------------------------------------------------------------------

    /**
     * In-memory set difference: subtracting a break may split a shift into
     * two windows; a break covering a whole window eliminates it.
     * Package-private for unit testing (ATOM-SLOT-005 contract).
     */
    List<TimeWindow> subtractBreaks(List<TimeWindow> shifts, List<TimeWindow> breaks) {
        List<TimeWindow> result = new ArrayList<>(shifts);
        for (TimeWindow brk : breaks) {
            List<TimeWindow> next = new ArrayList<>();
            for (TimeWindow window : result) {
                if (!window.overlaps(brk)) {
                    next.add(window);
                    continue;
                }
                if (window.start().isBefore(brk.start())) {
                    next.add(new TimeWindow(window.start(), brk.start()));
                }
                if (window.end().isAfter(brk.end())) {
                    next.add(new TimeWindow(brk.end(), window.end()));
                }
                // Break swallows the window entirely → nothing added.
            }
            result = next;
        }
        result.sort(Comparator.comparing(TimeWindow::start));
        return result;
    }

    /** Local wall-clock window on {@code date} → UTC instants via {@code zone}. */
    static TimeWindow toUtcWindow(LocalDate date, LocalTime start, LocalTime end, ZoneId zone) {
        return new TimeWindow(
            date.atTime(start).atZone(zone).toInstant(),
            date.atTime(end).atZone(zone).toInstant());
    }

    /** DB convention: 0=Sunday .. 6=Saturday (java.time: Monday=1 .. Sunday=7). */
    static int toScheduleDayOfWeek(LocalDate date) {
        return date.getDayOfWeek().getValue() % 7;
    }

    private static TimeWindow bufferWindowOf(Booking b) {
        return new TimeWindow(b.getBufferStart(), b.getBufferEnd());
    }
}
