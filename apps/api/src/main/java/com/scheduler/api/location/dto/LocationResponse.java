// TASK: ATOM-LOCATION-001
package com.scheduler.api.location.dto;

import com.scheduler.api.location.Location;

import java.math.BigDecimal;
import java.util.UUID;

/** Location representation per API-SPEC section 2 (nested address/coords). */
public record LocationResponse(
        UUID id,
        String name,
        Address address,
        Coordinates coordinates,
        String timezone,
        String status
) {

    public record Address(
            String line1,
            String line2,
            String city,
            String state,
            String postalCode,
            String countryCode
    ) {
    }

    public record Coordinates(BigDecimal latitude, BigDecimal longitude) {
    }

    public static LocationResponse from(Location l) {
        return new LocationResponse(
            l.getId(),
            l.getName(),
            new Address(l.getAddressLine1(), l.getAddressLine2(), l.getCity(),
                l.getState(), l.getPostalCode(), l.getCountryCode()),
            new Coordinates(l.getLatitude(), l.getLongitude()),
            l.getTimezone(),
            l.getStatus());
    }
}
