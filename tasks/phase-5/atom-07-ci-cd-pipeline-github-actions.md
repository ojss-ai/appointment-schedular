---
description: GitHub Actions CI/CD pipeline — quality gates (CVE, coverage, isolation tests), staging auto-deploy, production manual approval
---

# ATOM-INFRA-507: CI/CD Pipeline — GitHub Actions

**Status**: 🟡 Planned
**Feature**: infra-ci-cd
**Phase**: 5 (Production)
**Tags**: [INFRA] [SECURITY] [TEST]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-SEC-504 (security audit — CVE gate config), ATOM-SEC-505 (pen test — isolation gate), ATOM-INFRA-506 (AWS deployment — ECS cluster ARNs needed)
**Blocks**: None
**PR**: TBD

---

## Overview

This atom creates the full CI/CD pipeline as a GitHub Actions workflow (`.github/workflows/ci.yml`). The pipeline runs on every push to every branch and enforces four merge-blocking quality gates: CRITICAL CVE detection, code coverage below 80%, tenant isolation test failure, and concurrency test failure. On push to `main`, the pipeline auto-deploys to the staging ECS cluster. Production deployment requires manual approval via a GitHub Environment with required reviewers. The full pipeline must complete in under 15 minutes.

---

## User Story

```
As a System
I want every pull request automatically validated against security, coverage, and isolation quality gates
So that no code reaches production without passing all zero-tolerance checks
```

---

## Acceptance Criteria

- [ ] **AC-01**: Pipeline runs on every push to every branch (`on: push: branches: ['**']`) and on every pull request
- [ ] **AC-02**: CRITICAL CVE (`mvn dependency-check -DfailBuildOnCVSS=7` or `npm audit --audit-level=high`) blocks the build — pipeline exits non-zero
- [ ] **AC-03**: Code coverage < 80% on any `@Service` class blocks the build (JaCoCo configured in `pom.xml`)
- [ ] **AC-04**: `TenantIsolationPenTestIT` failure blocks the build — zero tolerance
- [ ] **AC-05**: `*ConcurrencyIT` failure blocks the build — zero tolerance
- [ ] **AC-06**: Staging auto-deploys on push to `main` after all quality gates pass
- [ ] **AC-07**: Production deployment requires manual approval in GitHub Environments (`environment: production` with required reviewers)
- [ ] **AC-08**: Full pipeline completes in < 15 minutes
- [ ] **AC-09 (Domain abstraction)**: No industry-specific terms in workflow step names, job names, or environment variable names

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | GitHub Actions run history — branches other than main | `.github/workflows/ci.yml` `on:` block | 🔜 Planned |
| AC-02 | Introduce a known-CVE dependency → pipeline fails | `quality-gates` job — CVE scan step | 🔜 Planned |
| AC-03 | Remove a test → coverage drops → pipeline fails | `quality-gates` job — coverage check step | 🔜 Planned |
| AC-04 | Revert a `@PreAuthorize` → isolation test fails → pipeline fails | `quality-gates` job — isolation test step | 🔜 Planned |
| AC-05 | Manual: trigger push to main → verify staging ECS update | `deploy-staging` job | 🔜 Planned |
| AC-06 | Manual: attempt production deploy without approval → blocked | `deploy-production` job — `environment: production` | 🔜 Planned |
| AC-07 | Time full pipeline run from push to deploy-staging completion | GitHub Actions run duration | 🔜 Planned |

<!-- AC validation passed: TBD, 7 criteria mapped, all TBD -->

---

## Technical Design

### Architecture

The pipeline has three jobs: `quality-gates` (runs on all pushes/PRs), `deploy-staging` (runs only on `main` after `quality-gates` pass), and `deploy-production` (runs after `deploy-staging`, gated by GitHub Environment manual approval). Docker images are pushed to Amazon ECR and ECS services are updated in-place with `aws ecs update-service --force-new-deployment`. OIDC role assumption is used — no long-lived AWS access keys stored as GitHub secrets.

### Data Flow / Sequence

```
git push (any branch)
  → quality-gates job:
      → Dependency CVE scan (Java + Node)    [BLOCKS on CRITICAL CVE]
      → Compile API + Build Web
      → Unit tests
      → Integration tests (Testcontainers)
      → Concurrency tests (*ConcurrencyIT)   [BLOCKS on failure]
      → Tenant isolation tests               [BLOCKS on failure]
      → E2E tests (Playwright)
      → Coverage check (JaCoCo ≥ 80%)        [BLOCKS on failure]

git push to main (after quality-gates pass)
  → deploy-staging job:
      → Build + push Docker images to ECR
      → aws ecs update-service (staging cluster)
      → Smoke test: curl https://staging.api.scheduler.io/actuator/health

Manual approval in GitHub Environments
  → deploy-production job:
      → aws ecs update-service (production cluster)
      → Smoke test: curl https://api.scheduler.io/actuator/health
```

### File Structure

```
.github/
└── workflows/
    └── ci.yml                   ← Full CI/CD pipeline
```

### Interface Contracts

