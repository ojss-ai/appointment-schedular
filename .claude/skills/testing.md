# Testing Skill — Scheduling Framework Patterns

> Reference for the TestGen agent. Covers Java (JUnit 5 + Testcontainers + k6) and TypeScript (Playwright).

---

## Java Testing Stack

| Layer | Tool | Scope |
|---|---|---|
| Unit | JUnit 5 + Mockito | Pure business logic (no Spring context) |
| Integration | @SpringBootTest + Testcontainers | Service → DB → Kafka wire |
| API | MockMvc / REST Assured | Controller → service (mocked) |
| Load | k6 | p99 < 300ms at 500 RPS (NFR-1.1/1.2) |

---

## Unit Test Pattern (SlotCalculatorService)

```java
@ExtendWith(MockitoExtension.class)
class SlotCalculatorServiceTest {

    @InjectMocks
    private SlotCalculatorService calculator;

    @Mock
    private BookingRepository bookingRepository;

    // Given/Assert format — match grover atom template
    @Test
    void calculateSlots_returnsEmptyList_whenResourceFullyBooked() {
        // Given
        UUID tenantId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        Instant windowStart = Instant.parse("2025-01-15T09:00:00Z");
        Instant windowEnd   = Instant.parse("2025-01-15T17:00:00Z");

        when(bookingRepository.findConflicting(tenantId, resourceId, windowStart, windowEnd))
            .thenReturn(List.of(bookingCovering(windowStart, windowEnd)));

        // Assert
        List<SlotWindow> slots = calculator.getAvailableSlots(
            tenantId, resourceId, windowStart, windowEnd, Duration.ofMinutes(30));
        assertThat(slots).isEmpty();
    }
}
```

---

## Integration Test Pattern (Testcontainers)

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class BookingServiceIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("scheduling_test")
        .withUsername("test")
        .withPassword("test");

    @Container
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.5.0"));

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    }

    @Autowired BookingService bookingService;
    @Autowired BookingRepository bookingRepository;

    @Test
    void createBooking_preventsDoubleBooking_underConcurrentLoad() throws Exception {
        // Given
        UUID tenantId = UUID.randomUUID();
        UUID resourceId = UUID.randomUUID();
        CreateBookingRequest request = new CreateBookingRequest(
            tenantId, resourceId, serviceId, locationId,
            Instant.parse("2025-01-15T10:00:00Z"), null
        );

        // Assert — run 10 concurrent booking attempts on same slot
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<BookingResponse>> futures = IntStream.range(0, 10)
            .mapToObj(_ -> executor.submit(() -> bookingService.createBooking(tenantId, request)))
            .toList();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        long successes = futures.stream().filter(f -> {
            try { f.get(); return true; }
            catch (Exception e) { return false; }
        }).count();

        assertThat(successes).isEqualTo(1);  // exactly one booking wins the lock
        assertThat(bookingRepository.countByTenantIdAndStatus(tenantId, CONFIRMED)).isEqualTo(1);
    }
}
```

---

## API Test Pattern (MockMvc)

```java
@WebMvcTest(BookingController.class)
class BookingControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean BookingService bookingService;

    @Test
    @WithMockJwt(tenantId = "550e8400-e29b-41d4-a716-446655440000", role = "STAFF")
    void getBooking_returns200_whenTenantMatches() throws Exception {
        UUID tenantId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID bookingId = UUID.randomUUID();
        when(bookingService.getBooking(tenantId, bookingId))
            .thenReturn(mockBookingResponse(tenantId, bookingId));

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/bookings/{bookingId}", tenantId, bookingId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(bookingId.toString()))
            .andExpect(jsonPath("$.tenantId").value(tenantId.toString()));
    }

    @Test
    void getBooking_returns403_whenTenantMismatch() throws Exception {
        // Given JWT tenant ≠ path tenantId → tenantGuard.check() returns false
        UUID jwtTenantId = UUID.randomUUID();   // different tenant
        UUID pathTenantId = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/tenants/{tenantId}/bookings/{bookingId}", pathTenantId, UUID.randomUUID())
            .with(jwt().jwt(j -> j.claim("tenant_id", jwtTenantId.toString()))))
            .andExpect(status().isForbidden());
    }
}
```

---

## Kafka Consumer Idempotency Test

```java
@Test
void consumer_skipsProcessing_whenEventAlreadySeen() {
    // Given — event already in processed_events table
    String eventId = UUID.randomUUID().toString();
    processedEventRepo.save(new ProcessedEvent(eventId, Instant.now()));

    BookingLifecycleEvent event = buildEvent(eventId);

    // Assert — no notification dispatched for duplicate
    consumer.handle(event, "tenant-key");
    verify(notificationService, never()).dispatchReminder(any());
}
```

---

## k6 Load Test Template

```javascript
// tests/load/slot-availability.js
import http from 'k6/http'
import { check, sleep } from 'k6'
import { Trend } from 'k6/metrics'

export const options = {
  vus: 50,
  duration: '2m',
  thresholds: {
    http_req_duration: ['p(99)<300'],  // NFR-1.2
    http_req_failed: ['rate<0.01'],
  },
}

const slotLatency = new Trend('slot_latency_ms')

export default function () {
  const tenantId = __ENV.TENANT_ID
  const res = http.get(
    `http://localhost:8080/api/v1/tenants/${tenantId}/slots/available?serviceId=...&date=2025-01-15`,
    { headers: { Authorization: `Bearer ${__ENV.JWT_TOKEN}` } }
  )
  slotLatency.add(res.timings.duration)
  check(res, {
    'status 200': r => r.status === 200,
    'slots returned': r => JSON.parse(r.body).length > 0,
  })
  sleep(0.1)
}
```

---

## Playwright E2E Pattern

```typescript
// tests/e2e/booking-flow.spec.ts
import { test, expect } from '@playwright/test'

test('complete booking flow', async ({ page }) => {
  // Given
  await page.goto('/tenant-abc/slots')
  await page.getByLabel('Service').selectOption('Standard Consultation')
  await page.getByLabel('Date').fill('2025-01-15')

  // Assert — slots appear after selection
  const slots = page.getByTestId('slot-option')
  await expect(slots.first()).toBeVisible()

  // Select first slot and confirm
  await slots.first().click()
  await page.getByRole('button', { name: 'Confirm Booking' }).click()
  await expect(page).toHaveURL(/\/bookings\/[a-f0-9-]{36}/)
  await expect(page.getByText('Booking confirmed')).toBeVisible()
})
```

---

## Coverage Gates (enforced by TestGen agent)

- Service classes: line coverage ≥ 80%
- Every `@Transactional` state mutation: at least 1 integration test
- Every Kafka consumer: idempotency test (duplicate delivery)
- Concurrency: ≥ 10 simultaneous requests on same slot
- Load: k6 must pass before Phase 5 sign-off
