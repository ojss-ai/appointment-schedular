// TASK: P1-T07
package com.scheduler.api.security.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * Issues and validates HS256-signed JWTs carrying all nine required claims
 * (SECURITY-SPEC 2.1): sub, iss, aud, iat, exp, jti, tenantId, userId,
 * roleClaims. {@code validateToken} throws {@link io.jsonwebtoken.JwtException}
 * on any failure — it never returns null.
 */
@Service
@RequiredArgsConstructor
public class JwtService {

    public static final String ISSUER = "scheduler-api";
    public static final String AUDIENCE = "scheduler-clients";

    private final JwtProperties props;

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(props.secretBytes());
    }

    /** Generate a signed JWT with all required claims. */
    public String generateToken(UUID userId, UUID tenantId, List<String> roleClaims) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant exp = now.plus(props.expiryHours(), ChronoUnit.HOURS);

        return Jwts.builder()
            .id(jti)
            .subject(userId.toString())
            .issuer(ISSUER)
            .audience().add(AUDIENCE).and()
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .claim("tenantId", tenantId.toString())
            .claim("userId", userId.toString())
            .claim("roleClaims", roleClaims)
            .signWith(signingKey(), Jwts.SIG.HS256)
            .compact();
    }

    /** Validate and parse a JWT. Throws JwtException on any failure. */
    @SuppressWarnings("unchecked")
    public JwtClaims validateToken(String token) {
        Claims claims = Jwts.parser()
            .verifyWith(signingKey())
            .requireIssuer(ISSUER)
            .requireAudience(AUDIENCE)
            .build()
            .parseSignedClaims(token)
            .getPayload();

        String tenantId = claims.get("tenantId", String.class);
        String userId = claims.get("userId", String.class);
        if (tenantId == null || userId == null) {
            throw new MalformedJwtException("Token missing required tenantId/userId claim");
        }

        List<String> roleClaims = claims.get("roleClaims", List.class);

        return new JwtClaims(
            UUID.fromString(tenantId),
            UUID.fromString(userId),
            roleClaims == null ? List.of() : List.copyOf(roleClaims),
            claims.getId(),
            claims.getIssuedAt().toInstant(),
            claims.getExpiration().toInstant()
        );
    }

    public UUID extractTenantId(String token) {
        return validateToken(token).tenantId();
    }

    public UUID extractUserId(String token) {
        return validateToken(token).userId();
    }

    public List<String> extractRoleClaims(String token) {
        return validateToken(token).roleClaims();
    }
}
