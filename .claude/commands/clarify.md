# /clarify — Feature Clarification

**Usage:** `/clarify` (run after `/specify`)

You are the **spec-agent** in clarification mode. Your job is to find and resolve every ambiguity in the current feature spec before any implementation planning begins.

---

## Step 1 — Find the active spec

Look for the most recently modified `specs/*/spec.md`. If multiple exist, list them and ask the user which one to clarify.

Read the spec fully. Also read `CLAUDE.md` (architecture constraints) and `docs/DATABASE-SCHEMA.md` if it exists.

---

## Step 2 — Gap analysis

Before asking any questions, silently audit the spec against these gap categories:

| Category | What to check |
|---|---|
| **Tenant isolation** | Does every new entity/query need a `tenant_id` filter? Is this stated? |
| **Slot impact** | Does the feature touch availability computation? Is it clear no slots are stored? |
| **Concurrency** | Are there race conditions? Is pessimistic lock strategy stated? |
| **Kafka events** | Does the feature produce state changes that need outbox events? |
| **Auth/roles** | Which roles can trigger each user story? Are role constraints explicit? |
| **Edge cases** | What happens at boundaries: empty state, max limits, concurrent updates, timezone edge? |
| **Data migration** | Does this require a new Flyway migration? Is backward compatibility stated? |
| **Extension data** | Should any tenant-specific metadata live in the `extension` JSONB column? |
| **UI** | Which screens are new vs modified? Is the Next.js app router path defined? |
| **NFR impact** | Could this degrade slot generation latency (NFR-1.2) or booking throughput (NFR-1.1)? |

Also collect all `Q-NN` questions from the spec's **Open Questions** section.

---

## Step 3 — Ask questions sequentially

Ask questions **one at a time**. Wait for the user's answer before asking the next.

Format each question as:

```
**Q-[N]: [Category]**
[Question text]

(Context: [One sentence explaining why this matters for implementation])
```

Start with the open questions already in the spec, then add any gaps you found in Step 2.

**Important:** Do NOT ask questions about things that are already clearly answered in the spec. Only ask about genuine gaps.

---

## Step 4 — Record answers

After all questions are answered, update `specs/<slug>/clarifications.md` with the full Q&A record:

```markdown
# Clarifications — <feature-slug>
**Spec:** specs/<slug>/spec.md
**Date:** <today>

## Q-01: <Category>
**Question:** <question text>
**Answer:** <user's answer>
**Impact:** <one line: what this changes about the implementation>

## Q-02: ...
```

Also update the spec's **Open Questions** section — mark each resolved question with `✅` and add a link to the clarification.

---

## Step 5 — Completeness score

After recording all answers, print:

```
✅ Clarifications saved: specs/<slug>/clarifications.md
   Questions resolved: <N>
   Spec completeness: <score>/10
   
   Remaining risks:
   - <any still-open questions or ambiguities>
   
   Next step: /atomize
```

Score 8+ means ready to atomize. If score < 8, list what is still missing and offer to ask more questions.

Do NOT run `/atomize` automatically.
