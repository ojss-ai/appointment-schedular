# /specify — Feature Specification

**Usage:** `/specify <feature description>`

You are the **spec-agent** for the Multi-Tenant Omni-Industry Scheduling Framework. Your job is to take the user's feature description and produce a complete, unambiguous specification.

---

## Step 1 — Parse the feature slug

Derive a short kebab-case slug from the feature description (e.g., "recurring booking support" → `recurring-booking-support`). Announce it:

```
Feature slug: <slug>
Output: specs/<slug>/spec.md
```

---

## Step 2 — Domain abstraction check

Before writing a single word of the spec, scan the user's description for any industry-specific terms and silently translate them:

| If the user said | Write instead |
|---|---|
| doctor, provider, therapist, mechanic, stylist | Resource |
| patient, customer, client, visitor | Booking initiator (or just "user") |
| appointment, session, class, service | Service |
| clinic, shop, salon, garage, gym | Location |
| hospital, chain, franchise, company | Tenant |
| time slot, opening, availability | computed slot |

The spec must never contain the original industry terms.

---

## Step 3 — Write the spec

Create `specs/<slug>/spec.md` using the template at `specs/_template/spec-template.md`.

Populate every section:

### 3a. Summary
One paragraph: what the feature does and why it matters to a Tenant operator.

### 3b. User Stories
Write user stories in this exact format (use the roles defined in the system: **Tenant Admin**, **Resource**, **Booking User**, **System**):

```
US-01: As a <role>, I want to <action> so that <outcome>.
  Acceptance Criteria:
    - AC-01: <specific, testable condition>
    - AC-02: <specific, testable condition>
```

Minimum 3 user stories. Each must have at least 2 acceptance criteria.

### 3c. Functional Requirements
Numbered list. Each requirement must:
- Reference which user story it supports (e.g., `[US-01]`)
- Be specific enough that a developer can write a test against it
- NOT describe implementation — describe observable behaviour

### 3d. Non-Functional Requirements
Flag any new NFRs this feature introduces. Cross-reference existing NFRs from `CLAUDE.md` that this feature must not violate (especially NFR-1.1, NFR-1.2, NFR-1.3).

### 3e. Out of Scope
Explicitly list at least 3 things this feature does NOT cover. This prevents scope creep during `/atomize`.

### 3f. Open Questions
List anything ambiguous that `/clarify` will need to resolve. Number each question (Q-01, Q-02, ...).

### 3g. Review Checklist
```
- [ ] All entity names use domain-abstracted terminology (no industry terms)
- [ ] Every user story has at least 2 testable acceptance criteria
- [ ] tenant_id isolation impact assessed
- [ ] No new stored slots (SlotCalculatorService computes on demand)
- [ ] Outbox pattern required? (yes/no — justify)
- [ ] Open questions documented
```

---

## Step 4 — Output summary

Print to the user:
```
✅ Spec created: specs/<slug>/spec.md
   User stories: <N>
   Open questions: <N> — run /clarify to resolve them
   Next step: /clarify
```

Do NOT proceed to `/clarify` or `/atomize` automatically. Wait for the user.
