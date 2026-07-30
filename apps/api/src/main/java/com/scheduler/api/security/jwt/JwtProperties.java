// TASK: P1-T07
package com.scheduler.api.security.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * JWT configuration bound from {@code app.jwt.*}. The secret must come from
 * the {@code JWT_SECRET} environment variable and be at least 32 bytes —
 * application startup fails otherwise (AC-09, ATOM-JWT-BUILDER-007).
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(
    String secret,      // minimum 256-bit key material (base64 or raw)
    int expiryHours     // access-token lifetime
) {

    public JwtProperties {
        if (secret == null || secretBytes(secret).length < 32) {
            throw new IllegalStateException(
                "app.jwt.secret must be set (JWT_SECRET env var) and be at least 32 bytes");
        }
        if (expiryHours <= 0) {
            expiryHours = 24;
        }
    }

    /** Decodes the secret as base64 when possible, otherwise raw UTF-8 bytes. */
    public static byte[] secretBytes(String secret) {
        try {
            byte[] decoded = Base64.getDecoder().decode(secret);
            if (decoded.length >= 32) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {
            // Not base64 — fall through to raw bytes.
        }
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    public byte[] secretBytes() {
        return secretBytes(secret);
    }
}
