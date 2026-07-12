# /adr-check

Check whether recent code changes require a new Architecture Decision Record.

## Steps

1. **Get changed files**
   - `git diff --name-only HEAD~1 HEAD`

2. **Evaluate each change against ADR triggers**
   - New `@Entity` class → potential domain model change (check ADR-005)
   - New Kafka producer/consumer → requires ADR if topic is new
   - New external dependency in `pom.xml` or `package.json` → check if significant
   - Change to `SecurityConfig` or JWT builder → check ADR relevance
   - New locking strategy or caching layer → definitely needs an ADR

3. **Cross-reference existing ADRs**
   - List all files in `docs/ADR/` and check if the change contradicts or extends an existing decision.

4. **Generate ADR stub if needed**
   - If a trigger is hit, create `docs/ADR/ADR-{next_number}-{description}.md` using the template in `.claude/agents/adr-docs.md`.
   - Status: `Proposed` — human must change to `Accepted`.

5. **Output**
   - Print: `ADR check complete — {N} new ADR(s) proposed, {N} existing ADR(s) referenced`
