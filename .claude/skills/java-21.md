# Java 21 Skill — Scheduling Framework Patterns

> Reference for the Coder and TestGen agents. Applies to all code in `apps/api/`.

---

## Key Java 21 Features in Use

### Records (DTOs)

All request/response DTOs **must** be records. No Lombok, no plain classes for DTOs.

```java
// Request
public record CreateBookingRequest(
    UUID tenantId,
    UUID resourceId,
    UUID serviceId,
    UUID locationId,
    Instant startTime,
    String notes
) {}

// Response
public record BookingResponse(
    UUID id,
    UUID tenantId,
    BookingStatus status,
    Instant startTime,
    Instant endTime,
    OffsetDateTime createdAt
) {}

// Nested value object
public record SlotWindow(Instant start, Instant end) {
    public boolean overlaps(SlotWindow other) {
        return start.isBefore(other.end) && end.isAfter(other.start);
    }
}
```

### Sealed Classes (Domain States)

Use sealed interfaces for exhaustive domain state modelling:

```java
public sealed interface BookingResult
    permits BookingResult.Success, BookingResult.Conflict, BookingResult.NotFound {

    record Success(UUID bookingId, Instant startTime) implements BookingResult {}
    record Conflict(String reason, List<SlotWindow> conflictingWindows) implements BookingResult {}
    record NotFound(String entityType, UUID id) implements BookingResult {}
}
```

Pattern-match in controller:
```java
return switch (result) {
    case BookingResult.Success s    -> ResponseEntity.ok(toResponse(s));
    case BookingResult.Conflict c   -> ResponseEntity.status(409).body(c.reason());
    case BookingResult.NotFound nf  -> ResponseEntity.notFound().build();
};
```

### Text Blocks (SQL in Services)

Use text blocks for multi-line JPQL or native SQL in repository custom queries:

```java
@Query(value = """
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
```

### Pattern Matching instanceof

```java
// Prefer over explicit cast
if (event instanceof BookingCreatedEvent created) {
    processCreated(created.bookingId(), created.tenantId());
}
```

### Switch Expressions

```java
Duration serviceDuration = switch (serviceType) {
    case QUICK    -> Duration.ofMinutes(15);
    case STANDARD -> Duration.ofMinutes(30);
    case EXTENDED -> Duration.ofMinutes(60);
};
```

---

## Exception Handling Conventions

```java
// Domain exceptions — always carry tenant context
public class BookingConflictException extends RuntimeException {
    private final UUID tenantId;
    private final UUID resourceId;

    public BookingConflictException(UUID tenantId, UUID resourceId, String message) {
        super(message);
        this.tenantId = tenantId;
        this.resourceId = resourceId;
    }
}

// Global handler
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BookingConflictException.class)
    public ResponseEntity<ErrorResponse> handleConflict(BookingConflictException ex) {
        log.warn("Booking conflict tenant={} resource={}", ex.getTenantId(), ex.getResourceId());
        return ResponseEntity.status(409)
            .body(new ErrorResponse("BOOKING_CONFLICT", ex.getMessage()));
    }
}
```

---

## Structured Logging (SLF4J — no console.log equivalent)

```java
// Correct — structured key=value pairs, no string concatenation
log.info("Booking created bookingId={} tenantId={} resourceId={} startTime={}",
    booking.getId(), booking.getTenantId(), booking.getResourceId(), booking.getStartTime());

// Wrong — never do this in production code
System.out.println("booking: " + booking);
```

---

## Utility Patterns

```java
// Time math for slot boundaries
Instant slotEnd = slotStart.plus(serviceDuration);
boolean isInFuture = slotStart.isAfter(Instant.now());

// Safe optional chaining
Optional.ofNullable(extension)
    .map(ext -> ext.get("customField"))
    .map(JsonNode::asText)
    .orElse(null);
```

---

## Anti-Patterns (Never Do)

- ❌ `new Date()` — use `Instant` / `OffsetDateTime`
- ❌ `@Data` (Lombok) on entities — use records for DTOs, plain getters on entities
- ❌ `Optional.get()` without `isPresent()` check — use `orElseThrow()`
- ❌ Catching `Exception` broadly — catch specific domain exceptions
- ❌ `System.out.println` — use SLF4J logger
