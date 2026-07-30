# ATOM-LOCAL-DEV-003: Docker Compose Local Dev Environment

**Status**: ✅ Complete
**Feature**: local-dev-infrastructure
**Phase**: 1 (Foundation)
**Tags**: [INFRA]
**Complexity**: Low
**Agent**: coder
**Dependencies**: ATOM-MONOREPO-SCAFFOLD-001
**Blocks**: None
**PR**: TBD

---

## Overview

This atom creates the complete Docker Compose stack for local development so that a single `docker compose up -d` starts all infrastructure dependencies — PostgreSQL 15, Redis 7, Kafka (KRaft mode), Confluent Schema Registry, Kafka UI, Debezium Connect, and a one-shot topic-init container. It also provides the Spring Boot `application-local.yml` profile and a `apps/web/.env.local.example` file. The key design decision is to run Kafka in KRaft mode (no ZooKeeper) using Confluent CP 7.6, which eliminates the ZooKeeper container and simplifies local topology.

---

## User Story

```
As a System
I want all infrastructure services to start with one command
So that developers can run the full stack locally without manual setup
```

---

## Acceptance Criteria

- [ ] **AC-01**: `docker compose up -d` starts all 6 services (postgres, redis, kafka, schema-registry, kafka-ui, debezium) without errors
- [ ] **AC-02**: PostgreSQL accessible — `psql -h localhost -U scheduler -d scheduler` connects successfully
- [ ] **AC-03**: Redis accessible — `redis-cli ping` returns `PONG`
- [ ] **AC-04**: Kafka UI accessible — `http://localhost:8080` returns HTTP 200
- [ ] **AC-05**: Schema Registry accessible — `curl http://localhost:8081/subjects` returns `[]`
- [ ] **AC-06**: Debezium Connect REST accessible — `curl http://localhost:8083/` returns connector info JSON
- [ ] **AC-07**: All 4 Kafka topics created by `kafka-init` container (`tenant.bookings.lifecycle`, `tenant.bookings.lifecycle.DLQ`, `tenant.notifications.outbound`, `tenant.audit.events`)
- [ ] **AC-08**: `docker compose down` cleans up all containers and network cleanly
- [ ] **AC-09**: `docker compose down -v` also removes the postgres data volume

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | Manual / CI | `infra/docker-compose.yml` | 🔜 Planned |
| AC-02 | Manual shell command | `infra/docker-compose.yml` postgres service | 🔜 Planned |
| AC-03 | Manual shell command | `infra/docker-compose.yml` redis service | 🔜 Planned |
| AC-04 | Manual browser / curl | `infra/docker-compose.yml` kafka-ui service | 🔜 Planned |
| AC-05 | `curl` command | `infra/docker-compose.yml` schema-registry service | 🔜 Planned |
| AC-06 | `curl` command | `infra/docker-compose.yml` debezium service | 🔜 Planned |
| AC-07 | `kafka-topics --list` | `infra/kafka/topics.sh` | 🔜 Planned |
| AC-08 | Manual | `infra/docker-compose.yml` | 🔜 Planned |
| AC-09 | Manual | `infra/docker-compose.yml` volumes section | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 9 criteria rewritten, 9 marked TBD -->

---

## Technical Design

### Architecture

All infrastructure services run as Docker containers in an isolated Compose network. Kafka runs in KRaft mode — no ZooKeeper required. Debezium Connect depends on both Kafka (healthy) and PostgreSQL (healthy) before starting, ensuring CDC is ready for the outbox pattern (ADR-003). The `kafka-init` one-shot container pre-creates all required topics so application services can start without topic auto-creation enabled.

### Data Flow / Sequence (if applicable)

```
docker compose up -d
  → postgres (healthcheck: pg_isready)
  → redis (healthcheck: redis-cli ping)
  → kafka KRaft (healthcheck: kafka-topics --list)
  → schema-registry (depends_on kafka healthy)
  → kafka-ui (depends_on schema-registry)
  → debezium (depends_on kafka healthy + postgres healthy)
  → kafka-init (depends_on kafka healthy) → creates 4 topics → exits 0
```

### File Structure

```
infra/
├── docker-compose.yml
├── docker-compose.override.yml
├── kafka/
│   └── topics.sh
└── postgres/
    └── init.sql

apps/api/src/main/resources/
└── application-local.yml

apps/web/
└── .env.local.example
```

