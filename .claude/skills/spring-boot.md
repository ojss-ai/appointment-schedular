# Spring Boot 3.x Skill — Scheduling Framework Patterns

> Reference for the Coder agent. All Spring Boot code lives in `apps/api/`.
> Spring Boot 3.x + Spring Security 6 + Spring Data JPA + Jakarta EE namespace.

---

## Entity Layer

### Base Entity Pattern

```java
@MappedSuperclass
public abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
```

### Booking Entity (reference implementation)

```java
@Entity
@Table(name = "bookings", indexes = {
    @Index(name = "idx_booking_tenant_resource_time",
           columnList = "tenant_id, resource_id, start_time")
})
public class Booking extends BaseEntity {

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Type(JsonBinaryType.class)
    @Column(name = "extension", columnDefinition = "jsonb")
    private JsonNode extension;
}
```

**Rules:**
- Every entity must extend `BaseEntity` (provides `id`, `tenantId`, timestamps)
- Never add industry-specific field names — `Resource` not `Doctor`, `Booking` not `Appointment`
- JSONB `extension` is for tenant-injected domain data only; core logic never reads it

---

## Repository Layer

### Standard Pattern

```java
@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    // MANDATORY: every finder must include tenantId
    Optional<Booking> findByIdAndTenantId(UUID id, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id AND b.tenantId = :tenantId")
    Optional<Booking> findByIdAndTenantIdForUpdate(
        @Param("id") UUID id,
        @Param("tenantId") UUID tenantId
    );

    @Query("""
        SELECT b FROM Booking b
        WHERE b.tenantId = :tenantId
          AND b.resourceId = :resourceId
          AND b.status NOT IN ('CANCELLED', 'NO_SHOW')
          AND b.startTime < :windowEnd
          AND b.endTime > :windowStart
        """)
    List<Booking> findConflicting(
        @Param("tenantId") UUID tenantId,
        @Param("resourceId") UUID resourceId,
        @Param("windowStart") Instant windowStart,
        @Param("windowEnd") Instant windowEnd
    );

    // Count queries also need tenantId
    long countByTenantIdAndStatus(UUID tenantId, BookingStatus status);
}
```

**Hard rule:** No repository method — including `findAll`, `count`, `existsBy` — may execute without `tenantId` in the WHERE clause.

---

## Service Layer

### Standard Pattern

```java
@Service
@Transactional
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final OutboxRepository outboxRepository;
    private final SlotCalculatorService slotCalculator;

    // Read-only methods use readOnly = true for performance
    @Transactional(readOnly = true)
    public BookingResponse getBooking(UUID tenantId, UUID bookingId) {
        return bookingRepository.findByIdAndTenantId(bookingId, tenantId)
            .map(BookingMapper::toResponse)
            .orElseThrow(() -> new ResourceNotFoundException("Booking", bookingId));
    }

    // State-mutating methods use default (readOnly = false)
    public BookingResponse createBooking(UUID tenantId, CreateBookingRequest request) {
        // 1. Pessimistic lock via FOR UPDATE
        // 2. Validate slot availability
        // 3. Persist booking
        // 4. Write outbox event — same ACID transaction
        Booking booking = buildBooking(tenantId, request);
        bookingRepository.save(booking);
        outboxRepository.save(buildOutboxEvent(booking, "booking.created"));
        return BookingMapper.toResponse(booking);
    }
}
```

---

## Controller Layer

### Standard Pattern

```java
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @GetMapping("/{bookingId}")
    @PreAuthorize("@tenantGuard.check(#tenantId)")  // MANDATORY on every endpoint
    public ResponseEntity<BookingResponse> getBooking(
        @PathVariable UUID tenantId,
        @PathVariable UUID bookingId
    ) {
        return ResponseEntity.ok(bookingService.getBooking(tenantId, bookingId));
    }

    @PostMapping
    @PreAuthorize("@tenantGuard.check(#tenantId)")
    @ResponseStatus(HttpStatus.CREATED)
    public BookingResponse createBooking(
        @PathVariable UUID tenantId,
        @Valid @RequestBody CreateBookingRequest request
    ) {
        return bookingService.createBooking(tenantId, request);
    }
}
```

**Rules:**
- `@PreAuthorize("@tenantGuard.check(#tenantId)")` on **every** endpoint — no exceptions
- Use `@Valid` on all `@RequestBody` parameters
- Return `ResponseEntity<T>` when HTTP status matters, plain `T` otherwise

---

## Security Configuration

### JWT Filter (Spring Security 6)

```java
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                            JwtAuthenticationFilter jwtFilter) throws Exception {
        return http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
```

### JWT Claims Structure

Every JWT must contain:
```json
{
  "sub": "<userId>",
  "tenant_id": "<tenantId>",
  "role": "ADMIN | STAFF | CLIENT",
  "iat": 1700000000,
  "exp": 1700003600
}
```

### Tenant Guard Bean

```java
@Component("tenantGuard")
public class TenantGuard {
    public boolean check(UUID tenantId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            UUID claimTenantId = UUID.fromString(token.getTokenAttributes().get("tenant_id").toString());
            return claimTenantId.equals(tenantId);
        }
        return false;
    }
}
```

---

## Transactional Outbox Pattern

```java
@Entity
@Table(name = "outbox")
public class OutboxEvent extends BaseEntity {
    private String aggregateType;   // "BOOKING"
    private UUID aggregateId;
    private String eventType;        // "booking.created"
    private String payload;          // JSON string of Avro payload
    private boolean processed;
    private OffsetDateTime processedAt;
}

// Inside BookingService — same transaction as booking save:
outboxRepository.save(OutboxEvent.builder()
    .tenantId(tenantId)
    .aggregateType("BOOKING")
    .aggregateId(booking.getId())
    .eventType("booking.created")
    .payload(avroSerializer.serialize(event))
    .processed(false)
    .build());
```

---

## Configuration Properties

```java
@ConfigurationProperties(prefix = "scheduling")
public record SchedulingProperties(
    SlotProperties slots,
    BookingProperties booking
) {
    public record SlotProperties(int maxAdvanceDays, int minLeadMinutes) {}
    public record BookingProperties(int pendingHoldMinutes, int maxPerTenant) {}
}
```

`application.yml`:
```yaml
scheduling:
  slots:
    max-advance-days: 90
    min-lead-minutes: 30
  booking:
    pending-hold-minutes: 10
    max-per-tenant: 10000
```

---

## Anti-Patterns (Never Do)

- ❌ `@Autowired` field injection — use constructor injection (`@RequiredArgsConstructor`)
- ❌ `entityManager.createQuery()` without `tenantId` — always filter
- ❌ Calling Kafka directly from `@Transactional` method — use outbox
- ❌ `@SpringBootTest` for unit tests — use `@ExtendWith(MockitoExtension.class)`
- ❌ `Optional.get()` without guard — use `orElseThrow()`
- ❌ `Date` or `Calendar` — use `Instant` / `OffsetDateTime` / `Duration`
