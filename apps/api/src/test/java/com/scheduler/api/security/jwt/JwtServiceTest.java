// TASK: P1-T07
package com.scheduler.api.security.jwt;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "unit-test-secret-key-that-is-definitely-32+bytes";

    private JwtService jwtService;
    private final UUID userId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(new JwtProperties(SECRET, 1));
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void shouldGenerateToken_withAllRequiredClaims() {
        String token = jwtService.generateToken(userId, tenantId, List.of("ROLE_CUSTOMER"));

        var claims = Jwts.parser().verifyWith(key()).build()
            .parseSignedClaims(token).getPayload();

        assertThat(claims.getSubject()).isEqualTo(userId.toString());
        assertThat(claims.getIssuer()).isEqualTo("scheduler-api");
        assertThat(claims.getAudience()).contains("scheduler-clients");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.get("tenantId", String.class)).isEqualTo(tenantId.toString());
        assertThat(claims.get("userId", String.class)).isEqualTo(userId.toString());
        assertThat(claims.get("roleClaims", List.class)).containsExactly("ROLE_CUSTOMER");
    }

    @Test
    void shouldValidateToken_andReturnCorrectClaims() {
        String token = jwtService.generateToken(userId, tenantId, List.of("ROLE_CUSTOMER"));

        JwtClaims claims = jwtService.validateToken(token);

        assertThat(claims.tenantId()).isEqualTo(tenantId);
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.roleClaims()).containsExactly("ROLE_CUSTOMER");
        assertThat(claims.tenantId()).isNotNull();
        assertThat(claims.jti()).isNotBlank();
    }

    @Test
    void shouldThrowJwtException_forExpiredToken() {
        Instant past = Instant.now().minusSeconds(120);
        String expired = Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(userId.toString())
            .issuer("scheduler-api")
            .audience().add("scheduler-clients").and()
            .issuedAt(Date.from(past))
            .expiration(Date.from(past.plusSeconds(1)))
            .claim("tenantId", tenantId.toString())
            .claim("userId", userId.toString())
            .claim("roleClaims", List.of("ROLE_CUSTOMER"))
            .signWith(key(), Jwts.SIG.HS256)
            .compact();

        assertThatThrownBy(() -> jwtService.validateToken(expired))
            .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldThrowJwtException_forWrongAudience() {
        String wrongAud = Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(userId.toString())
            .issuer("scheduler-api")
            .audience().add("wrong-clients").and()
            .issuedAt(new Date())
            .expiration(Date.from(Instant.now().plusSeconds(3600)))
            .claim("tenantId", tenantId.toString())
            .claim("userId", userId.toString())
            .signWith(key(), Jwts.SIG.HS256)
            .compact();

        assertThatThrownBy(() -> jwtService.validateToken(wrongAud))
            .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldThrowJwtException_forTamperedPayload() {
        String token = jwtService.generateToken(userId, tenantId, List.of("ROLE_CUSTOMER"));
        String[] parts = token.split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String tampered = payload.replace(tenantId.toString(), UUID.randomUUID().toString());
        String tamperedToken = parts[0] + "."
            + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(tampered.getBytes(StandardCharsets.UTF_8))
            + "." + parts[2];

        assertThatThrownBy(() -> jwtService.validateToken(tamperedToken))
            .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldThrowJwtException_forMissingTenantIdClaim() {
        String noTenant = Jwts.builder()
            .id(UUID.randomUUID().toString())
            .subject(userId.toString())
            .issuer("scheduler-api")
            .audience().add("scheduler-clients").and()
            .issuedAt(new Date())
            .expiration(Date.from(Instant.now().plusSeconds(3600)))
            .claim("userId", userId.toString())
            .signWith(key(), Jwts.SIG.HS256)
            .compact();

        assertThatThrownBy(() -> jwtService.validateToken(noTenant))
            .isInstanceOf(JwtException.class);
    }

    @Test
    void shouldRejectSecret_shorterThan32Bytes() {
        assertThatThrownBy(() -> new JwtProperties("too-short", 1))
            .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new JwtProperties(SECRET, 1)).doesNotThrowAnyException();
    }
}
