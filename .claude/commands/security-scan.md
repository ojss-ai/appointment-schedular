# /security-scan

Run the full security audit for the Multi-Tenant Scheduling Framework.

## Steps

1. **Dependency CVE scan**
   - Java: `cd apps/api && mvn dependency-check:check -q`
   - Node: `cd apps/web && npm audit --audit-level=high`
   - Flag any CVSS ≥ 4.0 findings.

2. **Tenant isolation audit**
   - Grep all `@Repository` interfaces for queries missing `tenant_id`:
     `grep -rn "findAll\|@Query" apps/api/src/main/java --include="*.java"`
   - Verify every `@RestController` method has `@PreAuthorize("@tenantGuard.check(#tenantId)")`.

3. **JWT claim audit**
   - Locate the JWT builder class and confirm `tenant_id`, `user_id`, `role_claims` are always set.

4. **OTP lifecycle check**
   - Verify OTP TTL is 5 minutes and single-use invalidation is implemented.

5. **Kafka PII check**
   - Grep Avro schemas for fields that may carry raw PII (name, email, phone, address).

6. **Write results**
   - Append findings to `docs/SECURITY-FINDINGS.md` with today's date as the section header.
   - CRITICAL or HIGH findings must be listed as action items at the top.

## Output

Print a one-line summary: `Security scan complete — {N} CRITICAL, {N} HIGH, {N} MEDIUM, {N} LOW`
