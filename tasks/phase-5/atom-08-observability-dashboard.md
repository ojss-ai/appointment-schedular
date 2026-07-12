---
description: Production observability — Prometheus metrics, Grafana dashboard (7 panels), alerting rules, cost log
---

# ATOM-INFRA-508: Observability Dashboard and Alerting

**Status**: 🟡 Planned
**Feature**: infra-observability
**Phase**: 5 (Production)
**Tags**: [INFRA]
**Complexity**: Medium
**Agent**: observability
**Dependencies**: ATOM-INFRA-506 (AWS deployment — running services required for metric scraping)
**Blocks**: None
**PR**: TBD

---

## Overview

This atom configures production observability: Spring Boot Actuator metrics exposed to Prometheus, a 7-panel Grafana dashboard covering slot calculation latency, PENDING_HOLD accumulation, Kafka consumer lag, OTP dispatch rates, booking confirmation rate, DLQ depth, and DB connection pool utilisation. Five Prometheus alerting rules fire to Slack via Alertmanager. The cost log (`docs/COST-LOG.md`) is initialised and the `/cost-report` slash command is verified to append entries.

---

## User Story

```
As a Tenant Admin
I want production metrics visible in a Grafana dashboard with automated alerting
So that the operations team can detect and respond to performance degradation, DLQ build-up, or OTP failures without manual log polling
```

---

## Acceptance Criteria

- [ ] **AC-01**: All 9 custom metrics from the observability agent spec are visible in Grafana
- [ ] **AC-02**: `DLQMessageDetected` alert fires within 2 minutes of a message landing in the DLQ
- [ ] **AC-03**: `SlotCalcHighLatency` alert fires when p99 > 250ms (verified by temporarily slowing DB)
- [ ] **AC-04**: All alerts reach Slack (Alertmanager → Slack webhook configured and tested)
- [ ] **AC-05**: `docs/COST-LOG.md` initialised with first session entry
- [ ] **AC-06**: `/cost-report` slash command works and appends to `docs/COST-LOG.md`
- [ ] **AC-07**: Grafana dashboard accessible to the team with authentication (Grafana Cloud or self-hosted)
- [ ] **AC-08 (Domain abstraction)**: No industry-specific terms in metric names, alert names, or dashboard panel labels

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | Grafana dashboard — all 7 panels populate with data | Spring Boot Actuator `/actuator/prometheus` | 🔜 Planned |
| AC-02 | Manual: push message to DLQ → Slack notification within 2min | `infra/observability/alert-rules.yml` — `DLQMessageDetected` | 🔜 Planned |
| AC-03 | Manual: add `Thread.sleep(300)` to `SlotCalculatorService` → alert fires | `infra/observability/alert-rules.yml` — `SlotCalcHighLatency` | 🔜 Planned |
| AC-04 | Slack channel: receive test alert after Alertmanager config applied | Alertmanager → Slack webhook | 🔜 Planned |
| AC-05 | `cat docs/COST-LOG.md` — first entry present | `docs/COST-LOG.md` | 🔜 Planned |
| AC-06 | Run `/cost-report` in Claude Code session — new row appended | `.claude/commands/cost-report.md` | 🔜 Planned |
| AC-07 | Team members access Grafana dashboard URL with credentials | Grafana Cloud / self-hosted | 🔜 Planned |

<!-- AC validation passed: TBD, 7 criteria mapped, all TBD -->

---

## Technical Design

### Architecture

Spring Boot Actuator exposes a `/actuator/prometheus` endpoint scraped by Prometheus. Custom metrics are registered via Micrometer `MeterRegistry` in `SlotCalculatorService` and other key service classes. Prometheus alert rules are defined in `infra/observability/alert-rules.yml` and loaded by the Prometheus server. Alertmanager routes alerts to Slack. Grafana reads from the Prometheus datasource and renders the 7-panel dashboard defined in `infra/observability/grafana-dashboard.json`.

