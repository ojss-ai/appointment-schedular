# scheduling:security-rules
> Maintained by: security agent
> Last updated: 2026-07-20

## Tenant isolation pattern
- `JwtAuthFilter` validates the Bearer token, populates `TenantContext` (ThreadLocal) + MDC, and clears both in a `finally` block.
- `TenantGuard` (bean name `tenantGuard`) backs `@PreAuthorize("@tenantGuard.check(#tenantId)")` on every tenant-scoped controller method.
- `TenantFilterAspect` (@Before on `@TenantScoped` services) throws IllegalStateException when `TenantContext` is empty — second enforcement line per SECURITY-SPEC 3.1.
- Every JPA query carries `tenant_id` in its WHERE clause. Zero exceptions.

## JWT claim structure (HS256, JJWT 0.12.x)
| Claim | Value |
|---|---|
| sub | user UUID |
| iss | scheduler-api |
| aud | scheduler-clients |
| iat / exp | issue / expiry (app.jwt.expiry-hours) |
| jti | unique token UUID |
| tenantId | tenant UUID |
| userId | user UUID (mirrors sub) |
| roleClaims | e.g. ["ROLE_CUSTOMER"] |

Secret: `app.jwt.secret` env var only, >= 32 bytes enforced at startup.

## OTP rules
6-char uppercase alphanumeric (no 0/O/1/I), SecureRandom, bcrypt hash in Redis
key `otp:{identifier}` TTL 300s, single-use via GETDEL, rate limit 5/hr via
`otp-rate:{identifier}`.
