# Feature Spec — <Feature Name>
**Slug:** `<feature-slug>`
**Status:** Draft | Under Clarification | Ready for Atomize | Atomized
**Created:** <date>
**Author:** <author>

---

## Summary

<One paragraph: what this feature does and why it matters to a Tenant operator.>

---

## User Stories

<!--
Format:
US-NN: As a <Tenant Admin | Resource | Booking User | System>,
        I want to <action>
        so that <outcome>.

  Acceptance Criteria:
    - AC-NN: <specific, testable, observable condition — no implementation details>
-->

US-01: As a **<role>**, I want to <action> so that <outcome>.
  Acceptance Criteria:
    - AC-01: 
    - AC-02: 

US-02: As a **<role>**, I want to <action> so that <outcome>.
  Acceptance Criteria:
    - AC-01: 
    - AC-02: 

US-03: As a **<role>**, I want to <action> so that <outcome>.
  Acceptance Criteria:
    - AC-01: 
    - AC-02: 

---

## Functional Requirements

<!-- Each requirement must: reference a user story, be testable, describe behaviour not implementation -->

FR-01 [US-01]: 
FR-02 [US-01]: 
FR-03 [US-02]: 
FR-04 [US-03]: 

---

## Non-Functional Requirements

<!-- Flag new NFRs. Cross-reference existing NFRs from CLAUDE.md that apply. -->

| NFR | Requirement | Existing gate |
|---|---|---|
| Performance | <e.g., new endpoint must meet NFR-1.2: p99 < 300ms> | NFR-1.2 |
| Tenant isolation | All new queries must include tenant_id filter | NFR (architecture principle) |
| <new if any> | | |

---

## Out of Scope

<!-- Explicitly list at least 3 things this feature does NOT cover -->

1. 
2. 
3. 

---

## Open Questions

<!-- Numbered Q-NN. Each will be addressed by /clarify -->

- Q-01: 
- Q-02: 
- Q-03: 

---

## Review Checklist

- [ ] All entity names use domain-abstracted terminology (no industry terms)
- [ ] Every user story has at least 2 testable acceptance criteria
- [ ] tenant_id isolation impact assessed
- [ ] No new stored slots proposed (SlotCalculatorService computes on demand)
- [ ] Outbox pattern required? (yes/no — justify below)
- [ ] Open questions documented above
- [ ] Out-of-scope section lists at least 3 items
- [ ] No implementation details in functional requirements

**Outbox justification:** <yes/no and why>
