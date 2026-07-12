# ATOM-NOTIFICATION-DISPATCH-008: SES and Twilio Notification Dispatch Adapters

**Status**: 🟡 Planned
**Feature**: notification-dispatch
**Phase**: 1 (Foundation)
**Tags**: [AUTH]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-SPRING-SECURITY-005
**Blocks**: None
**PR**: TBD

---

## Overview

This atom implements email (AWS SES v2) and SMS (Twilio) dispatch adapters for OTP delivery, wired through a `DispatchService` strategy router. The `DispatchAdapter` interface provides a stable contract that allows additional channels (push, WhatsApp) to be added in later phases without modifying the router. The key design decision is that adapter failures return `DispatchResult.failure(...)` rather than throwing exceptions — dispatch errors are never allowed to fail the auth request since the OTP has already been generated and stored.

---

## User Story

```
As a Booking User
I want to receive my OTP code via email or SMS depending on my identifier type
So that I can verify my identity through the channel I registered with
```

---

## Acceptance Criteria

- [ ] **AC-01**: Email OTP dispatched via SES (verified with SES sandbox or LocalStack)
- [ ] **AC-02**: SMS OTP dispatched via Twilio (verified with Twilio test credentials)
- [ ] **AC-03**: `DispatchService` routes `email` identifier type to `EmailDispatchAdapter`
- [ ] **AC-04**: `DispatchService` routes `phone` identifier type to `SmsDispatchAdapter`
- [ ] **AC-05**: SES failure returns `DispatchResult.failure(...)` — does NOT throw 500 to the caller
- [ ] **AC-06**: Twilio failure returns `DispatchResult.failure(...)` — does NOT throw 500 to the caller
- [ ] **AC-07**: All credentials sourced from environment variables — zero hardcoded values
- [ ] **AC-08**: Unit tests mock SES and Twilio clients and verify correct adapter is called per `identifierType`
- [ ] **AC-09 (Domain abstraction)**: No industry-specific terms in any class name, method name, or message template

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | Integration test (LocalStack) | `EmailDispatchAdapter.java` | 🔜 Planned |
| AC-02 | Unit test with Twilio test credentials | `SmsDispatchAdapter.java` | 🔜 Planned |
| AC-03 | `DispatchServiceTest.java` | `DispatchService.dispatch()` | 🔜 Planned |
| AC-04 | `DispatchServiceTest.java` | `DispatchService.dispatch()` | 🔜 Planned |
| AC-05 | `EmailDispatchAdapterTest.java` | `EmailDispatchAdapter.sendOtp()` | 🔜 Planned |
| AC-06 | `SmsDispatchAdapterTest.java` | `SmsDispatchAdapter.sendOtp()` | 🔜 Planned |
| AC-07 | Code review + CI secret scan | All adapter classes | 🔜 Planned |
| AC-08 | `DispatchServiceTest.java` | All adapter classes | 🔜 Planned |
| AC-09 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 9 criteria rewritten, 9 marked TBD -->

---

## Technical Design

### Architecture

`DispatchService` holds a `List<DispatchAdapter>` injected by Spring. At dispatch time it streams the list to find the first adapter whose `supports(identifierType)` returns true, then calls `sendOtp()`. This is a classic Strategy pattern — adapters are Spring `@Component` beans auto-collected into the list. Adding a new channel requires only a new `@Component` implementing `DispatchAdapter`.

### Data Flow / Sequence (if applicable)

```
AuthService.requestOtp(identifier, tenantSlug)
  → otpService.generateAndStore() → OtpRecord (with rawOtp)
  → dispatchService.dispatch(identifier, identifierType, rawOtp, tenantName)
      → adapters.stream().filter(a -> a.supports(identifierType)).findFirst()
      → EmailDispatchAdapter.sendOtp(email, rawOtp, tenantName)
          → sesClient.sendEmail(SendEmailRequest) → DispatchResult.success()
          OR SesV2Exception → DispatchResult.failure() [never rethrows]
```

### File Structure

```
apps/api/src/main/java/com/scheduler/api/notification/
├── DispatchService.java
├── DispatchAdapter.java          ← interface
├── EmailDispatchAdapter.java
├── SmsDispatchAdapter.java
├── DispatchProperties.java       ← @ConfigurationProperties record
└── DispatchResult.java           ← record

apps/api/src/test/java/com/scheduler/api/notification/
└── DispatchServiceTest.java
```

### Interface Contracts

```java
// DispatchAdapter — strategy interface
public interface DispatchAdapter {
    DispatchResult sendOtp(String recipient, String rawOtp, String tenantName);
    DispatchResult sendMagicLink(String recipient, String magicLink, String tenantName);
    boolean supports(String identifierType);
}

// DispatchResult — operation outcome record
public record DispatchResult(boolean success, String channel, String errorMessage) {
    public static DispatchResult success(String channel);
    public static DispatchResult failure(String channel, String errorMessage);
}

// DispatchProperties — config record
@ConfigurationProperties(prefix = "app.dispatch")
public record DispatchProperties(
    String sesFromAddress,
    String twilioFromNumber
) {}

// DispatchService — public contract
@Service
public class DispatchService {
    public DispatchResult dispatch(String identifier, String identifierType,
                                   String rawOtp, String tenantName);
}
```

