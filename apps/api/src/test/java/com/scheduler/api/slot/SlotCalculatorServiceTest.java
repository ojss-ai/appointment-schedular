// TASK: ATOM-SLOT-005 / ATOM-SLOT-006
package com.scheduler.api.slot;

import com.scheduler.api.booking.Booking;
import com.scheduler.api.booking.BookingRepository;
import com.scheduler.api.booking.BookingStatus;
import com.scheduler.api.holiday.BranchHoliday;
import com.scheduler.api.holiday.BranchHolidayRepository;
import com.scheduler.api.location.Location;
import com.scheduler.api.location.LocationRepository;
import com.scheduler.api.resource.ResourceBreak;
import com.scheduler.api.resource.ResourceBreakRepository;
import com.scheduler.api.resource.ResourceSchedule;
import com.scheduler.api.resource.ResourceScheduleRepository;
import com.scheduler.api.servicetype.ServiceType;
import com.scheduler.api.servicetype.ServiceTypeRepository;
import com.scheduler.api.slot.model.AvailableSlot;
import com.scheduler.api.slot.model.TimeWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests (mocked repositories) for the operating matrix
 * (ATOM-SLOT-005) and booking subtraction (ATOM-SLOT-006).
 *
 * <p>2026-07-20 is a Monday → schedule day_of_week = 1 (0=Sunday).
 */
