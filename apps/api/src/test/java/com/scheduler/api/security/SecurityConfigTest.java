// TASK: P1-T05
package com.scheduler.api.security;

import com.scheduler.api.common.HealthController;
import com.scheduler.api.config.SecurityConfig;
import com.scheduler.api.security.jwt.JwtClaims;
import com.scheduler.api.security.jwt.JwtService;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the public/protected split of the security filter chain
 * (AC-02, AC-03, AC-08 of ATOM-SPRING-SECURITY-005).
 */
@WebMvcTest(controllers = HealthController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private DataSource dataSource;

    @MockBean
    private StringRedisTemplate redisTemplate;

    @Test
    void shouldReturn200_forHealthWithoutToken() throws Exception {
        mockMvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void shouldReturn401_forUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/v1/tenants/" + UUID.randomUUID() + "/locations"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldReturn401_forInvalidBearerToken() throws Exception {
        when(jwtService.validateToken(anyString()))
            .thenThrow(new MalformedJwtException("bad token"));

        mockMvc.perform(get("/api/v1/anything")
                .header("Authorization", "Bearer not-a-jwt"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void shouldAccept_validBearerToken() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(jwtService.validateToken("good-token")).thenReturn(new JwtClaims(
            tenantId, userId, List.of("ROLE_CUSTOMER"), "jti",
            Instant.now(), Instant.now().plusSeconds(3600)));

        // /health is public anyway, but a valid token must not break the chain
        mockMvc.perform(get("/health").header("Authorization", "Bearer good-token"))
            .andExpect(status().isOk());
    }
}
