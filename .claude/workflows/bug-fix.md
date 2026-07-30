# Workflow: bug-fix

1. Reproduce — testgen agent writes a failing test that captures the defect.
2. Diagnose — coder agent traces root cause; check tenant isolation impact first.
3. Fix — coder agent applies the minimal change; no drive-by refactors.
4. Verify — full test suite + the new regression test pass.
5. Audit — security agent confirms no tenant_id filter was weakened.
6. Record — adr-docs agent updates docs/memory/task-progress.md.
