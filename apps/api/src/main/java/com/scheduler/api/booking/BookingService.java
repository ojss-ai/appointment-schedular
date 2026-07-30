// TASK: ATOM-BOOKING-009 / ATOM-BOOKING-010 / ATOM-BOOKING-011 / ATOM-KAFKA-006
package com.scheduler.api.booking;

import com.fasterxml.jackson.databind.JsonNode;
import com.scheduler.api.booking.dto.CancellationResponse;
import com.scheduler.api.booking.dto.ConfirmationResponse;
import com.scheduler.api.booking.dto.CreateHoldRequest;
import com.scheduler.api.booking.dto.HoldResponse;
import com.scheduler.api.common.ApiException;
import com.scheduler.api.location.Location;
import com.scheduler.api.outbox.OutboxService;
import com.scheduler.api.location.LocationRepository;
import com.scheduler.api.resource.Resource;
import com.scheduler.api.resource.ResourceRepository;
import com.scheduler.api.servicetype.IntakeSchemaValidator;
import com.scheduler.api.servicetype.ServiceType;
import com.scheduler.api.servicetype.ServiceTypeRepository;
import com.scheduler.api.slot.SlotCalculatorService;
import com.scheduler.api.slot.model.TimeWindow;
import com.scheduler.api.tenant.Tenant;
import com.scheduler.api.tenant.TenantNotFoundException;
import com.scheduler.api.tenant.TenantRepository;
import com.scheduler.api.tenant.TenantScoped;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Booking lifecycle: hold → confirm → cancel (plus GC in
 * {@link HoldGcScheduler}). Concurrency guard per ADR-002:
 *
 * <ol>
 *   <li>Lock the Resource row FOR UPDATE — serializes all concurrent holds
 *       for one resource even when no booking rows exist yet to lock.</li>
 *   <li>Lock overlapping bookings FOR UPDATE and double-check under lock.</li>
 *   <li>SERIALIZABLE isolation as the belt-and-braces backstop; a losing
 *       serialization failure is mapped to 409 SLOT_UNAVAILABLE by
 *       {@code GlobalExceptionHandler}.</li>
 * </ol>
 *
 * Every state transition also stages a BookingLifecycleEvent row in the
 * outbox table inside the SAME transaction (ADR-003, ATOM-KAFKA-006) —
 * Debezium relays it to Kafka after commit; this service never produces to
 * Kafka directly.
 */
