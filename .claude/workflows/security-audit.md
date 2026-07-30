# Workflow: security-audit

1. Scope — security agent lists changed files since last audit.
2. Scan — /security-scan: CVE check (dependency-check, npm audit), secret grep, SQLi surface.
3. Tenant isolation — grep every repository for queries missing tenant_id; verify @PreAuthorize on all controllers.
4. JWT — confirm secret sourcing from env, HS256, all 9 claims, 401 (not 500) on any parse failure.
5. Report — findings to docs/SECURITY-FINDINGS.md; CRITICAL blocks merge (24h SLA), HIGH 7 days.