### Data Flow / Sequence

```
SlotCalculatorService.computeAvailableSlots()
  → slotCalcTimer.record(() -> doCompute())
  → Micrometer Timer: scheduling.slot.calc.duration {p50, p95, p99}

Prometheus scraper (every 15s)
  → GET http://scheduler-api:8080/actuator/prometheus
  → stores metrics in time-series DB

Prometheus alert evaluation (every 1m)
  → histogram_quantile(0.99, scheduling_slot_calc_duration_seconds_bucket) > 0.25
  → if true for 5m → SlotCalcHighLatency alert FIRING
  → Alertmanager → Slack webhook → #alerts channel

Grafana dashboard
  → queries Prometheus datasource
  → renders 7 panels (refresh every 30s)
```

### File Structure

```
infra/
└── observability/
    ├── alert-rules.yml              ← 5 Prometheus alerting rules
    ├── grafana-dashboard.json       ← 7-panel Grafana dashboard definition
    └── alertmanager.yml             ← Alertmanager → Slack webhook config

docs/
└── COST-LOG.md                      ← Claude agent cost tracking log

apps/api/src/main/java/com/scheduler/
└── service/
    └── SlotCalculatorService.java   ← custom Timer metric registration
```

### Interface Contracts

```java
// Custom metric registration in SlotCalculatorService (signatures only)
public interface SlotCalculatorService {
    List<AvailableSlot> computeAvailableSlots(
        UUID tenantId, UUID locationId, UUID resourceId,
        UUID serviceTypeId, LocalDate date
    );
}

// Micrometer Timer bean shape
// Timer.builder("scheduling.slot.calc.duration")
//   .publishPercentiles(0.5, 0.95, 0.99)
//   .register(registry)
```

```yaml
# Prometheus alert rule shape
groups:
  - name: scheduler-alerts
    rules:
      - alert: <AlertName>
        expr: <PromQL expression>
        for: <duration>
        labels: { severity: critical | warning }
        annotations:
          summary: "<human-readable message>"
```

### Design Rationale

- **Micrometer Timer (not manual `System.currentTimeMillis`)**: Micrometer integrates with Spring Boot Actuator and automatically exposes histogram buckets compatible with `histogram_quantile()` PromQL. Manual timing would require custom exposition format.
- **`DLQMessageDetected` fires immediately (`for: 0m`)**: Any message in the DLQ is a processing failure that requires immediate investigation. Unlike latency alerts which need a sustain period to filter transients, a non-zero DLQ depth is always actionable.
- **`SlotCalcHighLatency` threshold at 250ms (not 300ms)**: The alert fires at 250ms — 50ms below the NFR-1.2 gate — giving the operations team warning time before the 300ms SLO is breached.
- **NFR-1.2 context**: The `scheduling.slot.calc.duration` Timer directly measures the operation validated by the ATOM-PERF-501 load test. The alert provides continuous production monitoring of the same gate.

---

## Test Strategy

**Test type**: Integration (manual verification in staging) + alert firing verification

```
- slotCalcTimer_recordsLatency:
    Given: SlotCalculatorService executes with mocked dependencies taking 100ms
    Assert: Micrometer Timer records duration; Actuator /actuator/prometheus endpoint includes
            scheduling_slot_calc_duration_seconds_bucket metrics

- dlqAlert_firesWithin2Minutes:
    Given: message manually placed in DLQ topic in staging MSK
    Assert: Slack #alerts channel receives DLQMessageDetected notification within 2 minutes

- slotCalcAlert_firesWhenP99Exceeds250ms:
    Given: SlotCalculatorService artificially slowed (Thread.sleep(300)) in staging
    Assert: SlotCalcHighLatency alert fires within 5 minutes; Slack notification received

- grafanaDashboard_allPanelsPopulate:
    Given: application running and receiving traffic in staging
    Assert: all 7 Grafana panels show non-null data (no "No data" panels)

- costLog_appendedBySlashCommand:
    Given: /cost-report command run in Claude Code session
    Assert: docs/COST-LOG.md has new row with date, session ID, token counts
```

