# Project Memory Namespaces

Persistent context anchors read and written by Claude Code agents across
sessions. Each file maps to a memory namespace declared in `CLAUDE.md`
(section: Memory namespaces). Header format is mandatory:
`# scheduling:{namespace}`.

| File | Namespace | Maintained by |
|---|---|---|
| domain-model.md | scheduling:domain-model | adr-docs agent |
| api-contracts.md | scheduling:api-contracts | adr-docs agent |
| kafka-topology.md | scheduling:kafka-topology | coder agent |
| security-rules.md | scheduling:security-rules | security agent |
| task-progress.md | scheduling:task-progress | orchestrator agent |

`booking-patterns/` stores analytics-layer memory (Phase 4).
