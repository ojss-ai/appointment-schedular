# ATOM-UI-016: Next.js JSON Schema Form Builder

**Status**: 🟡 Planned
**Feature**: admin-ui
**Phase**: 2 (Core)
**Tags**: [UI]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-SERVICE-003
**Blocks**: None
**PR**: TBD

---

## Overview

A drag-and-drop intake form builder for tenant admins. Admins design the customer-facing intake form by adding, reordering, and configuring field definitions. The builder serializes the field definitions to a JSON Schema draft-07 document and saves it to `service_types.intake_schema` via the ServiceType API. A live preview panel renders the form in real time using `react-jsonschema-form`. Once saved, the schema is immediately available for validation during booking confirmation.

---

## User Story

```
As a Tenant Admin
I want to design the customer intake form for a service type using a visual builder
So that I can customize the data collected at booking time without writing JSON Schema by hand
```

---

## Acceptance Criteria

- [ ] **AC-01**: Admin can add, reorder (drag), and remove field definitions in the builder UI
- [ ] **AC-02**: The live preview panel updates in real time as fields are added, removed, or reordered — no save required
- [ ] **AC-03**: The serialized schema validates as JSON Schema draft-07 (enforced server-side on save via `InvalidIntakeSchemaException`)
- [ ] **AC-04**: The saved schema is immediately usable by the customer booking form — `react-jsonschema-form` renders it correctly
- [ ] **AC-05**: The auto-generated `key` for a field is camelCase derived from the label (e.g., "Chief Complaint" → "chiefComplaint")
- [ ] **AC-06**: The Select field type supports adding, editing, and removing enum options via inline inputs
- [ ] **AC-07**: The required toggle correctly sets the `required` array in the output JSON Schema
- [ ] **AC-08 (Tenant isolation)**: The save action calls `PUT .../tenants/{tenantId}/service-types/{id}` with the `tenantId` from the authenticated session — no tenant ID is accepted from the form
- [ ] **AC-09 (Domain abstraction)**: No industry-specific terms in any component name, field type label, or variable name in this package

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | TBD | TBD | 🔜 Planned |
| AC-02 | TBD | TBD | 🔜 Planned |
| AC-03 | TBD | TBD | 🔜 Planned |
| AC-04 | TBD | TBD | 🔜 Planned |
| AC-05 | TBD | TBD | 🔜 Planned |
| AC-06 | TBD | TBD | 🔜 Planned |
| AC-07 | TBD | TBD | 🔜 Planned |
| AC-08 | TBD | TBD | 🔜 Planned |
| AC-09 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 9 criteria rewritten, 9 marked TBD -->

---

## Technical Design

### Architecture

Client component (`'use client'`) maintaining `FieldDef[]` state. Drag-and-drop reordering uses `@dnd-kit/sortable`. Field state is serialized on-the-fly to a JSON Schema object via `serializeToJsonSchema(fields)`. The right panel renders a `react-jsonschema-form` instance with the live schema. The save button calls a server action that PUTs the schema to the ServiceType API.

### File Structure

```
apps/web/src/app/(admin)/forms/
└── [serviceTypeId]/
    └── page.tsx                         ← form builder page (server shell)

apps/web/src/components/admin/form-builder/
├── FormBuilder.tsx                      ← main builder client component
├── FieldList.tsx                        ← sortable field list (@dnd-kit)
├── FieldCard.tsx                        ← single field definition card
├── FieldTypeSelector.tsx                ← dropdown: Short text, Long text, Number, etc.
├── EnumOptionsEditor.tsx                ← add/edit/remove select options
└── FormPreview.tsx                      ← react-jsonschema-form live preview

apps/web/src/lib/
└── schema-serializer.ts                 ← serializeToJsonSchema() pure function
```

### Interface Contracts

```typescript
// Field definition state shape
interface FieldDef {
  key: string;          // camelCase auto-generated from label
  label: string;
  type: 'string' | 'number' | 'boolean';
  format?: string;      // 'textarea', 'date', etc.
  enum?: string[];      // for select fields only
  required: boolean;
  placeholder?: string;
}

// Supported field types (displayed as human-readable labels in UI)
type FieldType =
  | 'short-text'    // → { type: 'string' }
  | 'long-text'     // → { type: 'string', format: 'textarea' }
  | 'number'        // → { type: 'number' }
  | 'date'          // → { type: 'string', format: 'date' }
  | 'checkbox'      // → { type: 'boolean' }
  | 'select';       // → { type: 'string', enum: [...] }

// Serializer function signature only
function serializeToJsonSchema(fields: FieldDef[]): JsonSchema;

// camelCase converter signature only
function toCamelCase(label: string): string;

// Server action signature only
async function saveIntakeSchema(
  tenantId: string,
  serviceTypeId: string,
  schema: JsonSchema
): Promise<void>;
```