**Coverage requirements**:
- `SlotCalculatorService` Timer registration: unit test must verify `slotCalcTimer` is called during `computeAvailableSlots()`
- Alert rules: validate PromQL syntax using `promtool check rules alert-rules.yml`

---

## Implementation Constraints

- Custom metric names must use `scheduling.` prefix — no industry-specific terms in metric names
- `DLQMessageDetected` alert must use `for: 0m` — no sustain period tolerance for DLQ messages
- `SlotCalcHighLatency` alert threshold is 250ms (`> 0.25`) — must not be changed to 300ms (early warning requirement)
- Alertmanager Slack webhook URL must be stored in AWS Secrets Manager — never in `alertmanager.yml` source
- Grafana dashboard JSON must be committed to source — no "it's configured in the UI" approach; configuration must be reproducible
- No `System.out.println` in `SlotCalculatorService` metric code — use SLF4J only
- `/cost-report` command must append (not overwrite) to `docs/COST-LOG.md`

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Write unit test asserting `slotCalcTimer.record()` is called inside `computeAvailableSlots()` — test fails (Timer not yet wired)
2. Run `promtool check rules infra/observability/alert-rules.yml` on an empty file — fails
3. Confirm Grafana dashboard JSON is not yet committed

### GREEN — Minimum code to pass

1. Add `micrometer-registry-prometheus` dependency to `pom.xml`
2. Configure `application.yml` to expose `prometheus` endpoint
3. Wire `Timer` bean in `SlotCalculatorService` via `MeterRegistry`
4. Write `infra/observability/alert-rules.yml` with all 5 rules
5. Generate `infra/observability/grafana-dashboard.json` with 7 panels
6. Run `promtool check rules` — exits 0
7. Unit test passes

### REFACTOR — Staging verification

1. Deploy to staging (ATOM-INFRA-506 prerequisite)
2. Verify all 7 Grafana panels populate with real data
3. Test `DLQMessageDetected` and `SlotCalcHighLatency` alerts fire to Slack
4. Initialise `docs/COST-LOG.md` and verify `/cost-report` appends correctly
5. Configure Grafana authentication and share dashboard URL with team

---

## Implementation Reference

### Spring Boot Actuator Configuration

**File**: `apps/api/src/main/resources/application.yml` (additions)

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  metrics:
    tags:
      application: scheduler-api
```

**File**: `apps/api/pom.xml` (dependency addition)

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

### Custom Metrics in SlotCalculatorService

**File**: `apps/api/src/main/java/com/scheduler/service/SlotCalculatorService.java` (metric additions)

```java
// [TASK: ATOM-INFRA-508]
@Service
@RequiredArgsConstructor
public class SlotCalculatorService {
    private final Timer slotCalcTimer;  // injected via MeterRegistry

    public List<AvailableSlot> computeAvailableSlots(...) {
        return slotCalcTimer.record(() -> doCompute(...));
    }

    @Bean
    static Timer slotCalcTimer(MeterRegistry registry) {
        return Timer.builder("scheduling.slot.calc.duration")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
    }
}
```

### Prometheus Alert Rules

**File**: `infra/observability/alert-rules.yml`

```yaml
# [TASK: ATOM-INFRA-508]
groups:
  - name: scheduler-alerts
    rules:
      - alert: SlotCalcHighLatency
        expr: histogram_quantile(0.99, scheduling_slot_calc_duration_seconds_bucket) > 0.25
        for: 5m
        labels: { severity: critical }
        annotations:
          summary: "Slot calculation p99 > 250ms"

      - alert: PendingHoldAccumulation
        expr: scheduling_pending_hold_active > 100
        for: 2m
        labels: { severity: warning }

      - alert: KafkaConsumerLag
        expr: scheduling_kafka_consumer_lag > 1000
        for: 3m
        labels: { severity: critical }

      - alert: DLQMessageDetected
        expr: scheduling_dlq_depth > 0
        for: 0m
        labels: { severity: critical }
        annotations:
          summary: "Messages in DLQ — immediate investigation required"

      - alert: OTPFailureRateHigh
        expr: rate(scheduling_otp_dispatch_total{status="failure"}[10m]) /
              rate(scheduling_otp_dispatch_total[10m]) > 0.05
        for: 0m
        labels: { severity: warning }
