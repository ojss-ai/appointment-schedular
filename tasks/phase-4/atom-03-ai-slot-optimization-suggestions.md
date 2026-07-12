---
description: Atom design document for AI-powered slot optimization suggestions via Claude API
---

# ATOM-ANALYTICS-003: AI Slot Optimization Suggestions via Claude API

**Status**: 🟡 Planned
**Feature**: ai-slot-optimization
**Phase**: 4 (Intelligence)
**Tags**: [SLOT] [ANALYTICS]
**Complexity**: High
**Agent**: coder + orchestrator
**Dependencies**: ATOM-ANALYTICS-001 — booking pattern JSON files available in `docs/memory/booking-patterns/`
**Blocks**: None
**PR**: TBD

---

## Overview

This atom builds a REST endpoint that generates AI-powered slot optimization suggestions for a tenant's resources. The service reads pre-aggregated booking pattern JSON files produced by ATOM-ANALYTICS-001, applies local utilization heuristics to produce optimization hints, then calls the Claude API (`claude-haiku-4-5`) to convert those hints into human-readable admin recommendations. Results are cached in Redis for 24 hours to contain API costs. The key design decisions are: (1) a two-stage pipeline (heuristics before AI) so the AI call is bounded by pre-filtered inputs, and (2) `claude-haiku-4-5` chosen for cost efficiency on structured-output tasks.

---

## User Story

```
As a Tenant Admin
I want AI-generated suggestions for optimizing my resource scheduling slots
So that I can improve throughput and reduce idle time without manually analyzing booking data
```

---

## Acceptance Criteria

- [ ] **AC-01**: `GET /api/v1/tenants/{tenantId}/analytics/slot-optimization` returns a `SlotOptimizationResponse` with non-empty `suggestions` for a tenant with ≥ 7 days of booking pattern data
- [ ] **AC-02**: Suggestions are non-empty, coherent JSON objects with fields `resourceId`, `suggestion`, `details`, `confidence`, and `dataPoints`
- [ ] **AC-03**: A second identical call within 24 hours returns the cached result — no Claude API call is made on the second request (verify via mock or Redis key assertion)
- [ ] **AC-04**: Endpoint is secured: `ADMIN` role required (`@PreAuthorize`) and tenant guard applied — non-admin or cross-tenant request returns `403`
- [ ] **AC-05**: `ANTHROPIC_API_KEY` is read from environment variable — no API key literal in source code or configuration files
- [ ] **AC-06**: The Claude API call uses model `claude-haiku-4-5` (verified via `AnthropicClient` mock assertion)
- [ ] **AC-07**: When `docs/memory/booking-patterns/by-resource/` contains fewer than 7 days of data for the tenant, the response returns `{ suggestions: [], message: "Insufficient data (< 7 days)" }` — no Claude API call is made
- [ ] **AC-08**: When the Claude API call throws an exception, the endpoint returns heuristic-only suggestions (no 500 error); fallback response logged at WARN level
- [ ] **AC-09 (Tenant isolation)**: `loadPatternsForTenant()` filters by `tenantId` — zero patterns from other tenants included in the prompt
- [ ] **AC-10 (Domain abstraction)**: No industry-specific terms appear in any suggestion JSON key, prompt text, or API path

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | `SlotOptimizationServiceIT` — happy path with seeded pattern files | `SlotOptimizationService.getSuggestions()` | 🔜 Planned |
| AC-02 | `SlotOptimizationServiceIT` — response shape validation | `Suggestion` record | 🔜 Planned |
| AC-03 | `SlotOptimizationServiceTest` — Redis mock, assert no second AI call | `SlotOptimizationService.getSuggestions()` | 🔜 Planned |
| AC-04 | `SlotOptimizationControllerIT` — 403 on non-admin and cross-tenant | `SlotOptimizationController` `@PreAuthorize` | 🔜 Planned |
| AC-05 | Static grep for API key literal in source | `AnthropicConfig`, `.env*` | 🔜 Planned |
| AC-06 | `SlotOptimizationServiceTest` — mock AnthropicClient, assert model param | `SlotOptimizationService.generateSuggestionsWithClaude()` | 🔜 Planned |
| AC-07 | `SlotOptimizationServiceTest` — empty pattern directory | `SlotOptimizationService.getSuggestions()` | 🔜 Planned |
| AC-08 | `SlotOptimizationServiceTest` — mock AnthropicClient to throw | `SlotOptimizationService.generateSuggestionsWithClaude()` | 🔜 Planned |
| AC-09 | `SlotOptimizationServiceTest` — multi-tenant pattern directory | `SlotOptimizationService.loadPatternsForTenant()` | 🔜 Planned |
| AC-10 | Static review + grep | Prompt string, response JSON keys | 🔜 Planned |

