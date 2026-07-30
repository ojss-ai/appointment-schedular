# scheduling:api-contracts
> Maintained by: adr-docs agent
> Last updated: 2026-07-20

| Method | Path | Auth | Request | Response | Atom |
|---|---|---|---|---|---|
| POST | /api/v1/auth/request-otp | none | { identifier, tenantSlug } | 202 { status, maskedIdentifier, expiresAt } / 429 rate limit | ATOM-AUTH-FLOW-009 |
| POST | /api/v1/auth/verify-otp | none | { identifier, tenantSlug, otp } | 200 { status:"SUCCESS", token } / 401 { status:"OTP_INVALID"\|"OTP_EXPIRED" } | ATOM-AUTH-FLOW-009 |
| GET | /health | none | — | 200 { status:"UP", version } | ATOM-SPRING-SECURITY-005 |
| GET | /actuator/health | none | — | 200 { status:"UP" } | ATOM-SPRING-SECURITY-005 |