### Design Rationale

- The `supports(identifierType)` predicate on `DispatchAdapter` keeps routing logic out of `DispatchService` — each adapter declares its own capabilities.
- Failures return `DispatchResult.failure()` rather than throwing so that the auth flow always returns `202 ACCEPTED` to the client even if SES or Twilio is degraded. The error is logged for alerting but not surfaced to the user (who would be confused by a dispatch error distinct from an OTP error).
- AWS credentials come from the environment (`AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_REGION`) — never from `application.yml`.

---

## Test Strategy

**Test type**: Unit (JUnit 5 + Mockito)

```
- shouldRouteEmailIdentifier_toEmailAdapter:
    Given: DispatchService with mocked EmailDispatchAdapter and SmsDispatchAdapter; identifierType = "email"
    Assert: EmailDispatchAdapter.sendOtp() called once; SmsDispatchAdapter.sendOtp() not called

- shouldRoutePhoneIdentifier_toSmsAdapter:
    Given: identifierType = "phone"
    Assert: SmsDispatchAdapter.sendOtp() called once; EmailDispatchAdapter.sendOtp() not called

- shouldReturnFailure_whenSesThrows:
    Given: sesClient.sendEmail() throws SesV2Exception
    Assert: dispatch() returns DispatchResult with success=false; no exception propagated to caller

- shouldReturnFailure_whenTwilioThrows:
    Given: twilioClient throws ApiException
    Assert: dispatch() returns DispatchResult with success=false; no exception propagated to caller

- shouldReturnFailure_whenNoAdapterFound:
    Given: identifierType = "fax" (unsupported)
    Assert: dispatch() returns DispatchResult.failure with "No adapter found" message
```

**Coverage requirements**:
- Line coverage ≥ 80% on `DispatchService`, `EmailDispatchAdapter`, `SmsDispatchAdapter`
- Failure path (exception → `DispatchResult.failure`) must be tested explicitly

---

## Implementation Constraints

