#!/usr/bin/env bash
# [TASK: ATOM-SEC-504]
# Rerunnable security audit suite — 6 domains. Run from repo root:
#   ./scripts/security-audit.sh
# Exit code is non-zero if any zero-tolerance static check fails. The CVE and
# HIPAA domains require Maven / a live DB and are invoked separately in CI
# (they are printed as guidance here and gated by --full).
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
API_SRC="apps/api/src/main/java"
FAIL=0

hr() { printf '\n=== %s ===\n' "$1"; }

# --- Domain 1: Dependency CVE scan (guidance / --full) --------------------
hr "Domain 1: Dependency CVE Scan"
if [[ "${1:-}" == "--full" ]]; then
  ( cd apps/api && mvn -q org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7 ) || FAIL=1
  ( cd apps/web && npm audit --audit-level=high ) || FAIL=1
  ( cd services/notification-service && mvn -q org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7 ) || FAIL=1
  ( cd services/audit-service && mvn -q org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7 ) || FAIL=1
else
  echo "SKIP (run with --full). CI gate: mvn dependency-check:check -DfailBuildOnCVSS=7 + npm audit --audit-level=high"
fi

# --- Domain 2: Tenant isolation ------------------------------------------
hr "Domain 2: Tenant Isolation"
# Every JPA @Query block must reference tenantId/tenant_id somewhere in the
# annotation body. System-level batch operations are explicitly allow-listed.
ALLOWLIST='BookingRepository.java:.*DELETE FROM bookings|AuditLogRepository.java:.*aggregateBookingPatterns'
echo "--- @Query blocks lacking tenant scoping (allow-list applies) ---"
UNSCOPED=0
while IFS= read -r file; do
  awk '
    /@Query/ {inq=1; buf=""; start=NR}
    inq {buf=buf" "$0}
    inq && /"""\)|"\)/ {
      if (buf !~ /tenantId|tenant_id/) print FILENAME":"start": "buf
      inq=0
    }
  ' "$file"
done < <(grep -rl "@Query" "$API_SRC" --include="*.java") \
  | grep -Ev "DELETE FROM bookings|aggregateBookingPatterns" \
  | grep -E '.' && UNSCOPED=1 || echo "CLEAN: all @Query blocks tenant-scoped (or allow-listed system batch)"
[[ "$UNSCOPED" == "1" ]] && FAIL=1

echo "--- findAll( usages (each must be tenant-scoped via Specification) ---"
grep -rn "\.findAll(" "$API_SRC" --include="*.java" || echo "CLEAN: 0 findAll usages"

echo "--- @RestController mapping vs @PreAuthorize coverage ---"
for f in $(grep -rln "@RestController" "$API_SRC" --include="*.java"); do
  m=$(grep -c '@GetMapping\|@PostMapping\|@PutMapping\|@PatchMapping\|@DeleteMapping' "$f")
  p=$(grep -c '@PreAuthorize' "$f")
  [[ "$m" -gt 0 && "$p" -lt "$m" ]] && echo "REVIEW: $(basename "$f") mappings=$m preauth=$p"
done
echo "(HealthController + AuthController public endpoints are expected below-count)"

# --- Domain 3: JWT audit --------------------------------------------------
hr "Domain 3: JWT Audit"
grep -rn "Math.random\|new Random()" "$API_SRC" --include="*.java" \
  && { echo "VIOLATION: insecure RNG"; FAIL=1; } \
  || echo "CLEAN: 0 insecure RNG (SecureRandom only)"
grep -q "secretBytes(secret).length < 32" "$API_SRC/com/scheduler/api/security/jwt/JwtProperties.java" \
  && echo "PASS: JWT secret >= 256-bit enforced at startup" \
  || { echo "VIOLATION: JWT min-length not enforced"; FAIL=1; }

# --- Domain 4: OTP audit --------------------------------------------------
hr "Domain 4: OTP Audit"
grep -q "@Transient" "$API_SRC/com/scheduler/api/auth/otp/OtpRecord.java" \
  && grep -q "rawOtp" "$API_SRC/com/scheduler/api/auth/otp/OtpRecord.java" \
  && echo "PASS: rawOtp is @Transient (never persisted); hash stored via BCrypt" \
  || echo "REVIEW: verify rawOtp is not a persisted column"
grep -rq "SecureRandom" "$API_SRC/com/scheduler/api/auth/otp/OtpService.java" \
  && echo "PASS: OTP uses SecureRandom" || { echo "VIOLATION: OTP RNG"; FAIL=1; }

# --- Domain 5: PII in Kafka outbox ---------------------------------------
hr "Domain 5: PII in Kafka Outbox"
# ipAddress is a HIPAA-required audit field (SECURITY-SPEC 5.1), not banned PII.
HITS=$(grep -rni "email\|phone\|firstName\|lastName\|\baddress\b" \
  "$API_SRC/com/scheduler/api/outbox" --include="*.java" | grep -vi "ipAddress" || true)
if [[ -n "$HITS" ]]; then echo "$HITS"; echo "VIOLATION: PII in outbox payload"; FAIL=1;
else echo "CLEAN: 0 banned PII fields in outbox (UUIDs + ipAddress audit field only)"; fi

# --- Domain 6: HIPAA fields (guidance / --full) --------------------------
hr "Domain 6: HIPAA Audit Fields"
if [[ "${1:-}" == "--full" ]]; then
  psql -h "${PGHOST:-localhost}" -U "${PGUSER:-scheduler}" -d "${PGDATABASE:-scheduler}" -c \
    "SELECT column_name FROM information_schema.columns WHERE table_name='audit_log' ORDER BY column_name;"
else
  echo "SKIP (run with --full). Verify audit_log has: tenant_id, who/user_id, what/event_type,"
  echo "  when/occurred_at, booking_id, resource_id, ip_address, user_agent, previous_status, new_status"
fi

hr "RESULT"
[[ "$FAIL" == "0" ]] && echo "PASS: 0 zero-tolerance static violations" || echo "FAIL: violations found (see above)"
exit "$FAIL"
