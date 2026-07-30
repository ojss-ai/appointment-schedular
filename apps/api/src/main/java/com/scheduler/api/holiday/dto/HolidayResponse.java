// TASK: ATOM-HOLIDAY-004
package com.scheduler.api.holiday.dto;

import com.scheduler.api.holiday.BranchHoliday;

import java.time.LocalDate;
import java.util.UUID;

/** Holiday representation. */
public record HolidayResponse(
        UUID id,
        UUID locationId,
        LocalDate holidayDate,
        String name,
        boolean isRecurring
) {
    public static HolidayResponse from(BranchHoliday h) {
        return new HolidayResponse(h.getId(), h.getLocationId(), h.getHolidayDate(),
            h.getName(), h.isRecurring());
    }
}