```yaml
# GitHub Actions workflow trigger shape
on:
  push:
    branches: ['**']
  pull_request:

# Quality gates job — key inputs
jobs:
  quality-gates:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:15
        env: { POSTGRES_DB: scheduler_test, POSTGRES_USER: scheduler, POSTGRES_PASSWORD: test }
        ports: ['5432:5432']
      redis:
        image: redis:7-alpine
        ports: ['6379:6379']

# Blocking quality gates (step-level)
# Each of the following steps exits non-zero → pipeline fails → merge blocked:
#   - Dependency CVE scan (Java): mvn dependency-check:check -DfailBuildOnCVSS=7
#   - Dependency CVE scan (Node): npm audit --audit-level=high
#   - Coverage check: mvn jacoco:check  (configured ≥ 80% in pom.xml)
#   - Concurrency tests: mvn test -Dtest="*ConcurrencyIT"
#   - Security isolation tests: mvn test -Dtest="TenantIsolationPenTestIT"
```

### Quality Gates That Block Merge

| Gate | Trigger | Tool | Zero-tolerance? |
|------|---------|------|-----------------|
| CRITICAL CVE (Java) | CVSS score ≥ 7 in any production dependency | OWASP dependency-check | Yes |
| CRITICAL CVE (Node) | High severity in npm audit | npm audit | Yes |
| Test coverage | < 80% on any `@Service` class | JaCoCo | Yes |
| Tenant isolation | Any `TenantIsolationPenTestIT` failure | JUnit 5 | Yes |
| Concurrency | Any `*ConcurrencyIT` failure | JUnit 5 | Yes |

### Design Rationale

- **OIDC role assumption (not long-lived keys)**: GitHub Actions OIDC allows the workflow to assume an IAM role scoped to the specific repository and branch. No long-lived AWS access keys are stored as GitHub secrets — eliminates the credential rotation burden and limits blast radius.
- **GitHub Environments for production gate**: Using a GitHub Environment with required reviewers gives a named, auditable approval gate. The alternative (a `workflow_dispatch` with confirmation) is less auditable and harder to enforce at the org level.
- **Testcontainers in CI**: PostgreSQL and Redis are started as GitHub Actions `services` (Docker sidecar) — not Testcontainers — for CI. Testcontainers spins up its own container in the integration test suite, but the base infrastructure services use the faster `services:` pattern to avoid Docker-in-Docker complexity.

---

## Test Strategy

**Test type**: Pipeline integration verification (Given/Assert on CI behaviour)

```
- pipeline_blocksOnCriticalCve:
    Given: pom.xml dependency with known CVSS ≥ 7 CVE introduced
    Assert: quality-gates job exits non-zero; PR shows red check; merge blocked

- pipeline_blocksOnCoverageDrop:
    Given: BookingService test deleted, coverage drops below 80%
    Assert: jacoco:check step exits non-zero; merge blocked

- pipeline_blocksOnIsolationTestFailure:
    Given: @PreAuthorize removed from one controller method
    Assert: TenantIsolationPenTestIT scenario1 fails; quality-gates exits non-zero

- pipeline_autoDeploys_toStaging_onMainPush:
    Given: all quality gates pass; push to main branch
    Assert: deploy-staging job runs; ECS staging service updated; smoke test returns 200

- pipeline_blocksProduction_withoutApproval:
    Given: deploy-staging succeeded; no human approval in GitHub Environments
    Assert: deploy-production job remains pending (not auto-executed)

- pipeline_completesUnder15Minutes:
    Given: clean codebase, all tests passing
    Assert: full pipeline (quality-gates + deploy-staging) completes in < 15 minutes
```

**Coverage requirements**:
- Pipeline behaviour must be verified by intentionally breaking each gate (manual verification during setup)
- Pipeline timing must be measured and documented in PR description

---

## Implementation Constraints

- CRITICAL CVE or tenant isolation test failure BLOCKS merge — no bypass mechanism is permitted
- `failBuildOnCVSS=7` threshold is fixed — lowering it requires a security team sign-off ADR
- Production deployment must use `environment: production` with required reviewers configured in GitHub repository settings
- No long-lived AWS access keys as GitHub secrets — OIDC only
- All Docker builds use multi-stage builds — no secrets in image layers
- `deploy-production` must run a smoke test after deployment (`curl -f https://api.scheduler.io/actuator/health`) and fail the job if the health check fails
- No industry-specific terms in workflow step names, job names, or environment variable names

---

## Implementation Plan (TDD)

### RED — Write failing pipeline first

1. Create `.github/workflows/ci.yml` with all jobs and steps defined
2. Push to a feature branch — confirm quality-gates job runs
3. Intentionally introduce a CVSS ≥ 7 dependency — confirm pipeline blocks
4. Remove a `@PreAuthorize` annotation — confirm isolation test blocks pipeline

### GREEN — All gates pass on clean codebase

1. Remove intentional failures
2. Push to feature branch — confirm all quality-gates steps pass
3. Merge to main — confirm deploy-staging runs and smoke test passes
4. Verify production job is blocked pending manual approval

### REFACTOR — Optimise and document