```

### Grafana Dashboard Panels

**File**: `infra/observability/grafana-dashboard.json` (panel definitions)

The dashboard JSON must define 7 panels:

1. Slot calculation p50/p95/p99 latency — histogram panel (`scheduling_slot_calc_duration_seconds`)
2. Active PENDING_HOLD count per tenant — bar chart (`scheduling_pending_hold_active`)
3. Kafka consumer lag per group — time series (`scheduling_kafka_consumer_lag`)
4. OTP dispatch success/failure rate by channel — pie + time series (`scheduling_otp_dispatch_total`)
5. Booking confirmation rate — confirmations/holds per hour (`scheduling_booking_confirmed_total`)
6. DLQ depth — stat panel, red when > 0 (`scheduling_dlq_depth`)
7. DB connection pool utilisation % — gauge (`hikaricp_connections_active / hikaricp_connections_max`)

### Cost Log Initialisation

**File**: `docs/COST-LOG.md`

```markdown
# Claude Agent Cost Log

| Date | Session ID | Agent | Input Tokens | Output Tokens | Est. Cost (USD) | Tasks |
|---|---|---|---|---|---|---|
| 2026-06-18 | session-001 | orchestrator | — | — | — | P1-T01 through P1-T10 |
```

---

## Integration Points

**Depends on**: ATOM-INFRA-506 (AWS deployment — services must be running for metric scraping to work); Prometheus and Grafana infrastructure available (Grafana Cloud or self-hosted)

**Enables**: Continuous production monitoring; operations team alert response; cost tracking for Claude Code sessions

**NFR Gates satisfied**: NFR-1.2 (ongoing production monitoring via `SlotCalcHighLatency` alert — continuous enforcement beyond the one-time load test)

**Cascading updates required**:
- `docs/COST-LOG.md` — initialise (new file)
- `tasks/MASTER-TASK-LIST.md` — mark atom complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/src/main/resources/application.yml` | Modified | Expose Prometheus endpoint |
| `apps/api/pom.xml` | Modified | Add micrometer-registry-prometheus dependency |
| `apps/api/src/main/java/com/scheduler/service/SlotCalculatorService.java` | Modified | Wire slotCalcTimer metric |
| `infra/observability/alert-rules.yml` | New | 5 Prometheus alerting rules |
| `infra/observability/grafana-dashboard.json` | New | 7-panel Grafana dashboard definition |
| `infra/observability/alertmanager.yml` | New | Alertmanager → Slack webhook routing |
| `docs/COST-LOG.md` | New | Claude agent cost tracking log |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `promtool check rules infra/observability/alert-rules.yml` exits 0
- [ ] All 7 Grafana panels populate with real data in staging
- [ ] `DLQMessageDetected` alert fires to Slack within 2 minutes (verified in staging)
- [ ] `SlotCalcHighLatency` alert fires at p99 > 250ms (verified by artificial delay in staging)
- [ ] Alertmanager Slack webhook URL stored in Secrets Manager — not in `alertmanager.yml` source
- [ ] `docs/COST-LOG.md` initialised with first session entry
- [ ] `/cost-report` command appends to cost log (verified)
- [ ] Grafana dashboard JSON committed to source (not just configured in UI)
- [ ] Zero industry-specific terms in metric names, alert names, or panel labels
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: infra-observability | Phase: 5*