<!-- AC validation passed: TBD, 10 criteria written, all marked TBD -->

---

## Technical Design

### Architecture

The pipeline runs in three stages inside `SlotOptimizationService.getSuggestions()`:

1. **Cache check** — Redis lookup on key `slot-opt:{tenantId}`; return cached `SlotOptimizationResponse` if present.
2. **Heuristic pre-filter** — `applyHeuristics()` reads tenant-scoped `BookingPatternRecord` objects from the JSON file memory namespace and emits `OptimizationHint` objects for two patterns: over-utilized slots (> 90% utilization, ≥ 10 bookings) and under-utilized resources (average utilization < 20%).
3. **Claude API call** — `generateSuggestionsWithClaude()` serialises the hints to JSON, constructs a prompt, and calls `AnthropicClient` using `claude-haiku-4-5` (cost-efficient model for structured-output tasks). The response JSON array is deserialised into `List<Suggestion>`.

The `AnthropicConfig` `@Configuration` class exposes `AnthropicOkHttpClient` as a Spring bean, reading the API key from `${ANTHROPIC_API_KEY}`.

### Data Flow / Sequence

```
GET /api/v1/tenants/{tenantId}/analytics/slot-optimization
  → SlotOptimizationController.getOptimizationSuggestions(tenantId)
  → SlotOptimizationService.getSuggestions(tenantId)
      → Redis: GET slot-opt:{tenantId}
          if HIT → deserialize → return SlotOptimizationResponse (no AI call)
          if MISS →
              → loadPatternsForTenant(tenantId)
                  → Files.list(docs/memory/booking-patterns/by-resource/)
                  → filter by tenantId
              if patterns < 7 days → return SlotOptimizationResponse([], "Insufficient data")
              → applyHeuristics(patterns)
                  → emit OptimizationHint(EXTEND_HOURS) if utilization > 90%
                  → emit OptimizationHint(REDUCE_BUFFER) if avg utilization < 20%
              → generateSuggestionsWithClaude(hints, tenantId)
                  → AnthropicClient.messages().create(claude-haiku-4-5, prompt)
                  → parse JSON array → List<Suggestion>
              → Redis: SET slot-opt:{tenantId} TTL 24h
              → return SlotOptimizationResponse(suggestions, null)
```

### File Structure

```
apps/api/src/main/java/com/scheduler/
├── analytics/
│   ├── config/
│   │   └── AnthropicConfig.java                ← @Configuration, AnthropicClient bean
│   ├── controller/
│   │   └── SlotOptimizationController.java     ← @RestController, @PreAuthorize
│   ├── service/
│   │   └── SlotOptimizationService.java        ← heuristics + AI pipeline
│   └── record/
│       ├── BookingPatternRecord.java            ← from ATOM-ANALYTICS-001
│       ├── OptimizationHint.java               ← internal hint record
│       ├── Suggestion.java                     ← AI-generated suggestion record
│       └── SlotOptimizationResponse.java       ← API response record

apps/api/src/main/resources/
└── application.yml                             ← add ANTHROPIC_API_KEY binding

apps/api/pom.xml                               ← add anthropic-java 0.8.0 dependency
```

### Interface Contracts

```java
// Response records — Java 21 records

public record OptimizationHint(
    UUID   resourceId,
    String hintCode,    // e.g. "EXTEND_HOURS", "REDUCE_BUFFER"
    String description
) {}

public record Suggestion(
    UUID   resourceId,
    String suggestion,  // hint code: "EXTEND_HOURS", "REDUCE_BUFFER"
    String details,     // 1–2 sentence human-readable explanation
    double confidence,  // 0.0–1.0
    int    dataPoints
) {}

public record SlotOptimizationResponse(
    List<Suggestion> suggestions,
    String           message        // null on success; "Insufficient data (< 7 days)" otherwise
) {}

// Service interface
public interface SlotOptimizationService {
    SlotOptimizationResponse getSuggestions(UUID tenantId);
}

// Controller — method signature only
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/analytics")
public class SlotOptimizationController {

    @GetMapping("/slot-optimization")
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasRole('ADMIN')")
    public ResponseEntity<SlotOptimizationResponse> getOptimizationSuggestions(
            @PathVariable UUID tenantId);
}

// AnthropicConfig — bean declaration only
@Configuration
public class AnthropicConfig {
    @Bean
    public AnthropicClient anthropicClient(@Value("${ANTHROPIC_API_KEY}") String apiKey);
}

// Scheduled job internal method signatures — no bodies
@Service
public class SlotOptimizationService {

    private List<BookingPatternRecord> loadPatternsForTenant(UUID tenantId);

    private List<OptimizationHint> applyHeuristics(List<BookingPatternRecord> patterns);

    private List<Suggestion> generateSuggestionsWithClaude(
            List<OptimizationHint> hints, UUID tenantId);
}
```

