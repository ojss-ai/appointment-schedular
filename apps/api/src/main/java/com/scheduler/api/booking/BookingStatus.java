// TASK: ATOM-BOOKING-009
package com.scheduler.api.booking;

import java.util.List;

/**
 * Booking state constants (VARCHAR(30) column per V010). Plain strings —
 * repository queries take {@code List<String>} status filters.
 */
public final class BookingStatus {

    public static final String PENDING_HOLD = "PENDING_HOLD";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String CANCELLED = "CANCELLED";
    public static final String COMPLETED = "COMPLETED";
    public static final String NO_SHOW = "NO_SHOW";

    /** States that occupy a slot for availability/conflict purposes. */
    public static final List<String> SLOT_BLOCKING = List.of(PENDING_HOLD, CONFIRMED);

    private BookingStatus() {
    }
}