@Service
@TenantScoped
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    static final Duration HOLD_TTL = Duration.ofMinutes(10);

    private final BookingRepository bookingRepository;
    private final ResourceRepository resourceRepository;
    private final ServiceTypeRepository serviceTypeRepository;
    private final LocationRepository locationRepository;
    private final TenantRepository tenantRepository;
    private final SlotCalculatorService slotCalculatorService;
    private final IntakeSchemaValidator intakeSchemaValidator;
    private final ConfirmationCodeGenerator confirmationCodeGenerator;
    private final OutboxService outboxService;

    // ------------------------------------------------------------------
    // ATOM-BOOKING-009 — createHold
    // ------------------------------------------------------------------

    @Transactional(isolation = Isolation.SERIALIZABLE)
    public HoldResponse createHold(CreateHoldRequest req, UUID tenantId, UUID userId) {
        // 1. Pessimistic anchor: locks the resource row, serializing holds
        //    per resource (tenant-filtered — cross-tenant reads as absent).
        Resource resource = resourceRepository
            .findByIdAndTenantIdForUpdate(req.resourceId(), tenantId)
            .orElseThrow(() -> ApiException.notFound("RESOURCE_NOT_FOUND", "Resource not found."));
        if (!Resource.STATUS_ACTIVE.equals(resource.getStatus())) {
            throw ApiException.notFound("RESOURCE_NOT_FOUND", "Resource not found.");
        }

        ServiceType serviceType = serviceTypeRepository
            .findByIdAndTenantId(req.serviceTypeId(), tenantId)
            .orElseThrow(() ->
                ApiException.notFound("SERVICE_TYPE_NOT_FOUND", "Service type not found."));
        if (!ServiceType.STATUS_ACTIVE.equals(serviceType.getStatus())) {
            throw ApiException.unprocessable("SERVICE_TYPE_INACTIVE",
                "This service type no longer accepts new bookings.");
        }
        if (!serviceType.getAllowedResourceTypes().isEmpty()
                && !serviceType.getAllowedResourceTypes().contains(resource.getResourceType())) {
            throw ApiException.unprocessable("RESOURCE_TYPE_NOT_ALLOWED",
                "The selected resource type is not allowed for this service.");
        }

        Instant slotStart = req.slotStart();
        Instant slotEnd = slotStart.plus(Duration.ofMinutes(serviceType.getDurationMinutes()));
        Instant bufferStart = slotStart.minus(Duration.ofMinutes(serviceType.getBufferBeforeMin()));
        Instant bufferEnd = slotEnd.plus(Duration.ofMinutes(serviceType.getBufferAfterMin()));

        // 2. Slot must fall inside the operating matrix (location-local date).
        Location location = locationRepository
            .findByIdAndTenantId(resource.getLocationId(), tenantId)
            .orElseThrow(() -> ApiException.notFound("LOCATION_NOT_FOUND", "Location not found."));
        LocalDate localDate = slotStart.atZone(ZoneId.of(location.getTimezone())).toLocalDate();
        List<TimeWindow> matrix = slotCalculatorService.computeOperatingMatrix(
            resource.getId(), location.getId(), localDate, tenantId);
        TimeWindow requested = new TimeWindow(slotStart, slotEnd);
        boolean insideMatrix = matrix.stream().anyMatch(w -> w.contains(requested));
        if (!insideMatrix) {
            throw ApiException.unprocessable("SLOT_OUTSIDE_OPERATING_HOURS",
                "The requested slot is outside the resource's operating hours.");
        }

        // 3. Conflict probe under pessimistic lock (double-check, ADR-002).
        List<Booking> conflicts = bookingRepository.findConflictingBookingsForUpdate(
            resource.getId(), tenantId, BookingStatus.SLOT_BLOCKING, bufferStart, bufferEnd);
        if (!conflicts.isEmpty()) {
            boolean ownHold = conflicts.stream().anyMatch(c ->
                c.getUserId().equals(userId)
                    && BookingStatus.PENDING_HOLD.equals(c.getStatus())
                    && c.getSlotStart().equals(slotStart));
            if (ownHold) {
                throw ApiException.conflict("HOLD_ALREADY_EXISTS",
                    "You already hold this slot.");
            }
            throw new SlotUnavailableException();
        }

        Booking booking = bookingRepository.save(Booking.builder()
            .tenantId(tenantId)
            .locationId(location.getId())
            .resourceId(resource.getId())
            .serviceTypeId(serviceType.getId())
            .userId(userId)
            .status(BookingStatus.PENDING_HOLD)
            .slotStart(slotStart)
            .slotEnd(slotEnd)
            .bufferStart(bufferStart)
            .bufferEnd(bufferEnd)
            .holdExpiresAt(Instant.now().plus(HOLD_TTL))
            .build());

        // ADR-003: outbox row in the same transaction — no @Async, no direct
        // Kafka produce. Propagation.MANDATORY inside OutboxService verifies
        // the active transaction.
        outboxService.writeBookingEvent(booking, OutboxService.EVENT_BOOKING_HELD, null);

        log.info("Hold created bookingId={} resourceId={} slotStart={}",
            booking.getId(), resource.getId(), slotStart);
        return new HoldResponse(booking.getId(), booking.getStatus(),
            booking.getSlotStart(), booking.getSlotEnd(), booking.getHoldExpiresAt());
    }

    // ------------------------------------------------------------------
    // ATOM-BOOKING-010 — confirm
    // ------------------------------------------------------------------

    @Transactional
    public ConfirmationResponse confirmBooking(UUID bookingId, JsonNode extensionData,
                                               UUID tenantId, UUID userId) {
        Booking booking = requireBooking(bookingId, tenantId);
        if (!booking.getUserId().equals(userId)) {
            throw ApiException.forbidden("INSUFFICIENT_ROLE",
                "Only the booking owner may confirm.");
        }
        if (BookingStatus.CONFIRMED.equals(booking.getStatus())) {
            throw ApiException.conflict("ALREADY_CONFIRMED", "Booking is already confirmed.");
        }
        if (!BookingStatus.PENDING_HOLD.equals(booking.getStatus())) {
            throw ApiException.conflict("INVALID_STATE_TRANSITION",
                "Only a pending hold can be confirmed.");
        }
        if (booking.getHoldExpiresAt() == null
                || booking.getHoldExpiresAt().isBefore(Instant.now())) {
            throw ApiException.conflict("HOLD_EXPIRED",
                "The hold expired before confirmation.");
        }

        // Validate against the tenant's intake schema (only the validator
        // ever interprets schema/extension content — ADR-005).
        ServiceType serviceType = serviceTypeRepository
            .findByIdAndTenantId(booking.getServiceTypeId(), tenantId)
            .orElseThrow(() ->
                ApiException.notFound("SERVICE_TYPE_NOT_FOUND", "Service type not found."));
        if (extensionData != null && !extensionData.isNull()) {
            intakeSchemaValidator.requireDataMatchesSchema(
                serviceType.getIntakeSchema(), extensionData);
            booking.setExtension(extensionData.toString());
        }

        Tenant tenant = tenantRepository.findById(tenantId)
            .orElseThrow(() -> new TenantNotFoundException(tenantId.toString()));
        booking.setConfirmationCode(
            confirmationCodeGenerator.generate(tenantId, tenant.getSlug()));
        booking.setStatus(BookingStatus.CONFIRMED);
        booking.setHoldExpiresAt(null);
        bookingRepository.save(booking);

        // ADR-003: staged after the mutation so the payload carries the
        // confirmed state; committed atomically with it.
        outboxService.writeBookingEvent(booking,
            OutboxService.EVENT_BOOKING_CONFIRMED, BookingStatus.PENDING_HOLD);

        log.info("Booking confirmed bookingId={} code={}",
            booking.getId(), booking.getConfirmationCode());
        return new ConfirmationResponse(booking.getId(), booking.getStatus(),
            booking.getConfirmationCode(), booking.getSlotStart(), booking.getSlotEnd());
    }

    // ------------------------------------------------------------------
    // ATOM-BOOKING-011 — cancel
    // ------------------------------------------------------------------

    @Transactional
    public CancellationResponse cancelBooking(UUID bookingId, String reason,
                                              UUID actorUserId, List<String> actorRoles,
                                              UUID tenantId) {
        Booking booking = requireBooking(bookingId, tenantId);
        boolean owner = booking.getUserId().equals(actorUserId);
        boolean admin = isAdmin(actorRoles);
        if (!owner && !admin) {
            throw ApiException.forbidden("INSUFFICIENT_ROLE",
                "Only the booking owner or an admin may cancel.");
        }
        if (BookingStatus.CANCELLED.equals(booking.getStatus())) {
            throw ApiException.conflict("ALREADY_CANCELLED", "Booking is already cancelled.");
        }
        if (!BookingStatus.CONFIRMED.equals(booking.getStatus())) {
            // PENDING_HOLD is cleaned up exclusively by the GC (ATOM-BOOKING-012).
            throw ApiException.conflict("INVALID_STATE_TRANSITION",
                "Only a confirmed booking can be cancelled.");
        }

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(Instant.now());
        booking.setCancelledBy(actorUserId);
        booking.setCancellationReason(reason);
        bookingRepository.save(booking);

        // ADR-003: same-transaction outbox write.
        outboxService.writeBookingEvent(booking,
            OutboxService.EVENT_BOOKING_CANCELLED, BookingStatus.CONFIRMED);

        log.info("Booking cancelled bookingId={} by={}", booking.getId(), actorUserId);
        return new CancellationResponse(booking.getId(), booking.getStatus(),
            booking.getCancelledAt());
    }

    // ------------------------------------------------------------------
    // Reads (API-SPEC section 6 list/detail)
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Booking getBooking(UUID bookingId, UUID tenantId, UUID actorUserId,
                              List<String> actorRoles) {
        Booking booking = requireBooking(bookingId, tenantId);
        if (!booking.getUserId().equals(actorUserId) && !isAdmin(actorRoles)) {
            // Non-owner sees 404, never confirmation of existence.
            throw ApiException.notFound("BOOKING_NOT_FOUND", "Booking not found.");
        }
        return booking;
    }

    @Transactional(readOnly = true)
    public Page<Booking> listBookings(UUID tenantId, UUID actorUserId, List<String> actorRoles,
                                      String status, UUID resourceId, UUID locationId,
                                      Instant dateFrom, Instant dateTo, Pageable pageable) {
        // tenant_id is ALWAYS the first predicate (ADR-004).
        Specification<Booking> spec =
            (root, q, cb) -> cb.equal(root.get("tenantId"), tenantId);
        if (!isAdmin(actorRoles)) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("userId"), actorUserId));
        }
        if (status != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (resourceId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("resourceId"), resourceId));
        }
        if (locationId != null) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("locationId"), locationId));
        }
        if (dateFrom != null) {
            spec = spec.and((root, q, cb) ->
                cb.greaterThanOrEqualTo(root.get("slotStart"), dateFrom));
        }
        if (dateTo != null) {
            spec = spec.and((root, q, cb) -> cb.lessThanOrEqualTo(root.get("slotStart"), dateTo));
        }
        return bookingRepository.findAll(spec, pageable);
    }

    // ------------------------------------------------------------------

    private Booking requireBooking(UUID bookingId, UUID tenantId) {
        return bookingRepository.findByIdAndTenantId(bookingId, tenantId)
            .orElseThrow(() -> ApiException.notFound("BOOKING_NOT_FOUND", "Booking not found."));
    }

    private static boolean isAdmin(List<String> roles) {
        return roles != null && (roles.contains("admin") || roles.contains("super_admin"));
    }
}
