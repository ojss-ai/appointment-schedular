# ATOM-MONOREPO-SCAFFOLD-001: Monorepo Scaffold and Toolchain Setup

**Status**: ✅ Complete
**Feature**: monorepo-scaffold
**Phase**: 1 (Foundation)
**Tags**: [INFRA]
**Complexity**: Low
**Agent**: coder
**Dependencies**: None
**Blocks**: None
**PR**: TBD

---

## Overview

This atom establishes the top-level monorepo structure for the Multi-Tenant Scheduling Framework using pnpm workspaces at the root, with independent Spring Boot (Java 21) and Next.js 15 (TypeScript) applications as workspace members. It bootstraps the Maven project in `apps/api`, the Next.js project in `apps/web`, the service stubs in `services/`, and the infrastructure config in `infra/`. The key design decision is that pnpm workspaces govern all JavaScript/TypeScript dependencies while Maven governs the Java API independently — no single polyglot build tool is forced across both ecosystems.

---

## User Story

```
As a Tenant Admin
I want a fully scaffolded monorepo with working dev commands
So that the team can begin implementing features without project-setup overhead
```

---

## Acceptance Criteria

- [ ] **AC-01**: `pnpm install` succeeds at repo root with zero errors
- [ ] **AC-02**: `cd apps/api && mvn compile` compiles without errors
- [ ] **AC-03**: `cd apps/web && pnpm dev` starts Next.js dev server on port 3000
- [ ] **AC-04**: All folders in the target directory tree exist (even if empty placeholder)
- [ ] **AC-05**: `.gitignore` correctly excludes `node_modules/`, `target/`, `.env.local`, `*.class`
- [ ] **AC-06**: `CLAUDE.md` and `AGENTS.md` exist at repo root
- [ ] **AC-07**: `.claude/` directory with `agents/`, `commands/`, and `settings.json` exists
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms (`doctor`, `patient`, `vehicle`, etc.) in any identifier, file name, or path

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | Manual / CI script | `package.json` | 🔜 Planned |
| AC-02 | Manual / CI script | `apps/api/pom.xml` | 🔜 Planned |
| AC-03 | Manual | `apps/web/` | 🔜 Planned |
| AC-04 | Manual / shell script | Repo root | 🔜 Planned |
| AC-05 | Manual | `.gitignore` | 🔜 Planned |
| AC-06 | Manual | Repo root | 🔜 Planned |
| AC-07 | Manual | `.claude/` | 🔜 Planned |
| AC-08 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 8 criteria rewritten, 8 marked TBD -->

---

## Technical Design

### Architecture

This atom is purely scaffolding — no runtime logic. The repository root hosts a pnpm workspace manifest that resolves dependencies for `apps/web` and any Node-based services. `apps/api` is a standalone Maven project that does not participate in pnpm resolution. The `.claude/` directory and its sub-directories configure Claude Code's Agent SDK for all subsequent development work in the monorepo.

### File Structure

```
appointment/
├── apps/
│   ├── web/                   ← Next.js 15 (TypeScript, pnpm)
│   └── api/                   ← Spring Boot 3.x (Java 21, Maven)
├── services/
│   ├── notification-service/  ← Spring Boot 3.x (Java 21, Maven)
│   └── audit-service/         ← Spring Boot 3.x (Java 21, Maven)
├── infra/
│   ├── docker-compose.yml
│   ├── docker-compose.override.yml
│   ├── kafka/
│   │   └── topics.sh
│   └── postgres/
│       └── init.sql
├── CLAUDE.md
├── AGENTS.md
├── .claude/
│   ├── agents/
│   ├── commands/
│   └── settings.json
└── package.json               ← root pnpm workspace
```

### Interface Contracts

No runtime interfaces defined in this atom. This atom produces configuration files and directory structure only.

### Design Rationale

- **ADR-004**: Row-level multi-tenancy is enforced via `tenant_id` — this scaffold creates the package structure that subsequent atoms will populate with that enforcement.
- **ADR-005**: The generic domain model (Resource/Service) shapes the package naming convention established here; no domain-specific module names appear in the scaffold.
- The separation of pnpm (JS) and Maven (Java) build tools avoids polyglot build complexity while keeping both ecosystems in one repo.

---

## Test Strategy

**Test type**: Manual verification / CI shell script

```
- shouldInstallDependencies_atRepoRoot:
    Given: clean checkout with Node 22 and pnpm 9 installed
    Assert: `pnpm install` exits 0 with no errors

- shouldCompileApiProject:
    Given: Java 21 and Maven 3.9+ installed
    Assert: `cd apps/api && mvn compile` exits 0

- shouldStartWebDevServer:
    Given: `pnpm install` already completed
    Assert: `cd apps/web && pnpm dev` starts server, port 3000 responds with HTTP 200

- shouldExcludeSensitiveFiles_fromGit:
    Given: `.gitignore` in place
    Assert: `git check-ignore node_modules .env.local target` returns all three as ignored
```

**Coverage requirements**:
- No line coverage target — this atom produces no production logic
- CI must run scaffold verification before any subsequent atom's tests