```xml
<!-- pom.xml dependency addition -->
<dependency>
  <groupId>com.anthropic</groupId>
  <artifactId>anthropic-java</artifactId>
  <version>0.8.0</version>
</dependency>
```

### Design Rationale

- **Why `claude-haiku-4-5` and not a larger model**: Haiku is optimised for structured-output tasks at low cost. The optimization hints are already pre-filtered by heuristics, so the model's task is rephrasing, not deep reasoning. Using a larger model would increase per-call cost by ~10× with negligible quality improvement for this use case.
- **Why heuristics before the AI call**: Pre-filtering ensures the prompt is small and the model output is bounded. Sending raw booking data to the API would risk exceeding token limits and produce less actionable output.
- **Why Redis cache with 24-hour TTL**: Slot optimization suggestions are not real-time data — they reflect nightly-aggregated patterns. Caching for 24 hours aligns with the nightly ingestion cadence and prevents excessive Anthropic API spend on repeated page loads.
- **Why JSON file memory, not a DB table**: Consistent with the analytics layer design from ATOM-ANALYTICS-001. Files are human-readable, can be inspected for debugging, and decouple the intelligence layer from the transactional schema.
- **ADR-001 reference**: Slots are never stored — the `SlotCalculatorService` computes availability on demand. This atom produces *suggestions* about scheduling configuration, not slot records.
- **ADR-004 reference**: Row-level tenant isolation via `tenant_id` filter applies to pattern file loading — `loadPatternsForTenant()` must filter strictly by `tenantId` before building the AI prompt.

---

## Test Strategy

**Test type**: Unit (JUnit 5 + Mockito) + Integration (Testcontainers + PostgreSQL + Redis)

```
- shouldReturnSuggestions_whenPatternDataExistsFor7OrMoreDays:
    Given: docs/memory/booking-patterns/by-resource/ contains files for tenantId A with 7 days of data
           AnthropicClient mock returns a valid JSON suggestion array
    Assert: getSuggestions(tenantIdA) returns SlotOptimizationResponse with non-empty suggestions

- shouldReturnCachedResponse_onSecondCallWithin24Hours:
    Given: first call populates Redis cache for tenantIdA
    Assert: second call to getSuggestions(tenantIdA) returns same response;
            AnthropicClient.messages().create() invoked exactly once total

- shouldReturnInsufficientData_whenLessThan7DaysOfPatterns:
    Given: pattern directory contains files for tenantIdA covering only 3 dates
    Assert: getSuggestions(tenantIdA) returns SlotOptimizationResponse([], "Insufficient data (< 7 days)")
            AnthropicClient.messages().create() never called

- shouldReturnHeuristicsOnly_whenClaudeApiThrows:
    Given: AnthropicClient mock throws RuntimeException on create()
    Assert: getSuggestions() returns a non-empty response using heuristic hints only; no 500 thrown;
            WARN log entry contains "Claude API failure"

- shouldFilterPatternsByTenantId_excludingOtherTenants:
    Given: pattern directory contains files for tenantA and tenantB
    Assert: getSuggestions(tenantIdA) loads only patterns with tenantId = tenantIdA;
            tenantB patterns never included in the prompt

- shouldReturn403_whenCalledByNonAdminUser:
    Given: JWT bearing tenantA claims with ROLE_USER (not ADMIN)
    Assert: GET /api/v1/tenants/{tenantA-id}/analytics/slot-optimization returns 403

- shouldReturn403_whenCalledCrossTenant:
    Given: JWT bearing tenantA claims with ROLE_ADMIN
    Assert: GET /api/v1/tenants/{tenantB-id}/analytics/slot-optimization returns 403 TENANT_MISMATCH

- shouldUseHaikuModel_inClaudeApiCall:
    Given: AnthropicClient mock captures CreateMessageParams
    Assert: params.model() equals Model.CLAUDE_HAIKU_4_5
```

