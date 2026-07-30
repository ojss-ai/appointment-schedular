# Workflow: Onboard a New Tenant Configuration
<!-- ATOM-ORCHESTRATION-002 — 5-step tenant onboarding workflow. -->

## Preconditions
- [ ] Tenant display name, URL slug, and subscription plan provided
- [ ] Admin user email address provided
- [ ] At least one Location timezone specified

## Steps

1. **[coder]** Insert tenant row via admin API: POST /api/v1/admin/tenants with name, slug, and plan
2. **[coder]** Create initial admin user for the tenant via POST /api/v1/admin/tenants/{tenantId}/users
3. **[coder]** Seed one Location with the specified timezone via POST /api/v1/tenants/{tenantId}/locations
4. **[coder]** Seed one Service type with basic duration and buffer settings via POST /api/v1/tenants/{tenantId}/services
5. **[coder]** Verify end-to-end: admin user can authenticate via OTP flow and complete a booking for the seeded Service

## Success Criteria
- Admin OTP login succeeds for the new tenant
- Booking flow is visible and functional in the UI for the new tenant
- No data from other tenants is visible in the new tenant's session
