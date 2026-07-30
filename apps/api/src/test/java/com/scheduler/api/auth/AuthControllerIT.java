// TASK: P1-T09
package com.scheduler.api.auth;

import com.scheduler.api.auth.dto.RequestOtpRequest;
import com.scheduler.api.auth.dto.VerifyOtpRequest;
import com.scheduler.api.auth.dto.VerifyOtpResponse;
import com.scheduler.api.notification.DispatchResult;
import com.scheduler.api.notification.DispatchService;
import com.scheduler.api.tenant.Tenant;
import com.scheduler.api.tenant.TenantRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

/**
 * Full-stack auth flow tests (7 scenarios) against real PostgreSQL 15 and
 * Redis 7 via Testcontainers.
 */
@SpringBootTest(webEnvironment = RANDOM_PORT)
@Testcontainers
class AuthControllerIT {

    private static final String JWT_SECRET = "integration-test-secret-key-32+bytes-long!!";
    private static final String TENANT_SLUG = "mc-branch";

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
        r.add("app.jwt.secret", () -> JWT_SECRET);
        r.add("app.jwt.expiry-hours", () -> 1);
    }

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockBean
    private DispatchService dispatchService;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        when(dispatchService.dispatch(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(DispatchResult.success("email"));
        tenantId = tenantRepository.findBySlug(TENANT_SLUG)
            .orElseGet(() -> tenantRepository.save(Tenant.builder()
                .name("Metro Branch")
                .slug(TENANT_SLUG)
                .build()))
            .getId();
    }

    private String requestOtpAndCapture(String identifier) {
        ResponseEntity<Map> res = rest.postForEntity("/api/v1/auth/request-otp",
            new RequestOtpRequest(identifier, TENANT_SLUG), Map.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(String.valueOf(res.getBody().get("maskedIdentifier")))
            .isNotEqualTo(identifier)
            .contains("***");
        ArgumentCaptor<String> otpCaptor = ArgumentCaptor.forClass(String.class);
        verify(dispatchService, atLeastOnce())
            .dispatch(anyString(), anyString(), otpCaptor.capture(), anyString());
        return otpCaptor.getValue();
    }

    private ResponseEntity<VerifyOtpResponse> verifyOtp(String identifier, String otp) {
        return rest.postForEntity("/api/v1/auth/verify-otp",
            new VerifyOtpRequest(identifier, TENANT_SLUG, otp), VerifyOtpResponse.class);
    }

    @Test
    void happyPathEmail_requestAndVerify_returnsJwt() {
        String identifier = "user-" + UUID.randomUUID() + "@example.com";
        String otp = requestOtpAndCapture(identifier);

        ResponseEntity<VerifyOtpResponse> res = verifyOtp(identifier, otp);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().status()).isEqualTo("SUCCESS");
        assertThat(res.getBody().token()).isNotBlank();

        var claims = Jwts.parser()
            .verifyWith(Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8)))
            .build()
            .parseSignedClaims(res.getBody().token())
            .getPayload();
        assertThat(claims.get("tenantId", String.class)).isEqualTo(tenantId.toString());
        assertThat(claims.get("userId", String.class)).isNotBlank();
        assertThat(claims.get("roleClaims", List.class)).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void happyPathPhone_requestAndVerify_returnsJwt() {
        String identifier = "+1555" + String.format("%07d",
            Math.abs(UUID.randomUUID().hashCode()) % 10_000_000);
        String otp = requestOtpAndCapture(identifier);

        ResponseEntity<VerifyOtpResponse> res = verifyOtp(identifier, otp);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody().status()).isEqualTo("SUCCESS");
        assertThat(res.getBody().token()).isNotBlank();
    }

    @Test
    void expiredOtp_returns401WithOTP_EXPIRED() {
        String identifier = "expired-" + UUID.randomUUID() + "@example.com";
        String otp = requestOtpAndCapture(identifier);

        // Force expiry: remove the Redis key as if TTL elapsed.
        redisTemplate.delete("otp:" + identifier);

        ResponseEntity<VerifyOtpResponse> res = verifyOtp(identifier, otp);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().status()).isEqualTo("OTP_EXPIRED");
        assertThat(res.getBody().token()).isNull();
    }

    @Test
    void alreadyUsedOtp_returns401() {
        String identifier = "used-" + UUID.randomUUID() + "@example.com";
        String otp = requestOtpAndCapture(identifier);

        assertThat(verifyOtp(identifier, otp).getStatusCode()).isEqualTo(HttpStatus.OK);

        // Second submission: key consumed on first use -> expired semantics.
        ResponseEntity<VerifyOtpResponse> second = verifyOtp(identifier, otp);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(second.getBody().status()).isEqualTo("OTP_EXPIRED");
    }

    @Test
    void wrongOtp_returns401WithOTP_INVALID() {
        String identifier = "wrong-" + UUID.randomUUID() + "@example.com";
        requestOtpAndCapture(identifier);

        ResponseEntity<VerifyOtpResponse> res = verifyOtp(identifier, "XXXXXX");

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(res.getBody().status()).isEqualTo("OTP_INVALID");
        assertThat(res.getBody().token()).isNull();
    }

    @Test
    void sixthRequest_returns429() {
        String identifier = "ratelimited-" + UUID.randomUUID() + "@example.com";
        for (int i = 0; i < 5; i++) {
            ResponseEntity<Map> ok = rest.postForEntity("/api/v1/auth/request-otp",
                new RequestOtpRequest(identifier, TENANT_SLUG), Map.class);
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        }

        ResponseEntity<Map> sixth = rest.postForEntity("/api/v1/auth/request-otp",
            new RequestOtpRequest(identifier, TENANT_SLUG), Map.class);

        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(String.valueOf(sixth.getBody().get("code")))
            .isEqualTo("OTP_RATE_LIMIT_EXCEEDED");
    }

    @Test
    void invalidIdentifierFormat_returns400() {
        ResponseEntity<Map> res = rest.postForEntity("/api/v1/auth/request-otp",
            new RequestOtpRequest("   ", TENANT_SLUG), Map.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