@ExtendWith(MockitoExtension.class)
class SlotCalculatorServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 20); // Monday
    private static final int MONDAY = 1;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID locationId = UUID.randomUUID();
    private final UUID resourceId = UUID.randomUUID();
    private final UUID serviceTypeId = UUID.randomUUID();

    @Mock
    private LocationRepository locationRepository;
    @Mock
    private ResourceScheduleRepository scheduleRepository;
    @Mock
    private ResourceBreakRepository breakRepository;
    @Mock
    private BranchHolidayRepository holidayRepository;
    @Mock
    private ServiceTypeRepository serviceTypeRepository;
    @Mock
    private BookingRepository bookingRepository;

    private SlotCalculatorService service;

    @BeforeEach
    void setUp() {
        service = new SlotCalculatorService(locationRepository, scheduleRepository,
            breakRepository, holidayRepository, serviceTypeRepository, bookingRepository);
        lenient().when(holidayRepository.findByTenantIdAndLocationId(tenantId, locationId))
            .thenReturn(List.of());
        lenient().when(locationRepository.findByIdAndTenantId(locationId, tenantId))
            .thenReturn(Optional.of(location("UTC")));
        lenient().when(breakRepository
                .findByTenantIdAndResourceIdAndDayOfWeek(tenantId, resourceId, MONDAY))
            .thenReturn(List.of());
    }

    // ------------------------------------------------------------------
    // ATOM-SLOT-005 — operating matrix
    // ------------------------------------------------------------------

    @Test
    void singleShiftNoBreaks_producesOneWindow() {
        givenShifts(shift("09:00", "17:00"));

        List<TimeWindow> matrix =
            service.computeOperatingMatrix(resourceId, locationId, DATE, tenantId);

        assertThat(matrix).containsExactly(window("09:00", "17:00"));
    }

    @Test
    void lunchBreak_splitsShiftIntoTwoWindows() {
        givenShifts(shift("09:00", "17:00"));
        givenBreaks(brk("12:00", "13:00"));

        List<TimeWindow> matrix =
            service.computeOperatingMatrix(resourceId, locationId, DATE, tenantId);

        assertThat(matrix).containsExactly(
            window("09:00", "12:00"),
            window("13:00", "17:00"));
    }

    @Test
    void holidayDate_returnsEmptyWithoutLoadingSchedules() {
        when(holidayRepository.findByTenantIdAndLocationId(tenantId, locationId))
            .thenReturn(List.of(holiday(DATE, false)));

        List<TimeWindow> matrix =
            service.computeOperatingMatrix(resourceId, locationId, DATE, tenantId);

        assertThat(matrix).isEmpty();
        verify(scheduleRepository, never())
            .findByTenantIdAndResourceIdAndDayOfWeekAndIsActiveTrue(any(), any(), anyInt());
    }

    @Test
    void recurringHoliday_blocksSameMonthDayInFutureYears() {
        // Anchor holiday: 2025-07-20, recurring — must block 2026-07-20.
        when(holidayRepository.findByTenantIdAndLocationId(tenantId, locationId))
            .thenReturn(List.of(holiday(LocalDate.of(2025, 7, 20), true)));

        List<TimeWindow> matrix =
            service.computeOperatingMatrix(resourceId, locationId, DATE, tenantId);

        assertThat(matrix).isEmpty();
    }

    @Test
    void splitShift_producesTwoWindows() {
        givenShifts(shift("09:00", "12:00"), shift("14:00", "18:00"));

        List<TimeWindow> matrix =
            service.computeOperatingMatrix(resourceId, locationId, DATE, tenantId);

        assertThat(matrix).containsExactly(
            window("09:00", "12:00"),
            window("14:00", "18:00"));
    }

    @Test
    void breakWiderThanShift_eliminatesWindowEntirely() {
        givenShifts(shift("10:00", "12:00"));
        givenBreaks(brk("09:00", "13:00"));

        List<TimeWindow> matrix =
            service.computeOperatingMatrix(resourceId, locationId, DATE, tenantId);

        assertThat(matrix).isEmpty();
    }

    @Test
    void localShiftTimes_convertToUtcViaLocationTimezone() {
        // America/New_York on 2026-07-20 is EDT (UTC-4): 09:00 local = 13:00Z.
        when(locationRepository.findByIdAndTenantId(locationId, tenantId))
            .thenReturn(Optional.of(location("America/New_York")));
        givenShifts(shift("09:00", "17:00"));

        List<TimeWindow> matrix =
            service.computeOperatingMatrix(resourceId, locationId, DATE, tenantId);

        assertThat(matrix).containsExactly(new TimeWindow(
            Instant.parse("2026-07-20T13:00:00Z"),
            Instant.parse("2026-07-20T21:00:00Z")));
    }

    @Test
    void resourceWithoutScheduleEntries_yieldsEmptyMatrix() {
        givenShifts(); // none

        assertThat(service.computeOperatingMatrix(resourceId, locationId, DATE, tenantId))
            .isEmpty();
    }

    @Test
    void subtractBreaks_isPureSetDifference() {
        List<TimeWindow> shifts = List.of(window("09:00", "17:00"));
        List<TimeWindow> breaks = List.of(brkWindow("12:00", "12:30"), brkWindow("15:00", "15:15"));

        List<TimeWindow> result = service.subtractBreaks(shifts, breaks);

        assertThat(result).containsExactly(
            window("09:00", "12:00"),
            window("12:30", "15:00"),
            window("15:15", "17:00"));
    }

    // ------------------------------------------------------------------
    // ATOM-SLOT-006 — booking subtraction
    // ------------------------------------------------------------------

    @Test
    void noBookings_allWindowsBecomeCandidatesAtDurationGranularity() {
        givenShifts(shift("09:00", "12:00"));
        givenServiceType(60, 0, 0);
        givenBookings();

        List<AvailableSlot> slots = service.computeAvailableSlots(
            resourceId, serviceTypeId, locationId, DATE, tenantId);

        assertThat(slots).extracting(AvailableSlot::startTime).containsExactly(
            utc("09:00"), utc("10:00"), utc("11:00"));
        assertThat(slots).allSatisfy(s -> assertThat(s.durationMinutes()).isEqualTo(60));
    }

    @Test
    void confirmedBookingMidWindow_blocksOverlappingCandidates() {
        givenShifts(shift("09:00", "13:00"));
        givenServiceType(60, 0, 0);
        givenBookings(booking("10:00", "11:00", "10:00", "11:00", BookingStatus.CONFIRMED));

        List<AvailableSlot> slots = service.computeAvailableSlots(
            resourceId, serviceTypeId, locationId, DATE, tenantId);

        assertThat(slots).extracting(AvailableSlot::startTime).containsExactly(
            utc("09:00"), utc("11:00"), utc("12:00"));
    }

    @Test
    void bookingBufferWindow_excludesAdjacentCandidate() {
        givenShifts(shift("09:00", "13:00"));
        givenServiceType(60, 0, 0);
        // Booking 10:00-11:00 with 30-min post-buffer → buffer window ends 11:30.
        givenBookings(booking("10:00", "11:00", "10:00", "11:30", BookingStatus.CONFIRMED));

        List<AvailableSlot> slots = service.computeAvailableSlots(
            resourceId, serviceTypeId, locationId, DATE, tenantId);

        // 11:00 candidate overlaps the 11:30 buffer tail → excluded.
        assertThat(slots).extracting(AvailableSlot::startTime).containsExactly(
            utc("09:00"), utc("12:00"));
    }

    @Test
    void pendingHold_blocksSlotsWithSameWeightAsConfirmed() {
        givenShifts(shift("09:00", "12:00"));
        givenServiceType(60, 0, 0);
        givenBookings(booking("10:00", "11:00", "10:00", "11:00", BookingStatus.PENDING_HOLD));

        List<AvailableSlot> slots = service.computeAvailableSlots(
            resourceId, serviceTypeId, locationId, DATE, tenantId);

        assertThat(slots).extracting(AvailableSlot::startTime).containsExactly(
            utc("09:00"), utc("11:00"));
    }

    @Test
    void holidayDate_returnsEmptySlotList() {
        when(holidayRepository.findByTenantIdAndLocationId(tenantId, locationId))
            .thenReturn(List.of(holiday(DATE, false)));

        assertThat(service.computeAvailableSlots(
            resourceId, serviceTypeId, locationId, DATE, tenantId)).isEmpty();
    }

    @Test
    void candidateBufferAfter_mustFitInsideOperatingWindow() {
        givenShifts(shift("09:00", "11:00"));
        givenServiceType(60, 0, 15);
        givenBookings();

        List<AvailableSlot> slots = service.computeAvailableSlots(
            resourceId, serviceTypeId, locationId, DATE, tenantId);

        // 10:00 candidate would end 11:15 with buffer — past window end.
        assertThat(slots).extracting(AvailableSlot::startTime).containsExactly(utc("09:00"));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private Location location(String timezone) {
        return Location.builder().id(locationId).tenantId(tenantId)
            .name("Branch A").addressLine1("1 Main St").city("Testville")
            .postalCode("00000").timezone(timezone).build();
    }

    private void givenShifts(ResourceSchedule... shifts) {
        when(scheduleRepository
            .findByTenantIdAndResourceIdAndDayOfWeekAndIsActiveTrue(tenantId, resourceId, MONDAY))
            .thenReturn(List.of(shifts));
    }

    private void givenBreaks(ResourceBreak... breaks) {
        when(breakRepository
            .findByTenantIdAndResourceIdAndDayOfWeek(tenantId, resourceId, MONDAY))
            .thenReturn(List.of(breaks));
    }

    private void givenServiceType(int durationMin, int bufferBeforeMin, int bufferAfterMin) {
        when(serviceTypeRepository.findByIdAndTenantId(serviceTypeId, tenantId))
            .thenReturn(Optional.of(ServiceType.builder()
                .id(serviceTypeId).tenantId(tenantId).name("Standard Session")
                .durationMinutes(durationMin)
                .bufferBeforeMin(bufferBeforeMin)
                .bufferAfterMin(bufferAfterMin)
                .build()));
    }

    private void givenBookings(Booking... bookings) {
        when(bookingRepository
            .findByTenantIdAndLocationIdAndResourceIdAndStatusInAndSlotStartBetween(
                any(), any(), any(), any(), any(), any()))
            .thenReturn(List.of(bookings));
    }

    private ResourceSchedule shift(String start, String end) {
        return ResourceSchedule.builder()
            .tenantId(tenantId).resourceId(resourceId).dayOfWeek(MONDAY)
            .startTime(LocalTime.parse(start)).endTime(LocalTime.parse(end))
            .isActive(true).effectiveFrom(LocalDate.of(2020, 1, 1))
            .build();
    }

    private ResourceBreak brk(String start, String end) {
        return ResourceBreak.builder()
            .tenantId(tenantId).resourceId(resourceId).dayOfWeek(MONDAY)
            .breakStart(LocalTime.parse(start)).breakEnd(LocalTime.parse(end))
            .build();
    }

    private BranchHoliday holiday(LocalDate date, boolean recurring) {
        return BranchHoliday.builder()
            .tenantId(tenantId).locationId(locationId)
            .holidayDate(date).isRecurring(recurring)
            .build();
    }

    private Booking booking(String slotStart, String slotEnd,
                            String bufferStart, String bufferEnd, String status) {
        return Booking.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).locationId(locationId)
            .resourceId(resourceId).serviceTypeId(serviceTypeId).userId(UUID.randomUUID())
            .status(status)
            .slotStart(utc(slotStart)).slotEnd(utc(slotEnd))
            .bufferStart(utc(bufferStart)).bufferEnd(utc(bufferEnd))
            .build();
    }

    private static TimeWindow window(String start, String end) {
        return new TimeWindow(utc(start), utc(end));
    }

    private static TimeWindow brkWindow(String start, String end) {
        return new TimeWindow(utc(start), utc(end));
    }

    private static Instant utc(String time) {
        return DATE.atTime(LocalTime.parse(time)).atZone(ZoneId.of("UTC")).toInstant();
    }
}
