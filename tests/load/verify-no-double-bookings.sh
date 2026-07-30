#!/usr/bin/env bash
# [TASK: ATOM-PERF-502]
# Post-load-test correctness gate: assert no (resource_id, slot_start) pair has
# more than one CONFIRMED booking. Exits non-zero if any double-booking is
# found — wire this into CI immediately after the k6 run (k6 exit 0 alone is
# NOT sufficient proof of ADR-002 correctness).
#
# Env overrides: PGHOST PGPORT PGUSER PGDATABASE PGPASSWORD
set -euo pipefail

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-scheduler}"
PGDATABASE="${PGDATABASE:-scheduler}"

echo "== Checking for double-bookings in ${PGDATABASE} =="

ROWS=$(psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" -t -A <<'SQL'
SELECT COUNT(*) FROM (
  SELECT resource_id, slot_start, COUNT(*) AS c
  FROM bookings
  WHERE status = 'CONFIRMED'
    AND created_at > NOW() - INTERVAL '15 minutes'
  GROUP BY resource_id, slot_start
  HAVING COUNT(*) > 1
) dupes;
SQL
)

ROWS="${ROWS//[[:space:]]/}"

if [[ "$ROWS" != "0" ]]; then
  echo "FAIL: ${ROWS} slot(s) hold more than one CONFIRMED booking — ADR-002 violated."
  psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$PGDATABASE" <<'SQL'
SELECT resource_id, slot_start, COUNT(*) AS count
FROM bookings
WHERE status = 'CONFIRMED'
  AND created_at > NOW() - INTERVAL '15 minutes'
GROUP BY resource_id, slot_start
HAVING COUNT(*) > 1;
SQL
  exit 1
fi

echo "PASS: 0 double-bookings (NFR-1.1 correctness invariant holds)."