- All credentials sourced from environment variables — never in `application.yml` or source code
- Adapter failure must return `DispatchResult.failure()` — never propagate exceptions to the caller
- `DispatchAdapter` implementations must be Spring `@Component` beans
- DTOs must be Java 21 records (`DispatchResult`, `DispatchProperties`)
- No `System.out.println` — use SLF4J
- `sendMagicLink()` on `SmsDispatchAdapter` throws `UnsupportedOperationException` (SMS magic links not supported)
- All Next.js API calls through `apps/web/lib/api-client.ts`

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `DispatchServiceTest.java` with Mockito mocks for both adapters
2. Write all 5 test scenarios — fail (classes don't exist yet)

### GREEN — Minimum code to pass

1. Create `DispatchAdapter.java` interface
2. Create `DispatchResult.java` record with `success()` and `failure()` factories
3. Create `DispatchProperties.java` configuration record
4. Implement `EmailDispatchAdapter.java` with SES v2 client
5. Implement `SmsDispatchAdapter.java` with Twilio client
6. Implement `DispatchService.java` strategy router
7. Add `app.dispatch.*` config to `application.yml`
8. Run `DispatchServiceTest` — all 5 pass

### REFACTOR — Quality pass

1. Add structured logging for dispatch success and failure events
2. Run `/security-scan` to confirm no hardcoded SES/Twilio credentials
3. Add LocalStack integration test for SES path

---

## Implementation Reference

### DispatchAdapter.java

**File**: `apps/api/src/main/java/com/scheduler/api/notification/DispatchAdapter.java`

```java
// TASK: P1-T08
public interface DispatchAdapter {
    DispatchResult sendOtp(String recipient, String rawOtp, String tenantName);
    DispatchResult sendMagicLink(String recipient, String magicLink, String tenantName);
    boolean supports(String identifierType);
}
```

### EmailDispatchAdapter.java

**File**: `apps/api/src/main/java/com/scheduler/api/notification/EmailDispatchAdapter.java`

```java
// TASK: P1-T08
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailDispatchAdapter implements DispatchAdapter {

    private final SesV2Client sesClient;
    private final DispatchProperties props;

    @Override
    public DispatchResult sendOtp(String email, String rawOtp, String tenantName) {
        try {
            sesClient.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(props.sesFromAddress())
                .destination(d -> d.toAddresses(email))
                .content(c -> c.simple(s -> s
                    .subject(sub -> sub.data("Your " + tenantName + " verification code"))
                    .body(b -> b
                        .text(t -> t.data("Your code is: " + rawOtp + "\nExpires in 5 minutes."))
                        .html(h -> h.data(buildOtpHtml(tenantName, rawOtp)))
                    )
                ))
                .build());
            return DispatchResult.success("email");
        } catch (SesV2Exception e) {
            log.error("SES dispatch failed for {}: {}", email, e.getMessage());
            return DispatchResult.failure("email", e.getMessage());
        }
    }

    @Override
    public DispatchResult sendMagicLink(String email, String magicLink, String tenantName) {
        // Implementation follows same structure as sendOtp
        throw new UnsupportedOperationException("Magic link: implement in P1-T08");
    }

    @Override
    public boolean supports(String identifierType) { return "email".equals(identifierType); }

    private String buildOtpHtml(String tenantName, String otp) {
        return """
            <html><body>
            <h2>%s verification</h2>
            <p>Your verification code is:</p>
            <h1 style="letter-spacing:4px">%s</h1>
            <p>This code expires in 5 minutes.</p>
            </body></html>
            """.formatted(tenantName, otp);
    }
}
```

### SmsDispatchAdapter.java

**File**: `apps/api/src/main/java/com/scheduler/api/notification/SmsDispatchAdapter.java`

```java
// TASK: P1-T08
@Component
@RequiredArgsConstructor
@Slf4j
public class SmsDispatchAdapter implements DispatchAdapter {

    private final TwilioRestClient twilioClient;
    private final DispatchProperties props;

    @Override
    public DispatchResult sendOtp(String phone, String rawOtp, String tenantName) {
        try {
            Message.creator(
                new PhoneNumber(phone),
                new PhoneNumber(props.twilioFromNumber()),
                tenantName + " code: " + rawOtp + " (expires 5 min)"
            ).create(twilioClient);
            return DispatchResult.success("sms");
        } catch (ApiException e) {
            log.error("Twilio dispatch failed for {}: {}", phone, e.getMessage());
            return DispatchResult.failure("sms", e.getMessage());
        }
    }

    @Override
    public DispatchResult sendMagicLink(String phone, String magicLink, String tenantName) {
        throw new UnsupportedOperationException("Magic link via SMS not supported");
    }

    @Override
    public boolean supports(String identifierType) { return "phone".equals(identifierType); }
}
```

### DispatchService.java

**File**: `apps/api/src/main/java/com/scheduler/api/notification/DispatchService.java`

```java
// TASK: P1-T08
@Service
@RequiredArgsConstructor
@Slf4j
public class DispatchService {

    private final List<DispatchAdapter> adapters;

    public DispatchResult dispatch(String identifier, String identifierType,
                                   String rawOtp, String tenantName) {
        return adapters.stream()
            .filter(a -> a.supports(identifierType))
            .findFirst()
            .map(a -> a.sendOtp(identifier, rawOtp, tenantName))
            .orElseGet(() -> {
                log.warn("No dispatch adapter for identifierType: {}", identifierType);
                return DispatchResult.failure(identifierType, "No adapter found");
            });
    }
}
```

### DispatchProperties.java

**File**: `apps/api/src/main/java/com/scheduler/api/notification/DispatchProperties.java`

```java
// TASK: P1-T08
@ConfigurationProperties(prefix = "app.dispatch")
public record DispatchProperties(
    String sesFromAddress,
    String twilioFromNumber
) {}
```

### application.yml additions

**File**: `apps/api/src/main/resources/application.yml` (additions)

```yaml
app:
  dispatch:
    ses-from-address:    ${SES_FROM_ADDRESS}
    twilio-from-number:  ${TWILIO_FROM_NUMBER}

# AWS credentials come from environment: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, AWS_REGION
# Twilio credentials: TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN
```

---

## Integration Points

**Depends on**: ATOM-SPRING-SECURITY-005 (Spring application context must be running)

**Enables**: ATOM-AUTH-CONTROLLER-009 (`AuthService.requestOtp()` calls `dispatchService.dispatch()` after generating OTP)

**Cascading updates required**:
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/java/.../notification/DispatchAdapter.java` | New | Strategy interface |
| `apps/api/src/main/java/.../notification/DispatchResult.java` | New | Operation outcome record |
| `apps/api/src/main/java/.../notification/DispatchProperties.java` | New | Config properties record |
| `apps/api/src/main/java/.../notification/EmailDispatchAdapter.java` | New | AWS SES v2 email adapter |
| `apps/api/src/main/java/.../notification/SmsDispatchAdapter.java` | New | Twilio SMS adapter |
| `apps/api/src/main/java/.../notification/DispatchService.java` | New | Strategy router |
| `apps/api/src/main/resources/application.yml` | Modified | Add dispatch config section |
| `apps/api/src/test/java/.../notification/DispatchServiceTest.java` | New | Routing and failure tests |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] Zero hardcoded credentials — all from environment variables
- [ ] Zero industry-specific terms in any identifier or message template
- [ ] Adapter failure does not propagate exception — returns `DispatchResult.failure()`
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: notification-dispatch | Phase: 1*