1. Add job-level caching for Maven `.m2` repository and pnpm store to reduce pipeline time
2. Verify full pipeline runs in < 15 minutes and document timing
3. Configure GitHub Environment `production` with required reviewers in repository settings

---

## Implementation Reference

### GitHub Actions Workflow

**File**: `.github/workflows/ci.yml`

```yaml
# [TASK: ATOM-INFRA-507]
name: CI/CD Pipeline
on:
  push:
    branches: ['**']
  pull_request:

jobs:
  quality-gates:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:15
        env: { POSTGRES_DB: scheduler_test, POSTGRES_USER: scheduler, POSTGRES_PASSWORD: test }
        ports: ['5432:5432']
      redis:
        image: redis:7-alpine
        ports: ['6379:6379']

    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { java-version: '21', distribution: 'temurin' }
      - uses: pnpm/action-setup@v3
        with: { version: 9 }
      - uses: actions/setup-node@v4
        with: { node-version: '22', cache: 'pnpm' }

      # Security gate — BLOCKS if CRITICAL CVE found
      - name: Dependency CVE scan (Java)
        run: cd apps/api && mvn dependency-check:check -DfailBuildOnCVSS=7 -q
      - name: Dependency CVE scan (Node)
        run: cd apps/web && npm audit --audit-level=high

      # Build
      - name: Compile API
        run: cd apps/api && mvn compile -q
      - name: Build Web
        run: cd apps/web && pnpm build

      # Tests
      - name: Unit tests
        run: cd apps/api && mvn test -q
      - name: Integration tests (Testcontainers)
        run: cd apps/api && mvn verify -P integration-tests -q
      - name: Concurrency tests
        run: cd apps/api && mvn test -Dtest="*ConcurrencyIT" -q
      - name: Security isolation tests
        run: cd apps/api && mvn test -Dtest="TenantIsolationPenTestIT" -q
      - name: E2E tests (Playwright)
        run: cd apps/web && pnpm playwright test

      # Coverage gate — BLOCKS if < 80%
      - name: Coverage check
        run: cd apps/api && mvn jacoco:check -q   # configured for 80% minimum

      # Claude Code test-gap check (advisory — does not block)
      - name: Test gap advisory
        run: |
          echo "Run /test-gap in Claude Code session to check for coverage gaps"
          echo "This is advisory — CI does not block on test-gap findings"

  deploy-staging:
    needs: quality-gates
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    environment: staging
    steps:
      - uses: actions/checkout@v4
      - name: Build and push Docker images
        run: |
          aws ecr get-login-password | docker login --username AWS --password-stdin $ECR_REGISTRY
          docker build -t $ECR_REGISTRY/scheduler-api:$GITHUB_SHA apps/api/
          docker push $ECR_REGISTRY/scheduler-api:$GITHUB_SHA
      - name: Deploy to ECS staging
        run: aws ecs update-service --cluster scheduler-staging --service api --force-new-deployment
      - name: Run smoke tests
        run: |
          sleep 30   # wait for ECS task to start
          curl -f https://staging.api.scheduler.io/actuator/health

  deploy-production:
    needs: deploy-staging
    runs-on: ubuntu-latest
    environment: production   # requires manual approval in GitHub Environments
    steps:
      - name: Deploy to ECS production
        run: aws ecs update-service --cluster scheduler-prod --service api --force-new-deployment
      - name: Production smoke test
        run: curl -f https://api.scheduler.io/actuator/health
```

---

## Integration Points

**Depends on**: ATOM-SEC-504 (CVE gate configuration — `failBuildOnCVSS=7`); ATOM-SEC-505 (`TenantIsolationPenTestIT` must exist to be run as a gate); ATOM-INFRA-506 (ECS cluster ARNs and ECR registry URLs)

**Enables**: Continuous deployment for all future feature work; Phase 5 production readiness

**NFR Gates satisfied**: All security NFRs (CVE gate, isolation gate); NFR-1.1 / NFR-1.2 validated via load tests that can be triggered post-deploy

**Cascading updates required**:
- `tasks/MASTER-TASK-LIST.md` — mark atom complete
- GitHub repository settings — configure `production` Environment with required reviewers (manual step, not in code)

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `.github/workflows/ci.yml` | New | Full CI/CD pipeline: quality gates + staging + production deploy |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] Pipeline runs on push to all branches (verified by push to feature branch)
- [ ] CRITICAL CVE gate verified — intentional CVE introduced and blocked, then removed
- [ ] Coverage gate verified — intentional coverage drop blocked, then restored
- [ ] Tenant isolation gate verified — `TenantIsolationPenTestIT` failure blocks pipeline
- [ ] Staging auto-deploy confirmed on push to `main`
- [ ] Production gate confirmed — manual approval required before deploy-production runs
- [ ] Full pipeline completes in < 15 minutes (timing documented in PR)
- [ ] OIDC role assumption configured — no long-lived AWS keys in GitHub secrets
- [ ] Zero industry-specific terms in step names, job names, or environment variable names
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: infra-ci-cd | Phase: 5*