### Design Rationale

- **`serializeToJsonSchema` as a pure function**: Separating serialization from component state makes it independently unit-testable. The live preview calls this function on every render; correctness can be verified without mounting the full component.
- **camelCase auto-key**: Prevents admins from accidentally creating keys with spaces or special characters that would break JSON Schema property names. Admins can see the generated key and correct the label if needed.
- **`@dnd-kit/sortable`**: Preferred over `react-beautiful-dnd` (deprecated) and HTML5 drag-and-drop API (poor keyboard accessibility). Provides accessible drag-and-drop with keyboard fallback.
- **Server action for save**: Keeps the API base URL and auth token server-side. The schema is POSTed to the ServiceType endpoint which validates it as JSON Schema draft-07 before persisting.

---

## Test Strategy

**Test type**: Unit (Jest/Vitest) + Component (React Testing Library) + E2E (Playwright)

```
- shouldSerializeFieldsToJsonSchema_correctly:
    Given: fields = [{key:'name', type:'string', required:true}, {key:'age', type:'number', required:false}]
    Assert: serializeToJsonSchema returns { type:'object', properties:{name:{type:'string'}, age:{type:'number'}}, required:['name'] }

- shouldGenerateCamelCaseKey_fromLabel:
    Given: label = "Chief Complaint"
    Assert: toCamelCase("Chief Complaint") === "chiefComplaint"

- shouldUpdatePreview_onFieldAdd:
    Given: FormBuilder rendered with 0 fields; admin adds a Short Text field
    Assert: FormPreview renders an input element within 100ms

- shouldSaveSchema_toServiceTypeApi:
    Given: admin clicks Save with 2 fields defined
    Assert: saveIntakeSchema server action called with correct schema object; PUT API called once

- shouldHandleSelectFieldEnumOptions:
    Given: admin adds Select field with options ["Yes", "No"]
    Assert: serialized schema contains enum: ["Yes", "No"] for that field
```

**Coverage requirements**:
- `serializeToJsonSchema` must have 100% branch coverage (all 6 field types)
- `toCamelCase` must handle multi-word labels, numbers, special characters

---

## Implementation Constraints

- All API calls must go through `apps/web/lib/api-client.ts` or server actions
- `serializeToJsonSchema` must be a pure function in `schema-serializer.ts` — no component imports
- `toCamelCase` must strip non-alphanumeric characters and camelCase the result
- Drag-and-drop must use `@dnd-kit/sortable` — not HTML5 drag API or deprecated libraries
- Select field must show `EnumOptionsEditor` inline when `FieldType = 'select'`
- Required toggle must update the `required` array in `FieldDef` — not the JSON Schema directly
- Live preview must re-render on every `fields` state change without debounce
- `tenantId` must come from the authenticated session — never from a form field or query param
- No `console.log` — use pino for server-side logging
- No industry-specific field type labels (no "Patient Name", "Diagnosis" etc. in UI copy)

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create `src/lib/schema-serializer.test.ts`
2. Write `shouldSerializeFieldsToJsonSchema_correctly` for all 6 field types — assert failures
3. Write `shouldGenerateCamelCaseKey_fromLabel` — assert failures

### GREEN — Minimum code to pass

1. Implement `serializeToJsonSchema` and `toCamelCase` in `schema-serializer.ts`
2. Implement `FormBuilder` client component with `FieldDef[]` state
3. Implement `FieldCard`, `FieldList` (with `@dnd-kit/sortable`), `FieldTypeSelector`
4. Implement `EnumOptionsEditor` for select fields
5. Implement `FormPreview` wrapping `react-jsonschema-form`
6. Implement `saveIntakeSchema` server action
7. Build `[serviceTypeId]/page.tsx`

### REFACTOR — Quality pass

1. Add loading and error states for the save action
2. Add empty state for the field list ("No fields — click Add Field to begin")
3. Show the auto-generated `key` below the label input so admins can see what they're creating
4. Run E2E Playwright test for full add → preview → save flow

---

## Implementation Reference

### FieldDef Interface and Field Type Map

**File**: `apps/web/src/components/admin/form-builder/FormBuilder.tsx`

