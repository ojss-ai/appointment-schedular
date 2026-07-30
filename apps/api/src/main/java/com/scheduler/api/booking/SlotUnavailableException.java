// TASK: ATOM-BOOKING-009
package com.scheduler.api.booking;

import com.scheduler.api.common.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The requested slot is already occupied under the pessimistic lock
 * (ADR-002) — surfaces as 409 SLOT_UNAVAILABLE (API-SPEC section 9).
 */
public class SlotUnavailableException extends ApiException {

    public SlotUnavailableException() {
        super(HttpStatus.CONFLICT, "SLOT_UNAVAILABLE",
            "The requested slot is no longer available.");
    }
}
