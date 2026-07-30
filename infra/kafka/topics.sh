#!/bin/bash
# Pre-create all Kafka topics used by the scheduling framework (ATOM-LOCAL-DEV-003).
# Idempotent: uses --if-not-exists so re-running is safe.
set -e
KAFKA=${BOOTSTRAP_SERVERS:-kafka:29092}
echo "Creating Kafka topics..."

kafka-topics --bootstrap-server "$KAFKA" --create --if-not-exists \
  --topic tenant.bookings.lifecycle --partitions 12 --replication-factor 1

kafka-topics --bootstrap-server "$KAFKA" --create --if-not-exists \
  --topic tenant.bookings.lifecycle.DLQ --partitions 3 --replication-factor 1

kafka-topics --bootstrap-server "$KAFKA" --create --if-not-exists \
  --topic tenant.notifications.outbound --partitions 6 --replication-factor 1

kafka-topics --bootstrap-server "$KAFKA" --create --if-not-exists \
  --topic tenant.audit.events --partitions 6 --replication-factor 1

echo "Topics created."
