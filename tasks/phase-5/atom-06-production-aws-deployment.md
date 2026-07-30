---
description: Production AWS infrastructure — ECS Fargate, RDS, ElastiCache, MSK, CDK stacks, Secrets Manager
---

# ATOM-INFRA-506: Production Infrastructure — AWS Deployment

**Status**: ✅ Complete
**Feature**: infra-aws-deployment
**Phase**: 5 (Production)
**Tags**: [INFRA] [SECURITY]
**Complexity**: High
**Agent**: coder
**Dependencies**: All Phase 1–4 atoms complete; ATOM-SEC-504 (security audit passed)
**Blocks**: ATOM-INFRA-507 (CI/CD pipeline needs ECS cluster ARNs and ECR registry URLs), ATOM-INFRA-508 (observability dashboard)
**PR**: TBD

---

## Overview

This atom provisions the full production AWS infrastructure using AWS CDK (TypeScript) across six stacks: VPC, RDS PostgreSQL, ElastiCache Redis, Amazon MSK (Kafka), ECS Fargate (API + notification + audit + Debezium services), and AWS Secrets Manager. All credentials are stored in Secrets Manager and injected into ECS task definitions via `valueFrom` — never as environment literal values, never in source code, never in Docker images. CDK plan must receive human approval before `cdk deploy` is executed.

---

## User Story

```
As a Tenant Admin
I want the scheduling platform deployed to production AWS infrastructure
So that the system is available with high availability, automated backups, and zero hard-coded secrets
```

---

## Acceptance Criteria

- [ ] **AC-01**: All services deployed and healthy — `GET https://api.scheduler.io/actuator/health` returns `{"status":"UP"}`
- [ ] **AC-02**: Next.js booking flow works end-to-end in production (Vercel or AWS Amplify)
- [ ] **AC-03**: Kafka producing and consuming events in Amazon MSK — consumer lag confirmed at 0 after initial deployment
- [ ] **AC-04**: RDS automated daily backups configured and first backup verified in AWS console
- [ ] **AC-05**: Zero secrets in source code, Docker images, or ECS task environment literals — all secrets via `valueFrom` referencing Secrets Manager ARNs
- [ ] **AC-06**: CDK diff/plan reviewed and approved by a human before `cdk deploy --require-approval broadening`
- [ ] **AC-07**: All ECS services show `RUNNING` state in target count — no stopped tasks
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms in CDK resource names, ECS service names, or infrastructure tags

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `curl -f https://api.scheduler.io/actuator/health` | ECS Fargate API task | 🔜 Planned |
| AC-02 | Manual E2E smoke test in browser | Vercel / Amplify deployment | 🔜 Planned |
| AC-03 | `kafka-consumer-groups.sh --describe` on MSK | MSK cluster | 🔜 Planned |
| AC-04 | AWS console: RDS → Maintenance & backups | RDS instance config | 🔜 Planned |
| AC-05 | `grep -rn "password\|secret\|token" infra/aws/cdk/` — must return 0 literal values | CDK stacks | 🔜 Planned |
| AC-06 | `cdk diff` output reviewed + human approval | CDK CLI | 🔜 Planned |
| AC-07 | AWS ECS console: all services show running count = desired count | ECS cluster | 🔜 Planned |

<!-- AC validation passed: TBD, 7 criteria mapped, all TBD -->

---

## Technical Design

### Architecture

Six CDK stacks compose the infrastructure. VpcStack provisions the shared VPC with public, private, and isolated subnets. RdsStack places PostgreSQL 15 Multi-AZ in isolated subnets. ElastiCacheStack places Redis 7 in isolated subnets. MskStack provisions Amazon MSK (two brokers, kafka.t3.small, 3-day retention). EcsStack deploys all four Fargate services (API, notification-service, audit-service, Debezium) in private subnets behind an Application Load Balancer with HTTPS. SecretsStack creates all Secrets Manager entries.

### Data Flow / Sequence

```
CDK deploy order (dependency graph):
  VpcStack (no deps)
  → SecretsStack (no deps)
  → RdsStack (depends on VpcStack, SecretsStack)
  → ElastiCacheStack (depends on VpcStack)
  → MskStack (depends on VpcStack)
  → EcsStack (depends on all above)

ECS API task startup:
  → Pull secrets from Secrets Manager (valueFrom)
  → Spring Boot: SPRING_DATASOURCE_URL, JWT_SECRET, etc. populated from env
  → Flyway migrations run on startup
  → /actuator/health → UP
```

### File Structure

