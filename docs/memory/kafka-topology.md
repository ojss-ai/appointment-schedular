# scheduling:kafka-topology
> Maintained by: coder agent
> Last updated: 2026-07-20

| Topic | Partitions | Key | Schema | Consumers |
|---|---|---|---|---|
| tenant.bookings.lifecycle | 12 | tenantId:bookingId | Avro (Phase 3) | notification-service, audit-service |
| tenant.bookings.lifecycle.DLQ | 3 | same | Avro | ops replay |
| tenant.notifications.outbound | 6 | tenantId:userId | Avro | notification-service |
| tenant.audit.events | 6 | tenantId:bookingId | Avro | audit-service |

Broker: Kafka KRaft (Confluent CP 7.6.0), Schema Registry :8081, Debezium Connect :8083.
Producers write via the transactional outbox only (ADR-003) — never directly.
