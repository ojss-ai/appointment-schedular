#!/bin/bash
# TASK: ATOM-KAFKA-005 — Register Avro schemas with the Schema Registry.
# Compatibility: BACKWARD for lifecycle/notification subjects, FULL for the
# audit subject (docs/KAFKA-SPEC.md section 3 — never remove audit fields).
# Idempotent: POST returns the existing ID when the fingerprint matches.
# Requires jq (or python3 as fallback) to JSON-escape the .avsc files.
# NFR-2.2: the registry validates every payload before publish.
set -e
REGISTRY_URL=${SCHEMA_REGISTRY_URL:-http://localhost:8081}
SCHEMA_DIR="$(dirname "$0")/schemas"

json_escape_file() {
  if command -v jq > /dev/null 2>&1; then
    jq -Rs '{"schema": .}' < "$1"
  else
    python3 -c 'import json,sys; print(json.dumps({"schema": sys.stdin.read()}))' < "$1"
  fi
}

echo "Waiting for Schema Registry at $REGISTRY_URL ..."
until curl -sf "$REGISTRY_URL/subjects" > /dev/null; do sleep 2; done

register_schema() {
  local subject=$1 schema_file=$2 compatibility=$3
  echo "Setting $subject compatibility=$compatibility ..."
  curl -sf -X PUT "$REGISTRY_URL/config/$subject" \
    -H "Content-Type: application/vnd.schemaregistry.v1+json" \
    -d "{\"compatibility\":\"$compatibility\"}" > /dev/null
  echo "Registering $subject ..."
  json_escape_file "$SCHEMA_DIR/$schema_file" \
    | curl -sf -X POST "$REGISTRY_URL/subjects/$subject/versions" \
        -H "Content-Type: application/vnd.schemaregistry.v1+json" \
        --data-binary @- > /dev/null
  echo "OK: $subject"
}

register_schema "tenant.bookings.lifecycle-value"     "booking-lifecycle-event.avsc" "BACKWARD"
register_schema "tenant.notifications.outbound-value" "notification-command.avsc"    "BACKWARD"
register_schema "tenant.audit.events-value"           "audit-event.avsc"             "FULL"

echo "All schemas registered."