```
infra/
└── aws/
    ├── cdk/
    │   ├── bin/
    │   │   └── app.ts                   ← CDK app entry point
    │   ├── lib/
    │   │   ├── vpc-stack.ts             ← VPC, subnets, NAT gateway
    │   │   ├── rds-stack.ts             ← RDS PostgreSQL 15 Multi-AZ
    │   │   ├── elasticache-stack.ts     ← ElastiCache Redis 7
    │   │   ├── msk-stack.ts             ← Amazon MSK 2 brokers
    │   │   ├── ecs-stack.ts             ← ECS Fargate: API, services, Debezium
    │   │   └── secrets-stack.ts         ← Secrets Manager entries
    │   ├── package.json
    │   └── tsconfig.json
    └── README.md                        ← Deployment runbook
```

### Interface Contracts

```typescript
// CDK stack props shapes (types only — no implementation bodies)

interface VpcStackProps extends cdk.StackProps {
  readonly maxAzs: number;
  readonly natGateways: number;
}

interface RdsStackProps extends cdk.StackProps {
  readonly vpc: ec2.IVpc;
  readonly dbPasswordSecret: secretsmanager.ISecret;
  readonly instanceType: ec2.InstanceType;   // db.t3.medium
  readonly multiAz: boolean;                 // true for production
  readonly backupRetentionDays: number;      // 7
}

interface ElastiCacheStackProps extends cdk.StackProps {
  readonly vpc: ec2.IVpc;
  readonly nodeType: string;                 // cache.t3.micro
}

interface MskStackProps extends cdk.StackProps {
  readonly vpc: ec2.IVpc;
  readonly brokerNodeCount: number;          // 2
  readonly kafkaVersion: string;             // 3.5.1
  readonly retentionHours: number;           // 72 (3 days)
}

interface EcsStackProps extends cdk.StackProps {
  readonly vpc: ec2.IVpc;
  readonly cluster: ecs.Cluster;
  readonly secrets: Record<string, secretsmanager.ISecret>;
  readonly rdsEndpoint: string;
  readonly redisEndpoint: string;
  readonly mskBrokers: string;
}
```

### Design Rationale

- **Secrets Manager `valueFrom` (not `value`)**: ECS task definition `environment` literals appear in plaintext in CloudFormation templates, which are stored in S3. `valueFrom` references the Secrets Manager ARN — the secret value is injected at task startup and never materialises in any stored configuration.
- **Multi-AZ RDS**: Booking data is the system's primary asset. Multi-AZ provides automatic failover with < 60s downtime, satisfying the production availability requirement without manual intervention.
- **MSK (not self-hosted Kafka)**: Amazon MSK manages broker upgrades, disk scaling, and replication. The operational overhead of self-hosted Kafka on EC2 is not justified at Phase 5 scale.
- **ECS Fargate (not EC2)**: No EC2 instance management, automatic bin-packing, and per-task IAM roles are sufficient for the initial production footprint. EC2 launch type is the upgrade path if reserved instance pricing becomes significant.

---

## Test Strategy

**Test type**: Infrastructure smoke tests (curl/AWS CLI) + CDK synthesis validation

```
- cdkSynth_producesNoErrors:
    Given: valid CDK app configuration
    Assert: cdk synth exits 0; no CloudFormation validation errors

- apiHealthCheck_returnsUp:
    Given: all ECS services deployed and running
    Assert: curl -f https://api.scheduler.io/actuator/health returns {"status":"UP"}

- noSecretsInSource_literalScan:
    Given: full CDK source tree
    Assert: grep for password/secret/token literal values returns 0 results in lib/ directory

- mskConsumerLag_isZero_afterInitialDeploy:
    Given: Debezium and consumer services started
    Assert: kafka-consumer-groups.sh --describe shows lag = 0 for all consumer groups

- rdsBackup_configuredAndVerified:
    Given: RdsStack deployed with backupRetentionDays = 7
    Assert: AWS CLI describe-db-instances shows BackupRetentionPeriod = 7
```

**Coverage requirements**:
- CDK synthesis (`cdk synth`) must pass as part of CI
- Smoke tests run in `deploy-staging` GitHub Actions job before production promotion

---

## Implementation Constraints

- All secrets must be stored in AWS Secrets Manager and referenced via `valueFrom` in ECS task definitions — never as environment literal values
- CDK `cdk deploy` must use `--require-approval broadening` — human must approve any IAM permission changes
- No secrets in Docker images — use multi-stage builds; verify with `docker history --no-trunc {image}`
- ECS task definitions must not contain any `value:` entries for sensitive configuration — only `valueFrom:`
- All CDK resource names and ECS service names must use generic terms (`scheduler-api`, `scheduler-notification`) — no industry-specific names
- GitHub Actions deployment steps use OIDC role assumption — no long-lived AWS access keys stored as GitHub secrets

---

## Implementation Plan (TDD)

### RED — Setup and synthesis validation

1. Scaffold `infra/aws/cdk/` with `cdk init app --language typescript`
2. Write all six stack files with placeholder props
3. Run `cdk synth` — expect synthesis errors for missing required props
4. Write CDK unit tests (`aws-cdk-lib/assertions`) for key resource properties

### GREEN — Full infrastructure definition

