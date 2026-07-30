// TASK: P1-T05 / P1-T07
package com.scheduler.api.security;

import com.scheduler.api.security.jwt.JwtClaims;
import com.scheduler.api.security.jwt.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Extracts and validates the {@code Authorization: Bearer} token, populates
 * {@link TenantContext} + MDC, and installs a {@link TenantAwarePrincipal}
 * into the SecurityContext. Any JWT failure short-circuits with 401 — never
 * a 500. Context is always cleared in the {@code finally} block.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String MDC_TENANT_KEY = "tenantId";

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                JwtClaims claims = jwtService.validateToken(token);
                TenantContext.set(claims.tenantId(), claims.userId(), claims.roleClaims());
                MDC.put(MDC_TENANT_KEY, claims.tenantId().toString());
                UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                        new TenantAwarePrincipal(claims), null,
                        claims.roleClaims().stream()
                            .map(SimpleGrantedAuthority::new).toList());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException e) {
                log.debug("Rejected JWT: {}", e.getMessage());
                res.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return;
            }
        }
        try {
            chain.doFilter(req, res);
        } finally {
            TenantContext.clear();
            MDC.remove(MDC_TENANT_KEY);
        }
    }
}