---

## Implementation Constraints

- pnpm version must be pinned to 9+ in `packageManager` field of root `package.json`
- Node.js version must be 22 LTS
- Java version must be 21 (Eclipse Temurin / OpenJDK)
- Maven version must be 3.9+
- Docker Engine must be 25+, Compose v2
- `.env.local` must never be committed — confirmed by `.gitignore` and `.claude/settings.json` pre-commit hook
- No `console.log` in Next.js; no `System.out.println` in Java — use pino / SLF4J
- All Next.js API calls go through `apps/web/lib/api-client.ts`

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Write a CI shell script `scripts/verify-scaffold.sh` that checks for each required directory and file
2. Run the script against an empty directory — assert all checks fail
3. Document expected tool versions in the script header

### GREEN — Minimum code to pass

1. Create root `package.json` with pnpm workspace config
2. Run `pnpm create next-app@latest apps/web` with the required flags
3. Generate `apps/api` via Spring Initializr with required dependencies
4. Create stub `services/notification-service/` and `services/audit-service/` directories
5. Create `infra/` with placeholder `docker-compose.yml`, `kafka/topics.sh`, `postgres/init.sql`
6. Create `.gitignore` at repo root
7. Create `.claude/` structure (agents/, commands/, settings.json)
8. Run `scripts/verify-scaffold.sh` — all checks should pass

### REFACTOR — Quality pass

1. Add `README.md` section on toolchain version requirements (per table below)
2. Verify no cross-directory symlinks or unexpected files were created
3. Run `/security-scan` to confirm no secrets in committed files

---

## Implementation Reference

### Root package.json

**File**: `package.json`

```json
{
  "name": "appointment-monorepo",
  "private": true,
  "packageManager": "pnpm@9.0.0",
  "workspaces": ["apps/*", "services/*"],
  "scripts": {
    "dev": "pnpm --filter web dev",
    "build": "pnpm --filter web build",
    "test": "pnpm --filter web test"
  }
}
```

### apps/web — Next.js 15 scaffold

**File**: `apps/web/` (generated)

```bash
cd apps/web
pnpm create next-app@latest . \
  --typescript \
  --tailwind \
  --app \
  --src-dir \
  --import-alias "@/*"
```

Additional dependencies:
```bash
pnpm add @tanstack/react-query react-hook-form zod @hookform/resolvers
pnpm add @rjsf/core @rjsf/utils @rjsf/validator-ajv8
pnpm add react-select
```

### apps/api — Spring Boot 3.x scaffold

**File**: `apps/api/pom.xml` (additions)

Generate via Spring Initializr with:
- Java 21
- Maven
- Dependencies: Spring Web, Spring Data JPA, Spring Security, Spring Validation, Spring Data Redis, Spring Actuator, Flyway, PostgreSQL Driver, Lombok, Spring Kafka

```xml
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-api</artifactId>
  <version>0.12.6</version>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-impl</artifactId>
  <version>0.12.6</version>
  <scope>runtime</scope>
</dependency>
<dependency>
  <groupId>io.jsonwebtoken</groupId>
  <artifactId>jjwt-jackson</artifactId>
  <version>0.12.6</version>
  <scope>runtime</scope>
</dependency>
```

### .gitignore (root)

**File**: `.gitignore`

```
# Node
node_modules/
.next/
dist/
.pnpm-store/

# Java
target/
*.class
*.jar
!*-SNAPSHOT.jar

# Environment
.env.local
.env.*.local
*.env

# IDE
.idea/
.vscode/
*.iml

# OS
.DS_Store
Thumbs.db
```

### Toolchain version requirements

| Tool | Version |
|---|---|
| Node.js | 22 LTS |
| Java | 21 (Eclipse Temurin / OpenJDK) |
| Maven | 3.9+ |
| Docker + Compose | Engine 25+ / Compose v2 |
| pnpm | 9+ |

---

## Integration Points

**Depends on**: None

**Enables**: All other Phase 1 atoms depend on this scaffold existing

**Cascading updates required**:
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `package.json` | New | Root pnpm workspace manifest |
| `apps/web/` | New | Next.js 15 application scaffold |
| `apps/api/pom.xml` | New | Spring Boot Maven project |
| `services/notification-service/` | New | Service stub directory |
| `services/audit-service/` | New | Service stub directory |
| `infra/docker-compose.yml` | New | Placeholder (completed in atom-03) |
| `infra/kafka/topics.sh` | New | Placeholder (completed in atom-03) |
| `infra/postgres/init.sql` | New | Placeholder (completed in atom-03) |
| `.gitignore` | New | Excludes secrets, build artifacts |
| `.claude/agents/` | New | Agent definition files |
| `.claude/commands/` | New | Slash command files |
| `.claude/settings.json` | New | Hooks config |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `pnpm install` passes (unit tests)
- [ ] `mvn compile` passes
- [ ] Zero industry-specific terms in any identifier or API path
- [ ] Flyway migration exists for all schema changes
- [ ] ADR created or referenced (if architectural decision made)
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: monorepo-scaffold | Phase: 1*
