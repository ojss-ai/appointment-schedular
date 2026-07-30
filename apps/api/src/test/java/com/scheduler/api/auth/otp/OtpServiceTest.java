// TASK: P1-T06
package com.scheduler.api.auth.otp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private OtpRepository otpRepository;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder(4);

    private OtpService otpService;

    private final UUID tenantId = UUID.randomUUID();
    private static final String IDENTIFIER = "user@example.com";

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(otpRepository.save(any(OtpRecord.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        otpService = new OtpService(redis, otpRepository, encoder);
    }

    @Test
    void shouldStoreHashedOtp_inRedisWithCorrectTTL() {
        when(valueOps.increment("otp-rate:" + IDENTIFIER)).thenReturn(1L);

        OtpRecord record = otpService.generateAndStore(IDENTIFIER, tenantId, "EMAIL");

        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq("otp:" + IDENTIFIER), value.capture(),
            eq((long) OtpConstants.TTL_SECONDS), eq(TimeUnit.SECONDS));

        assertThat(record.getRawOtp()).hasSize(6).matches("[A-HJ-NP-Z2-9]{6}");
        assertThat(value.getValue()).isNotEqualTo(record.getRawOtp());
        assertThat(encoder.matches(record.getRawOtp(), value.getValue())).isTrue();
        assertThat(record.getOtpHash()).isEqualTo(value.getValue());
        assertThat(record.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void shouldReturnSuccess_andDeleteKey_forCorrectOtp() {
        String raw = "A3K9PQ";
        when(valueOps.getAndDelete("otp:" + IDENTIFIER)).thenReturn(encoder.encode(raw));

        VerificationResult result = otpService.verify(IDENTIFIER, raw, tenantId);

        assertThat(result.isSuccess()).isTrue();
        verify(valueOps).getAndDelete("otp:" + IDENTIFIER);
        verify(otpRepository).markUsed(tenantId, IDENTIFIER);
    }

    @Test
    void shouldReturnInvalid_andDeleteKey_forWrongOtp() {
        when(valueOps.getAndDelete("otp:" + IDENTIFIER)).thenReturn(encoder.encode("A3K9PQ"));

        VerificationResult result = otpService.verify(IDENTIFIER, "WRONG9", tenantId);

        assertThat(result.status()).isEqualTo(VerificationResult.Status.INVALID);
        verify(valueOps).getAndDelete("otp:" + IDENTIFIER);
        verify(otpRepository).markFailed(tenantId, IDENTIFIER);
        verify(otpRepository, never()).markUsed(any(), anyString());
    }

    @Test
    void shouldReturnExpired_whenKeyAbsent() {
        when(valueOps.getAndDelete("otp:" + IDENTIFIER)).thenReturn(null);

        VerificationResult result = otpService.verify(IDENTIFIER, "A3K9PQ", tenantId);

        assertThat(result.status()).isEqualTo(VerificationResult.Status.EXPIRED);
        verify(otpRepository, never()).markUsed(any(), anyString());
    }

    @Test
    void shouldThrowRateLimitException_onSixthRequest() {
        when(valueOps.increment("otp-rate:" + IDENTIFIER)).thenReturn(6L);

        assertThatThrownBy(() -> otpService.generateAndStore(IDENTIFIER, tenantId, "EMAIL"))
            .isInstanceOf(OtpRateLimitException.class);
        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }
}
