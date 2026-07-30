// TASK: ATOM-UI-016
// Unit tests for the pure schema serializer — 100% branch coverage across
// all six field types plus camelCase key generation.
import { describe, expect, it } from 'vitest'
import {
  fieldDefFromBuilder,
  schemaToBuilderFields,
  serializeToJsonSchema,
  toCamelCase,
  type BuilderField,
  type FieldDef,
} from './schema-serializer'

const builder = (overrides: Partial<BuilderField>): BuilderField => ({
  id: 'test-id',
  label: 'Label',
  fieldType: 'short-text',
  required: false,
  placeholder: '',
  options: [],
  ...overrides,
})

describe('toCamelCase', () => {
  it('camelCases multi-word labels', () => {
    expect(toCamelCase('Chief Complaint')).toBe('chiefComplaint')
  })

  it('handles single words, numbers and special characters', () => {
    expect(toCamelCase('Notes')).toBe('notes')
    expect(toCamelCase('Insurance Provider #2!')).toBe('insuranceProvider2')
    expect(toCamelCase('  spaced   out  label ')).toBe('spacedOutLabel')
  })

  it('falls back to "field" for empty labels', () => {
    expect(toCamelCase('')).toBe('field')
    expect(toCamelCase('!!!')).toBe('field')
  })
})

describe('fieldDefFromBuilder', () => {
  it('maps short-text to plain string', () => {
    expect(fieldDefFromBuilder(builder({ label: 'Chief Complaint' }))).toEqual({
      key: 'chiefComplaint',
      label: 'Chief Complaint',
      type: 'string',
      required: false,
    })
  })

  it('maps long-text to string + textarea format', () => {
    const def = fieldDefFromBuilder(builder({ fieldType: 'long-text' }))
    expect(def.type).toBe('string')
    expect(def.format).toBe('textarea')
  })

  it('maps number to number', () => {
    expect(fieldDefFromBuilder(builder({ fieldType: 'number' })).type).toBe('number')
  })

  it('maps date to string + date format', () => {
    const def = fieldDefFromBuilder(builder({ fieldType: 'date' }))
    expect(def.type).toBe('string')
    expect(def.format).toBe('date')
  })

  it('maps checkbox to boolean', () => {
    expect(fieldDefFromBuilder(builder({ fieldType: 'checkbox' })).type).toBe('boolean')
  })

  it('maps select to string + enum options', () => {
    const def = fieldDefFromBuilder(
      builder({ fieldType: 'select', options: ['Yes', 'No'] }),
    )
    expect(def.type).toBe('string')
    expect(def.enum).toEqual(['Yes', 'No'])
  })

  it('carries required and placeholder through', () => {
    const def = fieldDefFromBuilder(
      builder({ required: true, placeholder: 'e.g. back pain' }),
    )
    expect(def.required).toBe(true)
    expect(def.placeholder).toBe('e.g. back pain')
  })
})

describe('serializeToJsonSchema', () => {
  it('serializes fields into an object schema with a required array', () => {
    const fields: FieldDef[] = [
      { key: 'name', label: 'Name', type: 'string', required: true },
      { key: 'age', label: 'Age', type: 'number', required: false },
    ]
    expect(serializeToJsonSchema(fields)).toEqual({
      type: 'object',
      properties: {
        name: { type: 'string', title: 'Name' },
        age: { type: 'number', title: 'Age' },
      },
      required: ['name'],
    })
  })

  it('includes format, enum, and examples only when present', () => {
    const fields: FieldDef[] = [
      {
        key: 'preferredContact',
        label: 'Preferred Contact',
        type: 'string',
        enum: ['Email', 'Phone'],
        required: true,
      },
      {
        key: 'notes',
        label: 'Notes',
        type: 'string',
        format: 'textarea',
        placeholder: 'Anything else?',
        required: false,
      },
    ]
    const schema = serializeToJsonSchema(fields)
    expect(schema.properties.preferredContact.enum).toEqual(['Email', 'Phone'])
    expect(schema.properties.preferredContact.format).toBeUndefined()
    expect(schema.properties.notes.format).toBe('textarea')
    expect(schema.properties.notes.examples).toEqual(['Anything else?'])
    expect(schema.required).toEqual(['preferredContact'])
  })

  it('produces an empty schema for zero fields', () => {
    expect(serializeToJsonSchema([])).toEqual({
      type: 'object',
      properties: {},
      required: [],
    })
  })
})

describe('schemaToBuilderFields (round trip)', () => {
  it('reconstructs builder rows for every field type', () => {
    const original: BuilderField[] = [
      builder({ label: 'Chief Complaint', fieldType: 'short-text', required: true }),
      builder({ label: 'History', fieldType: 'long-text' }),
      builder({ label: 'Age', fieldType: 'number' }),
      builder({ label: 'Preferred Date', fieldType: 'date' }),
      builder({ label: 'First Visit', fieldType: 'checkbox' }),
      builder({ label: 'Contact Method', fieldType: 'select', options: ['Email', 'Phone'] }),
    ]
    const schema = serializeToJsonSchema(original.map(fieldDefFromBuilder))
    const roundTripped = schemaToBuilderFields(schema)

    expect(roundTripped.map((f) => f.fieldType)).toEqual([
      'short-text',
      'long-text',
      'number',
      'date',
      'checkbox',
      'select',
    ])
    expect(roundTripped[0].required).toBe(true)
    expect(roundTripped[5].options).toEqual(['Email', 'Phone'])
  })

  it('returns an empty list for a missing schema', () => {
    expect(schemaToBuilderFields(null)).toEqual([])
    expect(schemaToBuilderFields(undefined)).toEqual([])
  })
})