**Coverage requirements**:
- Line coverage ≥ 80% on `SlotOptimizationService`
- Redis cache hit/miss paths both covered
- Claude API failure fallback path covered

---

## Implementation Constraints

- Anthropic Java SDK: `anthropic-java` version `0.8.0`; client built with `AnthropicOkHttpClient.builder()`
- Model: `claude-haiku-4-5` — do not substitute a different model without an ADR
- Slot optimization results cached in Redis with 24-hour TTL; cache key pattern: `slot-opt:{tenantId}`
- No industry-specific terms in any output JSON key, prompt text, or API path (use `resourceId`, `serviceTypeId` — never `doctorId`, `vehicleId`)
- Memory files read from `docs/memory/booking-patterns/` — path configurable via `${app.memory.booking-patterns-path}`
- `ANTHROPIC_API_KEY` sourced from environment variable only — never hardcoded in source or committed config
- All controller endpoints must carry `@PreAuthorize("@tenantGuard.check(#tenantId) and hasRole('ADMIN')")`
- Claude API call failure must be caught; endpoint must not return 500 — fallback to heuristic-only response
- DTOs must be Java 21 records (never classes)
- No `slots` table — `SlotCalculatorService` remains the sole slot availability source (ADR-001)
- No direct Kafka writes from this service
- No `System.out.println` — use SLF4J `@Slf4j`

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/test/java/…/analytics/SlotOptimizationServiceTest.java` with Mockito mocks for `AnthropicClient`, `StringRedisTemplate`, and `ObjectMapper`
2. Write `shouldReturnSuggestions_whenPatternDataExistsFor7OrMoreDays` — fails (class does not exist)
3. Write `shouldReturnCachedResponse_onSecondCallWithin24Hours` — fails
4. Write `shouldReturnInsufficientData_whenLessThan7DaysOfPatterns` — fails
5. Write `shouldReturnHeuristicsOnly_whenClaudeApiThrows` — fails
6. Write `shouldReturn403_whenCalledByNonAdminUser` in `SlotOptimizationControllerIT` — fails

### GREEN — Minimum code to pass

1. Add `anthropic-java 0.8.0` dependency to `pom.xml`
2. Implement `AnthropicConfig.java` — `AnthropicOkHttpClient` Spring bean
3. Implement `OptimizationHint.java`, `Suggestion.java`, `SlotOptimizationResponse.java` records
4. Implement `SlotOptimizationService.java` — `loadPatternsForTenant()`, `applyHeuristics()`, `generateSuggestionsWithClaude()`, Redis cache logic
5. Implement `SlotOptimizationController.java` with `@PreAuthorize` and tenant guard

### REFACTOR — Quality pass

1. Add structured logging: `log.info("SlotOptimizationService: generating suggestions for tenantId={}, hints={}", tenantId, hints.size())`
2. Add Javadoc to `getSuggestions()`, `applyHeuristics()`, `generateSuggestionsWithClaude()`
3. Extract prompt string to a private constant or `@Value`-injected template
4. Verify no raw exception messages leak into the API response body
5. Run `/security-scan` scoped to `SlotOptimizationController`

---

## Implementation Reference

### AnthropicConfig

**File**: `apps/api/src/main/java/com/scheduler/analytics/config/AnthropicConfig.java`

```java
// [TASK: ATOM-ANALYTICS-003]
package com.scheduler.analytics.config;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnthropicConfig {

    @Bean
    public AnthropicClient anthropicClient(
            @Value("${ANTHROPIC_API_KEY}") String apiKey) {
        return AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .build();
    }
}
```

### Response Records

**File**: `apps/api/src/main/java/com/scheduler/analytics/record/OptimizationHint.java`

```java
// [TASK: ATOM-ANALYTICS-003]
package com.scheduler.analytics.record;

import java.util.UUID;

public record OptimizationHint(
    UUID   resourceId,
    String hintCode,
    String description
) {}
```

**File**: `apps/api/src/main/java/com/scheduler/analytics/record/Suggestion.java`

```java
// [TASK: ATOM-ANALYTICS-003]
package com.scheduler.analytics.record;

import java.util.UUID;

public record Suggestion(
    UUID   resourceId,
    String suggestion,
    String details,
    double confidence,
    int    dataPoints
) {}
```

**File**: `apps/api/src/main/java/com/scheduler/analytics/record/SlotOptimizationResponse.java`

```java
// [TASK: ATOM-ANALYTICS-003]
package com.scheduler.analytics.record;