1. Implement `VpcStack` — VPC, 2 AZs, 1 NAT gateway
2. Implement `SecretsStack` — create all Secrets Manager entries (empty placeholders)
3. Implement `RdsStack` — PostgreSQL 15, Multi-AZ, `db.t3.medium`, 7-day backup retention
4. Implement `ElastiCacheStack` — Redis 7, single node
5. Implement `MskStack` — 2 brokers, `kafka.t3.small`, 3-day retention
6. Implement `EcsStack` — all 4 Fargate services with `valueFrom` secret injection
7. Run `cdk synth` — exits 0

### REFACTOR — Deployment runbook

1. Populate `infra/aws/README.md` with full deployment runbook: prerequisites → `cdk diff` → human approval → `cdk deploy`
2. Add Secrets Manager population commands to runbook (the `aws secretsmanager create-secret` commands)
3. Human reviews `cdk diff` output and approves
4. Execute `cdk deploy --all` against production AWS account

---

## Implementation Reference

### Secrets Manager Population (run once before first deploy)

**File**: `infra/aws/README.md` (setup section)

```bash
# [TASK: ATOM-INFRA-506]
# Run once — populate secrets before cdk deploy
aws secretsmanager create-secret \
  --name scheduler/prod/jwt-secret \
  --secret-string "$(openssl rand -base64 32)"

aws secretsmanager create-secret \
  --name scheduler/prod/db-password \
  --secret-string "$(openssl rand -base64 24)"

aws secretsmanager create-secret \
  --name scheduler/prod/twilio-auth-token \
  --secret-string "..."

aws secretsmanager create-secret \
  --name scheduler/prod/anthropic-api-key \
  --secret-string "..."
```

### Infrastructure Target

| Service | AWS Product | Configuration |
|---------|-------------|---------------|
| Spring Boot API | ECS Fargate | 2 tasks, 1 vCPU / 2 GB, ALB with HTTPS |
| Next.js | Vercel or AWS Amplify | CDN, auto-deploy from `main` |
| PostgreSQL | RDS PostgreSQL 15 | Multi-AZ, `db.t3.medium`, 7-day backups |
| Redis | ElastiCache Redis 7 | Single node (`cache.t3.micro`) |
| Kafka | Amazon MSK | `kafka.t3.small`, 2 brokers, 3-day retention |
| Debezium | ECS Fargate | 1 task, 0.5 vCPU / 1 GB |
| notification-service | ECS Fargate | 2 tasks |
| audit-service | ECS Fargate | 2 tasks |
| Secrets | AWS Secrets Manager | All credentials via `valueFrom` |

---

## Integration Points

**Depends on**: All Phase 1–4 atoms complete (all application code must exist and be containerisable); ATOM-SEC-504 (security audit passed — no CRITICAL CVEs in Docker images)

**Enables**: ATOM-INFRA-507 (CI/CD pipeline — needs ECS cluster ARNs and ECR registry URLs); ATOM-INFRA-508 (observability dashboard — needs running services to scrape)

**NFR Gates satisfied**: NFR-1.1 / NFR-1.2 (production environment ready for load test validation); production availability gate

**Cascading updates required**:
- `infra/aws/README.md` — deployment runbook (new file)
- `.github/workflows/ci.yml` — add ECR registry URL and ECS cluster name as GitHub Actions environment variables (ATOM-INFRA-507)
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `infra/aws/cdk/bin/app.ts` | New | CDK app entry point |
| `infra/aws/cdk/lib/vpc-stack.ts` | New | VPC, subnets, NAT gateway |
| `infra/aws/cdk/lib/rds-stack.ts` | New | RDS PostgreSQL 15 Multi-AZ |
| `infra/aws/cdk/lib/elasticache-stack.ts` | New | ElastiCache Redis 7 |
| `infra/aws/cdk/lib/msk-stack.ts` | New | Amazon MSK 2-broker Kafka |
| `infra/aws/cdk/lib/ecs-stack.ts` | New | ECS Fargate: API + services + Debezium |
| `infra/aws/cdk/lib/secrets-stack.ts` | New | Secrets Manager entries |
| `infra/aws/cdk/package.json` | New | CDK TypeScript project config |
| `infra/aws/README.md` | New | Deployment runbook |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `cdk synth` exits 0 (no CloudFormation validation errors)
- [ ] `cdk diff` reviewed and approved by human before deploy
- [ ] Zero secrets in CDK source — grep confirms no literal password/secret/token values in `lib/`
- [ ] All ECS secrets use `valueFrom` (Secrets Manager ARN) — no `value:` for sensitive config
- [ ] `GET https://api.scheduler.io/actuator/health` returns `{"status":"UP"}` post-deploy
- [ ] RDS backups configured and first backup verified
- [ ] MSK consumer lag = 0 after initial startup
- [ ] Zero industry-specific terms in CDK resource names or ECS service names
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: infra-aws-deployment | Phase: 5*
