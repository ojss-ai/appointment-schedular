#!/bin/sh
# TASK: ATOM-KAFKA-003 — Register the Debezium outbox connector (ADR-003).
# Idempotent: PUT /connectors/{name}/config upserts, so re-running is safe.
# POSIX sh + curl only (no jq/python) so it can run inside minimal containers:
# debezium-outbox-connector.json holds the flat "config" map, the connector
# name lives here.
set -e
CONNECT_URL=${CONNECT_URL:-http://localhost:8083}
CONNECTOR_NAME=${CONNECTOR_NAME:-scheduler-outbox-connector}
CONFIG_FILE=${CONFIG_FILE:-$(dirname "$0")/debezium-outbox-connector.json}

echo "Waiting for Kafka Connect at $CONNECT_URL ..."
until curl -sf "$CONNECT_URL/" > /dev/null; do sleep 2; done

echo "Registering connector $CONNECTOR_NAME (idempotent PUT)..."
curl -sf -X PUT "$CONNECT_URL/connectors/$CONNECTOR_NAME/config" \
  -H "Content-Type: application/json" \
  --data-binary @"$CONFIG_FILE" > /dev/null

echo "Verifying connector status..."
sleep 3
STATUS=$(curl -sf "$CONNECT_URL/connectors/$CONNECTOR_NAME/status" \
  | sed -n 's/.*"connector":{"state":"\([A-Z]*\)".*/\1/p')
echo "Connector state: $STATUS"
if [ "$STATUS" != "RUNNING" ]; then
  echo "ERROR: connector $CONNECTOR_NAME is not RUNNING" >&2
  exit 1
fi
echo "Outbox connector registered and RUNNING."