import java.util.List;

public record SlotOptimizationResponse(
    List<Suggestion> suggestions,
    String           message
) {}
```

### SlotOptimizationService

**File**: `apps/api/src/main/java/com/scheduler/analytics/service/SlotOptimizationService.java`

```java
// [TASK: ATOM-ANALYTICS-003]
package com.scheduler.analytics.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.CreateMessageParams;
import com.anthropic.models.Message;
import com.anthropic.models.Model;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.scheduler.analytics.record.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlotOptimizationService {

    private final StringRedisTemplate redis;
    private final ObjectMapper         objectMapper;
    private final AnthropicClient      anthropicClient;

    @Value("${app.memory.booking-patterns-path:docs/memory/booking-patterns}")
    private String patternsPath;

    private static final Duration CACHE_TTL = Duration.ofHours(24);

    private static final TypeReference<List<Suggestion>> LIST_OF_SUGGESTION =
        new TypeReference<>() {};

    public SlotOptimizationResponse getSuggestions(UUID tenantId) {
        String cacheKey = "slot-opt:" + tenantId;
        String cached   = redis.opsForValue().get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, SlotOptimizationResponse.class);
            } catch (Exception e) {
                log.warn("SlotOptimizationService: cache deserialize failed for tenantId={}: {}",
                    tenantId, e.getMessage());
            }
        }

        List<BookingPatternRecord> patterns = loadPatternsForTenant(tenantId);
        if (patterns.isEmpty()) {
            return new SlotOptimizationResponse(List.of(), "Insufficient data (< 7 days)");
        }

        List<OptimizationHint> hints = applyHeuristics(patterns);

        List<Suggestion> suggestions;
        try {
            suggestions = generateSuggestionsWithClaude(hints, tenantId);
        } catch (Exception e) {
            log.warn("SlotOptimizationService: Claude API failure for tenantId={}, falling back to heuristics: {}",
                tenantId, e.getMessage());
            suggestions = hints.stream()
                .map(h -> new Suggestion(h.resourceId(), h.hintCode(), h.description(), 0.5, 0))
                .toList();
        }

        SlotOptimizationResponse response = new SlotOptimizationResponse(suggestions, null);
        try {
            redis.opsForValue().set(cacheKey,
                objectMapper.writeValueAsString(response), CACHE_TTL);
        } catch (Exception e) {
            log.warn("SlotOptimizationService: Redis cache write failed for tenantId={}: {}",
                tenantId, e.getMessage());
        }
        return response;
    }

    private List<BookingPatternRecord> loadPatternsForTenant(UUID tenantId) {
        Path dir = Path.of(patternsPath, "by-resource");
        if (!Files.exists(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream
                .map(f -> {
                    try {
                        return objectMapper.readValue(f.toFile(), BookingPatternRecord[].class);
                    } catch (IOException e) {
                        log.warn("SlotOptimizationService: could not read pattern file {}: {}",
                            f, e.getMessage());
                        return new BookingPatternRecord[0];
                    }
                })
                .flatMap(Arrays::stream)
                .filter(p -> tenantId.equals(p.tenantId()))
                .toList();
        } catch (IOException e) {
            log.warn("SlotOptimizationService: could not list pattern directory: {}", e.getMessage());
            return List.of();
        }
    }

    private List<OptimizationHint> applyHeuristics(List<BookingPatternRecord> patterns) {
        List<OptimizationHint> hints = new ArrayList<>();

        // Heuristic 1: high utilization slots — suggest extending hours
        patterns.stream()
            .filter(p -> p.utilization() > 0.90 && p.bookingCount() >= 10)
            .forEach(p -> hints.add(new OptimizationHint(
                p.resourceId(), "EXTEND_HOURS",
                "Day %d hour %d: utilization %.0f%% over %d bookings"
                    .formatted(p.dayOfWeek(), p.hourOfDay(),
                               p.utilization() * 100, p.bookingCount()))));

        // Heuristic 2: consistently low utilization — suggest reducing buffer time
        patterns.stream()
            .collect(Collectors.groupingBy(BookingPatternRecord::resourceId,
                     Collectors.averagingDouble(BookingPatternRecord::utilization)))
            .entrySet().stream()
            .filter(e -> e.getValue() < 0.20)
            .forEach(e -> hints.add(new OptimizationHint(
                e.getKey(), "REDUCE_BUFFER",
                "Average utilization %.0f%% — consider reducing buffer time"
                    .formatted(e.getValue() * 100))));

        return hints;
    }

    private List<Suggestion> generateSuggestionsWithClaude(
            List<OptimizationHint> hints, UUID tenantId) throws Exception {
        if (hints.isEmpty()) return List.of();

        String hintsJson = objectMapper.writeValueAsString(hints);
        String prompt = """
            You are a scheduling optimization assistant for a multi-tenant booking platform.
            Analyze these resource utilization hints and return a JSON array of suggestions.
            Each suggestion must have:
              resourceId (string UUID), suggestion (code string), details (1-2 sentences),
              confidence (number 0-1), dataPoints (integer).

            Hints:
            %s

            Return ONLY a valid JSON array. No markdown, no explanation.
            """.formatted(hintsJson);

        log.info("SlotOptimizationService: calling Claude API for tenantId={}, hints={}",
            tenantId, hints.size());

        Message response = anthropicClient.messages().create(
            CreateMessageParams.builder()
                .model(Model.CLAUDE_HAIKU_4_5)
                .maxTokens(1024)
                .addUserMessage(prompt)
                .build());

        String json = response.content().get(0).text().text();
        return objectMapper.readValue(json, LIST_OF_SUGGESTION);
    }
}
```

### SlotOptimizationController

**File**: `apps/api/src/main/java/com/scheduler/analytics/controller/SlotOptimizationController.java`

```java
// [TASK: ATOM-ANALYTICS-003]
package com.scheduler.analytics.controller;

