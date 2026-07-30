// TASK: P1-T09
package com.scheduler.api.auth;

import com.scheduler.api.auth.dto.RequestOtpRequest;
import com.scheduler.api.auth.dto.RequestOtpResponse;
import com.scheduler.api.auth.dto.VerifyOtpRequest;
import com.scheduler.api.auth.dto.VerifyOtpResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

import static com.scheduler.api.auth.otp.OtpConstants.TTL_SECONDS;

/**
 * Public auth endpoints — permitAll() in SecurityConfig by design, hence no
 * {@code @PreAuthorize} here. request-otp always answers 202 so the response
 * never reveals whether an identifier exists.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;

    /** POST /api/v1/auth/request-otp — body: { identifier, tenantSlug }. */
    @PostMapping("/request-otp")
    public ResponseEntity<RequestOtpResponse> requestOtp(
            @Valid @RequestBody RequestOtpRequest req) {
        authService.requestOtp(req.identifier(), req.tenantSlug());
        return ResponseEntity.accepted()
            .body(new RequestOtpResponse("OTP_SENT",
                maskIdentifier(req.identifier()),
                Instant.now().plusSeconds(TTL_SECONDS)));
    }

    /** POST /api/v1/auth/verify-otp — body: { identifier, tenantSlug, otp }. */
    @PostMapping("/verify-otp")
    public ResponseEntity<VerifyOtpResponse> verifyOtp(
            @Valid @RequestBody VerifyOtpRequest req) {
        VerifyOtpResponse response = authService.verifyOtp(
            req.identifier(), req.tenantSlug(), req.otp());
        if ("SUCCESS".equals(response.status())) {
            return ResponseEntity.ok(response);
        }
        // OTP_INVALID / OTP_EXPIRED -> 401 per API-SPEC error reference
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    static String maskIdentifier(String identifier) {
        if (identifier.contains("@")) {
            int at = identifier.indexOf('@');
            return identifier.substring(0, Math.min(2, at)) + "***" + identifier.substring(at);
        }
        if (identifier.length() <= 5) {
            return "***";
        }
        return identifier.substring(0, 3) + "***" + identifier.substring(identifier.length() - 2);
    }
}
