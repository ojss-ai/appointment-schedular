# Workflow: Debug Slot Availability Issue
<!-- ATOM-ORCHESTRATION-002 — 6-step slot availability debugging workflow.
     Slots are never stored (ADR-001): availability = operating matrix −
     confirmed bookings − buffer windows, so debugging means re-deriving
     that computation from source data. -->

## Preconditions
- [ ] Affected booking ID or resource ID and date provided
- [ ] Error description or reproduction steps provided

## Steps

1. **[orchestrator]** Retrieve booking records for the affected resource and date from the bookings table
2. **[orchestrator]** Check resource_schedules for that resource and the relevant day_of_week
3. **[orchestrator]** Check branch_holidays for the location on the affected date
4. **[orchestrator]** Check buffer_before_min and buffer_after_min on the associated service type
5. **[orchestrator]** Trace SlotCalculatorService logic against the retrieved data: operating matrix − confirmed bookings − buffer windows = expected available slots
6. **[adr-docs]** Document root cause and proposed fix as a new entry in docs/DEBUGGING-LOG.md

## Success Criteria
- Root cause identified and documented in docs/DEBUGGING-LOG.md
- Fix specification written or linked to a new atom task
- Affected booking or slot state is understood and explainable