import com.scheduler.analytics.record.SlotOptimizationResponse;
import com.scheduler.analytics.service.SlotOptimizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/analytics")
@RequiredArgsConstructor
public class SlotOptimizationController {

    private final SlotOptimizationService optimizationService;

    @GetMapping("/slot-optimization")
    @PreAuthorize("@tenantGuard.check(#tenantId) and hasRole('ADMIN')")
    public ResponseEntity<SlotOptimizationResponse> getOptimizationSuggestions(
            @PathVariable UUID tenantId) {
        return ResponseEntity.ok(optimizationService.getSuggestions(tenantId));
    }
}
```

---

## Integration Points

**Depends on**: ATOM-ANALYTICS-001 — `docs/memory/booking-patterns/by-resource/{resourceId}.json` files populated nightly; Redis running

**Enables**: Tenant admins can view AI-generated scheduling recommendations from the admin dashboard

**Cascading updates required**:
- `docs/API-SPEC.md` — add `GET /api/v1/tenants/{tenantId}/analytics/slot-optimization` endpoint entry
- `tasks/MASTER-TASK-LIST.md` — mark ATOM-ANALYTICS-003 complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/api/pom.xml` | Modified | Add `anthropic-java 0.8.0` dependency |
| `apps/api/src/main/java/…/analytics/config/AnthropicConfig.java` | New | Spring bean for `AnthropicClient` |
| `apps/api/src/main/java/…/analytics/record/OptimizationHint.java` | New | Internal hint record |
| `apps/api/src/main/java/…/analytics/record/Suggestion.java` | New | AI suggestion output record |
| `apps/api/src/main/java/…/analytics/record/SlotOptimizationResponse.java` | New | API response record |
| `apps/api/src/main/java/…/analytics/service/SlotOptimizationService.java` | New | Heuristics + AI pipeline |
| `apps/api/src/main/java/…/analytics/controller/SlotOptimizationController.java` | New | REST endpoint with `@PreAuthorize` |
| `apps/api/src/test/java/…/analytics/SlotOptimizationServiceTest.java` | New | Unit tests (Mockito) |
| `apps/api/src/test/java/…/analytics/SlotOptimizationControllerIT.java` | New | Integration tests (MockMvc) |
| `docs/API-SPEC.md` | Modified | Add slot-optimization endpoint |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] Zero industry-specific terms in any JSON key, prompt text, or API path
- [ ] `@PreAuthorize("@tenantGuard.check(#tenantId) and hasRole('ADMIN')")` on controller method
- [ ] `ANTHROPIC_API_KEY` never hardcoded — sourced from environment variable only
- [ ] Model `claude-haiku-4-5` used in `CreateMessageParams`
- [ ] Redis cache hit/miss both covered by tests
- [ ] Claude API failure fallback covered by tests — no 500 returned
- [ ] `loadPatternsForTenant()` filters strictly by `tenantId`
- [ ] ADR created or referenced (if architectural decision made)
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: ai-slot-optimization | Phase: 4*