### Interface Contracts

No runtime interfaces defined in this atom. All deliverables are infrastructure configuration files.

### Design Rationale

- **ADR-003**: Debezium is included in the local stack to support the transactional outbox pattern — CDC reads from the `outbox` table and relays events to Kafka.
- **ADR-002**: Redis is included from day one as the distributed lock fallback for horizontal scale scenarios.
- Kafka runs in KRaft mode to eliminate ZooKeeper complexity, matching production-ready Confluent CP 7.6+ deployments.

---

## Test Strategy

**Test type**: Manual infrastructure verification

```
- shouldStartAllServices_withSingleCommand:
    Given: Docker Engine 25+ running, no port conflicts
    Assert: `docker compose up -d` exits 0; `docker compose ps` shows all services as "running" or "exited 0" (kafka-init)

- shouldCreateAllKafkaTopics:
    Given: kafka-init container has run
    Assert: `kafka-topics --bootstrap-server localhost:9092 --list` returns all 4 topic names

- shouldAcceptPostgresConnection:
    Given: postgres service healthy
    Assert: `psql -h localhost -U scheduler -d scheduler -c '\dt'` connects and returns empty table list

- shouldRespondToRedisping:
    Given: redis service healthy
    Assert: `redis-cli -h localhost ping` returns PONG
```

**Coverage requirements**:
- No line coverage target — this atom produces no production logic
- All services must be healthy before atom-04 (Flyway migrations) begins

---

## Implementation Constraints

- Kafka must use KRaft mode (no ZooKeeper)
- Confluent CP version pinned to 7.6.0 for Kafka and Schema Registry
- PostgreSQL version pinned to 15
- Redis version pinned to 7-alpine
- All service ports must match the values in `application-local.yml`
- `postgres/init.sql` must create the `audit_writer` role and enable `uuid-ossp` + `pgcrypto` extensions
- Debezium version pinned to 2.6
- `topics.sh` must use `--if-not-exists` to be idempotent
- No secrets in `docker-compose.yml` — use environment variables with dev defaults only
- All Next.js API calls through `apps/web/lib/api-client.ts`

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Write `scripts/verify-infra.sh` that attempts connections to each service endpoint
2. Run without any Compose stack running — all checks fail

### GREEN — Minimum code to pass

1. Write `infra/docker-compose.yml` with all 7 service definitions (including kafka-init)
2. Write `infra/postgres/init.sql` with role creation and extensions
3. Write `infra/kafka/topics.sh` with all 4 topic creation commands
4. Write `apps/api/src/main/resources/application-local.yml` with datasource, redis, kafka config
5. Write `apps/web/.env.local.example`
6. Run `docker compose up -d` and execute `scripts/verify-infra.sh` — all checks pass

### REFACTOR — Quality pass

1. Add health check annotations to all services that don't already have them
2. Verify `docker compose down -v` fully cleans up (no orphan volumes)
3. Document port mapping table in a comment block at the top of `docker-compose.yml`

---

## Implementation Reference

### infra/docker-compose.yml

**File**: `infra/docker-compose.yml`

