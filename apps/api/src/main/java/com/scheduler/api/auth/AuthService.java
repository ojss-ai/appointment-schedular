// TASK: P1-T09
package com.scheduler.api.auth;

import com.scheduler.api.auth.dto.VerifyOtpResponse;
import com.scheduler.api.auth.otp.OtpRecord;
import com.scheduler.api.auth.otp.OtpService;
import com.scheduler.api.auth.otp.VerificationResult;
import com.scheduler.api.notification.DispatchResult;
import com.scheduler.api.notification.DispatchService;
import com.scheduler.api.security.jwt.JwtService;
import com.scheduler.api.tenant.Tenant;
import com.scheduler.api.tenant.TenantNotFoundException;
import com.scheduler.api.tenant.TenantRepository;
import com.scheduler.api.user.User;
import com.scheduler.api.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Auth orchestration (ATOM-AUTH-FLOW-009). Tenants are resolved by slug —
 * never by a raw tenantId in the request body; post-login the JWT tenantId
 * claim is authoritative. Dispatch failures are logged, never surfaced:
 * the OTP already exists and revealing delivery state would leak whether
 * an identifier is registered.
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AuthService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final OtpService otpService;
    private final DispatchService dispatchService;
    private final JwtService jwtService;

    public void requestOtp(String identifier, String tenantSlug) {
        Tenant tenant = tenantRepository.findBySlug(tenantSlug)
            .orElseThrow(() -> new TenantNotFoundException(tenantSlug));

        String identifierType = identifier.contains("@") ? "email" : "phone";
        String channel = identifier.contains("@") ? "EMAIL" : "SMS";
        OtpRecord record = otpService.generateAndStore(identifier, tenant.getId(), channel);

        try {
            DispatchResult result = dispatchService.dispatch(
                identifier, identifierType, record.getRawOtp(), tenant.getName());
            if (!result.success()) {
                log.error("OTP dispatch failed tenant={} channel={} reason={}",
                    tenant.getId(), result.channel(), result.errorMessage());
            }
        } catch (RuntimeException e) {
            // Defence in depth: adapters already return failures as values.
            log.error("OTP dispatch raised unexpectedly tenant={}: {}", tenant.getId(), e.getMessage());
        }
    }

    public VerifyOtpResponse verifyOtp(String identifier, String tenantSlug, String submittedOtp) {
        Tenant tenant = tenantRepository.findBySlug(tenantSlug)
            .orElseThrow(() -> new TenantNotFoundException(tenantSlug));

        VerificationResult result = otpService.verify(identifier, submittedOtp, tenant.getId());

        return switch (result.status()) {
            case SUCCESS -> {
                User user = userRepository.findOrCreate(identifier, tenant.getId());
                String token = jwtService.generateToken(user.getId(), tenant.getId(),
                    List.of("ROLE_" + user.getRole().toUpperCase(Locale.ROOT)));
                yield new VerifyOtpResponse("SUCCESS", token, null);
            }
            case INVALID -> new VerifyOtpResponse("OTP_INVALID", null,
                "The code you entered is incorrect.");
            case EXPIRED -> new VerifyOtpResponse("OTP_EXPIRED", null,
                "The code has expired. Please request a new one.");
        };
    }
}
