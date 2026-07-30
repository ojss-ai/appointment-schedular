// TASK: ATOM-BOOKING-013 (covers ATOM-BOOKING-009 AC-05, 010, 011, 012)
package com.scheduler.api.booking;

import com.scheduler.api.booking.dto.CreateHoldRequest;
import com.scheduler.api.booking.dto.HoldResponse;
import com.scheduler.api.common.ApiException;
import com.scheduler.api.location.Location;
import com.scheduler.api.location.LocationRepository;
import com.scheduler.api.resource.Resource;
import com.scheduler.api.resource.ResourceRepository;
import com.scheduler.api.resource.ResourceSchedule;
import com.scheduler.api.resource.ResourceScheduleRepository;
import com.scheduler.api.security.TenantContext;
import com.scheduler.api.servicetype.ServiceType;
import com.scheduler.api.servicetype.ServiceTypeRepository;
import com.scheduler.api.tenant.Tenant;
import com.scheduler.api.tenant.TenantRepository;
import com.scheduler.api.user.User;
import com.scheduler.api.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Booking state machine under concurrent load (ATOM-BOOKING-013) against
 * real PostgreSQL 15 + Redis 7 via Testcontainers. Each test provisions its
 * own tenant fixture — zero cross-test or cross-tenant state leakage.
 */