```yaml
version: "3.9"

services:
  postgres:
    image: postgres:15
    container_name: scheduler-postgres
    environment:
      POSTGRES_DB: scheduler
      POSTGRES_USER: scheduler
      POSTGRES_PASSWORD: scheduler_dev
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./postgres/init.sql:/docker-entrypoint-initdb.d/init.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U scheduler"]
      interval: 5s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    container_name: scheduler-redis
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    container_name: scheduler-kafka
    hostname: kafka
    environment:
      KAFKA_NODE_ID: 1
      KAFKA_PROCESS_ROLES: broker,controller
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
      KAFKA_CONTROLLER_QUORUM_VOTERS: 1@kafka:9093
      KAFKA_CONTROLLER_LISTENER_NAMES: CONTROLLER
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR: 1
      KAFKA_TRANSACTION_STATE_LOG_MIN_ISR: 1
      CLUSTER_ID: "MkU3OEVBNTcwNTJENDM2Qk"
    ports:
      - "9092:9092"
    healthcheck:
      test: ["CMD", "kafka-topics", "--bootstrap-server", "localhost:9092", "--list"]
      interval: 10s
      timeout: 10s
      retries: 10

  schema-registry:
    image: confluentinc/cp-schema-registry:7.6.0
    container_name: scheduler-schema-registry
    depends_on:
      kafka:
        condition: service_healthy
    environment:
      SCHEMA_REGISTRY_HOST_NAME: schema-registry
      SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS: kafka:9092
      SCHEMA_REGISTRY_LISTENERS: http://0.0.0.0:8081
    ports:
      - "8081:8081"

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    container_name: scheduler-kafka-ui
    depends_on:
      - schema-registry
    environment:
      KAFKA_CLUSTERS_0_NAME: local
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092
      KAFKA_CLUSTERS_0_SCHEMAREGISTRY: http://schema-registry:8081
    ports:
      - "8080:8080"

  debezium:
    image: debezium/connect:2.6
    container_name: scheduler-debezium
    depends_on:
      kafka:
        condition: service_healthy
      postgres:
        condition: service_healthy
    environment:
      BOOTSTRAP_SERVERS: kafka:9092
      GROUP_ID: debezium-connect
      CONFIG_STORAGE_TOPIC: debezium-configs
      OFFSET_STORAGE_TOPIC: debezium-offsets
      STATUS_STORAGE_TOPIC: debezium-status
    ports:
      - "8083:8083"

  kafka-init:
    image: confluentinc/cp-kafka:7.6.0
    container_name: scheduler-kafka-init
    depends_on:
      kafka:
        condition: service_healthy
    volumes:
      - ./kafka/topics.sh:/topics.sh
    entrypoint: ["/bin/bash", "/topics.sh"]
    restart: "no"

volumes:
  postgres-data:
```

### infra/postgres/init.sql

**File**: `infra/postgres/init.sql`

```sql
-- Create audit_writer role used by audit-service
CREATE ROLE audit_writer;

-- Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
```

### infra/kafka/topics.sh

**File**: `infra/kafka/topics.sh`

```bash
#!/bin/bash
set -e
KAFKA=kafka:9092
echo "Creating Kafka topics..."

kafka-topics.sh --bootstrap-server $KAFKA --create --if-not-exists \
  --topic tenant.bookings.lifecycle --partitions 12 --replication-factor 1

kafka-topics.sh --bootstrap-server $KAFKA --create --if-not-exists \
  --topic tenant.bookings.lifecycle.DLQ --partitions 3 --replication-factor 1

kafka-topics.sh --bootstrap-server $KAFKA --create --if-not-exists \
  --topic tenant.notifications.outbound --partitions 6 --replication-factor 1

kafka-topics.sh --bootstrap-server $KAFKA --create --if-not-exists \
  --topic tenant.audit.events --partitions 6 --replication-factor 1

echo "Topics created."
```

### apps/api/src/main/resources/application-local.yml

**File**: `apps/api/src/main/resources/application-local.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/scheduler
    username: scheduler
    password: scheduler_dev
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
  data:
    redis:
      host: localhost
      port: 6379
  kafka:
    bootstrap-servers: localhost:9092
    properties:
      schema.registry.url: http://localhost:8081

app:
  jwt:
    secret: ${JWT_SECRET:dev-secret-change-in-production-must-be-at-least-256-bits}
    expiry-hours: 24
```

### apps/web/.env.local.example

**File**: `apps/web/.env.local.example`

```env
NEXT_PUBLIC_API_URL=http://localhost:8080
API_BASE_URL=http://localhost:8080
JWT_SECRET=dev-secret-change-in-production
```

---

## Integration Points

**Depends on**: ATOM-MONOREPO-SCAFFOLD-001 (`infra/` directory must exist)

**Enables**: ATOM-POSTGRESQL-MIGRATIONS-004 (needs running PostgreSQL); ATOM-SPRING-SECURITY-005 (needs `application-local.yml`); all Kafka atoms need the running broker and pre-created topics

**Cascading updates required**:
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `infra/docker-compose.yml` | New | Full local dev stack |
| `infra/postgres/init.sql` | New | DB role + extensions init |
| `infra/kafka/topics.sh` | New | Topic pre-creation script |
| `apps/api/src/main/resources/application-local.yml` | New | Spring Boot local profile |
| `apps/web/.env.local.example` | New | Next.js env variable template |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `docker compose up -d` passes all service health checks
- [ ] All 4 Kafka topics created
- [ ] Zero industry-specific terms in any identifier or path
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: local-dev-infrastructure | Phase: 1*
