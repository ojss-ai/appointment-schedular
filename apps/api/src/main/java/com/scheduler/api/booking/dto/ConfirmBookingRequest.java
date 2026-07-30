// TASK: ATOM-BOOKING-010
package com.scheduler.api.booking.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Confirmation payload. {@code extensionData} is validated against the
 * service type's intake schema, then stored verbatim in the JSONB
 * {@code extension} column — never interpreted by core logic (ADR-005).
 */
public record ConfirmBookingRequest(JsonNode extensionData) {
}