```typescript
// [TASK: ATOM-UI-016]
interface FieldDef {
  key: string          // camelCase auto-generated from label
  label: string
  type: 'string' | 'number' | 'boolean'
  format?: string
  enum?: string[]
  required: boolean
  placeholder?: string
}
```

### Schema Serializer

**File**: `apps/web/src/lib/schema-serializer.ts`

```typescript
// [TASK: ATOM-UI-016]
function serializeToJsonSchema(fields: FieldDef[]): JsonSchema {
  return {
    type: 'object',
    properties: Object.fromEntries(
      fields.map(f => [f.key, {
        type: f.type,
        title: f.label,
        ...(f.format && { format: f.format }),
        ...(f.enum && { enum: f.enum }),
        ...(f.placeholder && { examples: [f.placeholder] }),
      }])
    ),
    required: fields.filter(f => f.required).map(f => f.key),
  }
}
```

### Live Preview

**File**: `apps/web/src/components/admin/form-builder/FormPreview.tsx`

```typescript
// [TASK: ATOM-UI-016]
// Right panel renders react-jsonschema-form with the current schema in real time
// Uses @rjsf/core with @rjsf/validator-ajv8
import Form from '@rjsf/core'
import validator from '@rjsf/validator-ajv8'

<Form schema={serializeToJsonSchema(fields)} validator={validator} onSubmit={() => {}} />
```

### Save Action

**File**: `apps/web/src/lib/booking-actions.ts` (addition) or dedicated `form-builder-actions.ts`

```typescript
// [TASK: ATOM-UI-016]
async function saveSchema(serviceTypeId: string, schema: JsonSchema) {
  await fetch(`${API}/api/v1/tenants/${tenantId}/service-types/${serviceTypeId}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json', ...authHeader() },
    body: JSON.stringify({ intakeSchema: schema }),
  })
}
```

### Field Type to JSON Schema Mapping

| UI Type | JSON Schema output |
|---|---|
| Short text | `{"type": "string"}` |
| Long text | `{"type": "string", "format": "textarea"}` |
| Number | `{"type": "number"}` |
| Date | `{"type": "string", "format": "date"}` |
| Checkbox | `{"type": "boolean"}` |
| Select | `{"type": "string", "enum": [...options]}` |

---

## Integration Points

**Depends on**: ATOM-SERVICE-003 (ServiceType API with `intakeSchema` field and validation endpoint), ATOM-UI-015 (form builder page nested under admin portal)

**Enables**: ATOM-BOOKING-010 (saved schema used to validate `extensionData` during booking confirmation), ATOM-UI-014 (customer intake form renders from this schema)

**Cascading updates required**:
- `tasks/MASTER-TASK-LIST.md` — mark atom complete; Phase 2 UI complete gate

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/web/src/app/(admin)/forms/[serviceTypeId]/page.tsx` | New | Form builder page |
| `apps/web/src/components/admin/form-builder/FormBuilder.tsx` | New | Main builder component |
| `apps/web/src/components/admin/form-builder/FieldList.tsx` | New | Sortable field list |
| `apps/web/src/components/admin/form-builder/FieldCard.tsx` | New | Single field card |
| `apps/web/src/components/admin/form-builder/FieldTypeSelector.tsx` | New | Field type dropdown |
| `apps/web/src/components/admin/form-builder/EnumOptionsEditor.tsx` | New | Select options editor |
| `apps/web/src/components/admin/form-builder/FormPreview.tsx` | New | Live RJSF preview |
| `apps/web/src/lib/schema-serializer.ts` | New | serializeToJsonSchema pure function |
| `apps/web/src/lib/schema-serializer.test.ts` | New | Serializer unit tests |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] `mvn test` passes (unit tests)
- [ ] `mvn verify -P integration` passes (Testcontainers integration tests)
- [ ] Zero JPA queries without `tenant_id` in WHERE clause
- [ ] Zero industry-specific terms in any identifier or API path
- [ ] `@PreAuthorize` present on every new `@RestController` method
- [ ] Flyway migration exists for all schema changes
- [ ] Outbox event written within `@Transactional` scope (if booking state mutated)
- [ ] Redis cache keys invalidated (if schedule/holiday cache affected)
- [ ] ADR created or referenced (if architectural decision made)
- [ ] `serializeToJsonSchema` has 100% branch coverage (all 6 field types tested)
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated

---

*Last updated: 2026-06-18 | Feature: admin-ui | Phase: 2*
