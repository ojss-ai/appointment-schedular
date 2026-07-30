// TASK: ATOM-UI-016
// Pure serialization helpers for the intake form builder. No component
// imports — independently unit-testable (see schema-serializer.test.ts).
import type { JsonSchema, JsonSchemaProperty } from './types'

/** Field definition state shape used by the builder. */
export interface FieldDef {
  key: string // camelCase auto-generated from label
  label: string
  type: 'string' | 'number' | 'boolean'
  format?: string // 'textarea', 'date', ...
  enum?: string[] // for select fields only
  required: boolean
  placeholder?: string
}

/** Human-facing field types offered by the builder UI. */
export type FieldType =
  | 'short-text'
  | 'long-text'
  | 'number'
  | 'date'
  | 'checkbox'
  | 'select'

export const FIELD_TYPE_LABELS: Record<FieldType, string> = {
  'short-text': 'Short text',
  'long-text': 'Long text',
  number: 'Number',
  date: 'Date',
  checkbox: 'Checkbox',
  select: 'Select',
}

/** Builder row state: a FieldDef plus UI-only concerns (stable drag id, type). */
export interface BuilderField {
  id: string // stable identity for drag-and-drop, never serialized
  label: string
  fieldType: FieldType
  required: boolean
  placeholder: string
  options: string[] // select options; ignored for other types
}

/**
 * "Chief Complaint" → "chiefComplaint". Strips non-alphanumerics and
 * camelCases the remaining words. Falls back to "field" for empty labels.
 */
export function toCamelCase(label: string): string {
  const words = label
    .replace(/[^a-zA-Z0-9]+/g, ' ')
    .trim()
    .split(/\s+/)
    .filter(Boolean)
  if (words.length === 0) return 'field'
  return words
    .map((w, i) =>
      i === 0 ? w.toLowerCase() : w.charAt(0).toUpperCase() + w.slice(1).toLowerCase(),
    )
    .join('')
}

/** Map a builder row to its FieldDef (JSON Schema-facing) shape. */
export function fieldDefFromBuilder(field: BuilderField): FieldDef {
  const base: FieldDef = {
    key: toCamelCase(field.label),
    label: field.label,
    type: 'string',
    required: field.required,
    ...(field.placeholder ? { placeholder: field.placeholder } : {}),
  }
  switch (field.fieldType) {
    case 'short-text':
      return base
    case 'long-text':
      return { ...base, format: 'textarea' }
    case 'number':
      return { ...base, type: 'number' }
    case 'date':
      return { ...base, format: 'date' }
    case 'checkbox':
      return { ...base, type: 'boolean' }
    case 'select':
      return { ...base, enum: [...field.options] }
  }
}

/** Serialize field definitions to a JSON Schema draft-07 object schema. */
export function serializeToJsonSchema(fields: FieldDef[]): JsonSchema {
  return {
    type: 'object',
    properties: Object.fromEntries(
      fields.map((f) => [
        f.key,
        {
          type: f.type,
          title: f.label,
          ...(f.format ? { format: f.format } : {}),
          ...(f.enum ? { enum: f.enum } : {}),
          ...(f.placeholder ? { examples: [f.placeholder] } : {}),
        } satisfies JsonSchemaProperty,
      ]),
    ),
    required: fields.filter((f) => f.required).map((f) => f.key),
  }
}

function fieldTypeFromProperty(prop: JsonSchemaProperty): FieldType {
  if (prop.enum) return 'select'
  if (prop.type === 'boolean') return 'checkbox'
  if (prop.type === 'number' || prop.type === 'integer') return 'number'
  if (prop.format === 'date') return 'date'
  if (prop.format === 'textarea') return 'long-text'
  return 'short-text'
}

let uidCounter = 0
function nextUid(): string {
  uidCounter += 1
  return `field-${Date.now().toString(36)}-${uidCounter}`
}

export function newBuilderField(): BuilderField {
  return {
    id: nextUid(),
    label: '',
    fieldType: 'short-text',
    required: false,
    placeholder: '',
    options: [],
  }
}

/** Load an existing intake schema back into builder rows for editing. */
export function schemaToBuilderFields(schema: JsonSchema | null | undefined): BuilderField[] {
  if (!schema || schema.type !== 'object' || !schema.properties) return []
  const required = new Set(schema.required ?? [])
  return Object.entries(schema.properties).map(([key, prop]) => ({
    id: nextUid(),
    label: prop.title ?? key,
    fieldType: fieldTypeFromProperty(prop),
    required: required.has(key),
    placeholder: prop.examples?.[0] ?? '',
    options: prop.enum ? [...prop.enum] : [],
  }))
}

/**
 * Derive an RJSF uiSchema from the intake schema: maps our textarea format
 * to the textarea widget and examples[0] to a placeholder.
 */
export function buildUiSchema(
  schema: JsonSchema | null | undefined,
): Record<string, Record<string, unknown>> {
  if (!schema?.properties) return {}
  const ui: Record<string, Record<string, unknown>> = {}
  for (const [key, prop] of Object.entries(schema.properties)) {
    const entry: Record<string, unknown> = {}
    if (prop.format === 'textarea') entry['ui:widget'] = 'textarea'
    if (prop.examples?.[0]) entry['ui:placeholder'] = prop.examples[0]
    if (Object.keys(entry).length > 0) ui[key] = entry
  }
  return ui
}