@SpringBootTest
@Testcontainers
class BookingConcurrencyIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15")
        .withDatabaseName("scheduler_test")
        .withUsername("scheduler")
        .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host", redis::getHost);
        r.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        r.add("app.jwt.secret", () -> "integration-test-secret-key-32+bytes-long!!");
        r.add("app.jwt.expiry-hours", () -> 1);
    }

    @Autowired
    private BookingService bookingService;
    @Autowired
    private HoldGcScheduler holdGcScheduler;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private LocationRepository locationRepository;
    @Autowired
    private ResourceRepository resourceRepository;
    @Autowired
    private ResourceScheduleRepository scheduleRepository;
    @Autowired
    private ServiceTypeRepository serviceTypeRepository;
    @Autowired
    private UserRepository userRepository;

    private UUID tenantId;
    private UUID locationId;
    private UUID resourceId;
    private UUID serviceTypeId;

    @BeforeEach
    void setUpFixture() {
        tenantId = createTestTenant();
        locationId = createTestLocation(tenantId);
        resourceId = createTestResource(tenantId, locationId);
        serviceTypeId = createTestServiceType(tenantId, 60, 0, 15);
    }

    @AfterEach
    void cleanUp() {
        bookingRepository.deleteAll();
        TenantContext.clear();
    }

    // ------------------------------------------------------------------
    // Scenario 1 — 10-thread race on one slot (AC-04)
    // ------------------------------------------------------------------

    @Test
    void tenSimultaneousHolds_exactlyOneWins() throws Exception {
        Instant slotStart = tomorrowAt(10);
        RaceOutcome outcome = raceHolds(10, slotStart);

        assertThat(outcome.successes().get()).isEqualTo(1);
        assertThat(outcome.conflicts().get()).isEqualTo(9);
        assertThat(outcome.unexpected()).isEmpty();
        assertThat(countBlockingBookingsAt(slotStart)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Scenario 2 — GC running concurrently: no deadlocks (AC-07)
    // ------------------------------------------------------------------

    @Test
    void raceWithConcurrentHoldGc_producesNoDeadlock() throws Exception {
        // Seed expired holds for the GC to chew on during the race. Two-hour
        // spacing keeps the 15-min post-buffers of the seeds from colliding.
        UUID staleUser = createTestUser(tenantId, "stale@fixture.test");
        for (int hour = 12; hour <= 16; hour += 2) {
            Booking stale = holdAs(staleUser, tomorrowAt(hour));
            expireHold(stale.getId());
        }

        AtomicBoolean gcFailed = new AtomicBoolean(false);
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread gcThread = new Thread(() -> {
            while (!stop.get()) {
                try {
                    holdGcScheduler.expireStaleHolds();
                } catch (DeadlockLoserDataAccessException e) {
                    gcFailed.set(true);
                } catch (RuntimeException e) {
                    // transient contention is fine; deadlock is not
                }
            }
        });
        gcThread.start();

        Instant slotStart = tomorrowAt(10);
        RaceOutcome outcome = raceHolds(10, slotStart);
        stop.set(true);
        gcThread.join(TimeUnit.SECONDS.toMillis(10));

        assertThat(gcFailed).isFalse();
        assertThat(outcome.successes().get()).isEqualTo(1);
        assertThat(outcome.unexpected()).isEmpty();
        assertThat(countBlockingBookingsAt(slotStart)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Scenario 3 — expired hold is GC'd and the slot is free again (AC on 012)
    // ------------------------------------------------------------------

    @Test
    void expiredHold_isCollected_andSlotBecomesAvailableAgain() {
        Instant slotStart = tomorrowAt(9);
        UUID firstUser = createTestUser(tenantId, "first@fixture.test");
        Booking hold = holdAs(firstUser, slotStart);
        expireHold(hold.getId());

        holdGcScheduler.expireStaleHolds();
        assertThat(bookingRepository.findById(hold.getId())).isEmpty();

        UUID secondUser = createTestUser(tenantId, "second@fixture.test");
        Booking rebooked = holdAs(secondUser, slotStart);
        assertThat(rebooked.getStatus()).isEqualTo(BookingStatus.PENDING_HOLD);
    }

    // ------------------------------------------------------------------
    // Scenario 4 — adjacent-slot buffer collision + exact boundary (AC-05)
    // ------------------------------------------------------------------

    @Test
    void adjacentSlotInsideBuffer_blocked_exactBufferBoundary_succeeds() {
        UUID userA = createTestUser(tenantId, "a@fixture.test");
        UUID userB = createTestUser(tenantId, "b@fixture.test");

        // Hold 09:00-10:00; 15-min post-buffer occupies until 10:15.
        holdAs(userA, tomorrowAt(9));

        // 10:00 start collides with the 10:15 buffer tail.
        assertThatThrownBy(() -> holdAs(userB, tomorrowAt(10)))
            .isInstanceOf(SlotUnavailableException.class);

        // Exactly at the buffer boundary (10:15) the hold succeeds.
        Booking boundary = holdAs(userB, tomorrowAt(10).plusSeconds(15 * 60));
        assertThat(boundary.getStatus()).isEqualTo(BookingStatus.PENDING_HOLD);
    }

    // ------------------------------------------------------------------
    // Scenario 5 — expired hold cannot be confirmed (HOLD_EXPIRED)
    // ------------------------------------------------------------------

    @Test
    void confirmAfterHoldExpiry_returnsHoldExpired() {
        UUID user = createTestUser(tenantId, "late@fixture.test");
        Booking hold = holdAs(user, tomorrowAt(11));
        expireHold(hold.getId());

        asUser(user);
        assertThatThrownBy(() ->
            bookingService.confirmBooking(hold.getId(), null, tenantId, user))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> {
                ApiException ex = (ApiException) e;
                assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(ex.getCode()).isEqualTo("HOLD_EXPIRED");
            });
    }

    // ------------------------------------------------------------------
    // Scenario 6 — hold-and-confirm race: exactly one CONFIRMED (AC-06)
    // ------------------------------------------------------------------

    @Test
    void holdAndConfirmRace_exactlyOneConfirmedBooking() {
        Instant slotStart = tomorrowAt(14);
        UUID winner = createTestUser(tenantId, "winner@fixture.test");
        UUID rival = createTestUser(tenantId, "rival@fixture.test");

        Booking hold = holdAs(winner, slotStart);
        asUser(winner);
        bookingService.confirmBooking(hold.getId(), null, tenantId, winner);

        assertThatThrownBy(() -> holdAs(rival, slotStart))
            .isInstanceOf(SlotUnavailableException.class);

        long confirmed = bookingRepository.findAll().stream()
            .filter(b -> b.getTenantId().equals(tenantId))
            .filter(b -> BookingStatus.CONFIRMED.equals(b.getStatus()))
            .count();
        assertThat(confirmed).isEqualTo(1);

        Booking confirmedBooking = bookingRepository.findById(hold.getId()).orElseThrow();
        assertThat(confirmedBooking.getConfirmationCode()).matches("[A-Z]+-\\d{4}-\\d{5}");
        assertThat(confirmedBooking.getHoldExpiresAt()).isNull();
    }

    // ------------------------------------------------------------------
    // Cancellation frees the slot immediately (ATOM-BOOKING-011 AC-06)
    // ------------------------------------------------------------------

    @Test
    void cancelledBooking_freesSlotForNewHold() {
        Instant slotStart = tomorrowAt(15);
        UUID owner = createTestUser(tenantId, "owner@fixture.test");
        Booking hold = holdAs(owner, slotStart);

        asUser(owner);
        bookingService.confirmBooking(hold.getId(), null, tenantId, owner);
        bookingService.cancelBooking(hold.getId(), "Change of plans", owner,
            List.of("customer"), tenantId);

        UUID next = createTestUser(tenantId, "next@fixture.test");
        Booking rebooked = holdAs(next, slotStart);
        assertThat(rebooked.getStatus()).isEqualTo(BookingStatus.PENDING_HOLD);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private record RaceOutcome(AtomicInteger successes, AtomicInteger conflicts,
                               List<Throwable> unexpected) {
    }

    /**
     * Runs {@code threads} simultaneous createHold calls (distinct users)
     * for the same slot. Losers must surface as SlotUnavailableException or
     * a serialization/lock ConcurrencyFailureException — both map to 409
     * SLOT_UNAVAILABLE at the REST boundary. Anything else is a failure.
     */
    private RaceOutcome raceHolds(int threads, Instant slotStart) throws Exception {
        List<UUID> users = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            users.add(createTestUser(tenantId, "racer" + i + "@fixture.test"));
        }

        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();

        for (UUID userId : users) {
            futures.add(pool.submit((Callable<Void>) () -> {
                TenantContext.set(tenantId, userId, List.of("customer"));
                try {
                    ready.countDown();
                    go.await(30, TimeUnit.SECONDS);
                    bookingService.createHold(
                        new CreateHoldRequest(resourceId, serviceTypeId, slotStart),
                        tenantId, userId);
                    successes.incrementAndGet();
                } catch (SlotUnavailableException | ConcurrencyFailureException e) {
                    conflicts.incrementAndGet();
                } catch (Throwable t) {
                    synchronized (unexpected) {
                        unexpected.add(t);
                    }
                } finally {
                    TenantContext.clear();
                }
                return null;
            }));
        }

        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
        go.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        return new RaceOutcome(successes, conflicts, unexpected);
    }

    private Booking holdAs(UUID userId, Instant slotStart) {
        asUser(userId);
        HoldResponse response = bookingService.createHold(
            new CreateHoldRequest(resourceId, serviceTypeId, slotStart), tenantId, userId);
        return bookingRepository.findById(response.bookingId()).orElseThrow();
    }

    private void asUser(UUID userId) {
        TenantContext.set(tenantId, userId, List.of("customer"));
    }

    private void expireHold(UUID bookingId) {
        Booking booking = bookingRepository.findById(bookingId).orElseThrow();
        booking.setHoldExpiresAt(Instant.now().minusSeconds(700));
        bookingRepository.save(booking);
    }

    private long countBlockingBookingsAt(Instant slotStart) {
        return bookingRepository.findAll().stream()
            .filter(b -> b.getTenantId().equals(tenantId))
            .filter(b -> b.getSlotStart().equals(slotStart))
            .filter(b -> BookingStatus.SLOT_BLOCKING.contains(b.getStatus()))
            .count();
    }

    // --- fixture builders (generic domain terms only) ---

    private UUID createTestTenant() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return tenantRepository.save(Tenant.builder()
            .name("Fixture Tenant " + suffix)
            .slug("fx-" + suffix)
            .build()).getId();
    }

    private UUID createTestLocation(UUID tenantId) {
        return locationRepository.save(Location.builder()
            .tenantId(tenantId)
            .name("Fixture Branch")
            .addressLine1("1 Test Way")
            .city("Testville")
            .postalCode("00000")
            .countryCode("US")
            .timezone("UTC")
            .build()).getId();
    }

    private UUID createTestResource(UUID tenantId, UUID locationId) {
        UUID id = resourceRepository.save(Resource.builder()
            .tenantId(tenantId)
            .locationId(locationId)
            .name("Fixture Resource")
            .resourceType("GENERAL")
            .build()).getId();
        // Open every day of the week 08:00-18:00 so any race date works.
        for (int day = 0; day <= 6; day++) {
            scheduleRepository.save(ResourceSchedule.builder()
                .tenantId(tenantId)
                .resourceId(id)
                .dayOfWeek(day)
                .startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(18, 0))
                .isActive(true)
                .effectiveFrom(LocalDate.of(2020, 1, 1))
                .build());
        }
        return id;
    }

    private UUID createTestServiceType(UUID tenantId, int durationMin,
                                       int bufferBeforeMin, int bufferAfterMin) {
        return serviceTypeRepository.save(ServiceType.builder()
            .tenantId(tenantId)
            .name("Fixture Session")
            .durationMinutes(durationMin)
            .bufferBeforeMin(bufferBeforeMin)
            .bufferAfterMin(bufferAfterMin)
            .allowedResourceTypes(List.of("GENERAL"))
            .build()).getId();
    }

    private UUID createTestUser(UUID tenantId, String identifier) {
        return userRepository.save(User.builder()
            .tenantId(tenantId)
            .identifier(identifier)
            .identifierType("EMAIL")
            .build()).getId();
    }

    private Instant tomorrowAt(int hour) {
        return LocalDate.now(ZoneOffset.UTC).plusDays(1)
            .atTime(hour, 0).toInstant(ZoneOffset.UTC);
    }
}
