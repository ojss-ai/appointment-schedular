// TASK: P1-T06
package com.scheduler.api.auth.otp;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.scheduler.api.auth.otp.OtpConstants.OTP_ALPHABET;
import static com.scheduler.api.auth.otp.OtpConstants.OTP_KEY_PREFIX;
import static com.scheduler.api.auth.otp.OtpConstants.OTP_LENGTH;
import static com.scheduler.api.auth.otp.OtpConstants.RATE_KEY_PREFIX;
import static com.scheduler.api.auth.otp.OtpConstants.RATE_LIMIT_MAX;
import static com.scheduler.api.auth.otp.OtpConstants.RATE_LIMIT_WINDOW;
import static com.scheduler.api.auth.otp.OtpConstants.TTL_SECONDS;

/**
 * Cryptographically secure, single-use, rate-limited OTP subsystem
 * (ATOM-OTP-REDIS-006). Redis enforces TTL and single-use semantics via an
 * atomic GETDEL; the otp_records row is an audit trail, never a
 * verification source.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OtpService {

    private final StringRedisTemplate redis;
    private final OtpRepository otpRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();

    /** Generate an OTP, store its bcrypt hash in Redis with TTL, persist the audit row. */
    @Transactional
    public OtpRecord generateAndStore(String identifier, UUID tenantId, String channel) {
        checkRateLimit(identifier);

        String rawOtp = generateOtp();
        String hashedOtp = passwordEncoder.encode(rawOtp);

        redis.opsForValue().set(otpKey(identifier), hashedOtp, TTL_SECONDS, TimeUnit.SECONDS);

        OtpRecord record = OtpRecord.builder()
            .tenantId(tenantId)
            .identifier(identifier)
            .otpHash(hashedOtp)
            .channel(channel)
            .status(OtpRecord.STATUS_PENDING)
            .expiresAt(Instant.now().plusSeconds(TTL_SECONDS))
            .build();
        otpRepository.save(record);

        // Attach raw OTP transiently so the caller can deliver it.
        record.setRawOtp(rawOtp);
        log.info("OTP generated for identifier hash tenant={} channel={}", tenantId, channel);
        return record;
    }

    /** Verify a submitted OTP. Single-use: the Redis key is consumed on any attempt. */
    @Transactional
    public VerificationResult verify(String identifier, String submittedOtp, UUID tenantId) {
        String storedHash = redis.opsForValue().getAndDelete(otpKey(identifier));

        if (storedHash == null) {
            log.info("OTP verification expired/absent tenant={}", tenantId);
            return VerificationResult.expired();
        }
        if (!passwordEncoder.matches(submittedOtp, storedHash)) {
            otpRepository.markFailed(tenantId, identifier);
            log.info("OTP verification failed tenant={}", tenantId);
            return VerificationResult.invalid();
        }
        otpRepository.markUsed(tenantId, identifier);
        log.info("OTP verification succeeded tenant={}", tenantId);
        return VerificationResult.success();
    }

    /** Explicit invalidation (e.g. magic-link flow). */
    public void invalidate(String identifier) {
        redis.delete(otpKey(identifier));
    }

    // --- Private helpers ---

    private void checkRateLimit(String identifier) {
        String rateKey = rateKey(identifier);
        Long count = redis.opsForValue().increment(rateKey);
        if (count != null && count == 1L) {
            redis.expire(rateKey, RATE_LIMIT_WINDOW, TimeUnit.SECONDS);
        }
        if (count != null && count > RATE_LIMIT_MAX) {
            throw new OtpRateLimitException("OTP rate limit exceeded for identifier");
        }
    }

    private String generateOtp() {
        StringBuilder otp = new StringBuilder(OTP_LENGTH);
        for (int i = 0; i < OTP_LENGTH; i++) {
            otp.append(OTP_ALPHABET.charAt(secureRandom.nextInt(OTP_ALPHABET.length())));
        }
        return otp.toString();
    }

    private static String otpKey(String identifier) {
        return OTP_KEY_PREFIX + identifier;
    }

    private static String rateKey(String identifier) {
        return RATE_KEY_PREFIX + identifier;
    }
}
