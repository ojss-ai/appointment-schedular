# Clarifications — <feature-slug>
**Spec:** `specs/<slug>/spec.md`
**Clarified:** <date>
**Completeness score:** <N>/10

---

## Summary

<One paragraph: what key decisions were made during clarification that will most affect implementation.>

---

<!-- Repeat this block for each question answered -->

## Q-01: <Category>
**Question:** <question text>
**Answer:** <user's answer>
**Impact:** <one sentence: what this changes about the implementation>

## Q-02: <Category>
**Question:** 
**Answer:** 
**Impact:** 

---

## Implementation Decisions

<!-- Summarise the key decisions made, in the format the coder agent needs -->

| Decision | Value | Source |
|---|---|---|
| New Flyway migration needed? | yes/no | Q-NN |
| Kafka outbox event required? | yes/no — event type: | Q-NN |
| Redis cache invalidation? | yes/no — key pattern: | Q-NN |
| Pessimistic lock required? | yes/no | Q-NN |
| New @Entity classes? | <list> | Q-NN |
| New API endpoints? | <list> | Q-NN |
| Next.js pages/routes? | <list> | Q-NN |
| Role constraints | <which roles> | Q-NN |
