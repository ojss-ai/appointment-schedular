// TASK: ATOM-UI-014
// Dynamic customer intake form: renders the service type's intakeSchema
// (JSON Schema draft-07) with react-jsonschema-form + ajv8 validation.
'use client'

import Form from '@rjsf/core'
import validator from '@rjsf/validator-ajv8'
import type { IChangeEvent } from '@rjsf/core'
import { buildUiSchema } from '@/lib/schema-serializer'
import type { JsonSchema } from '@/lib/types'

interface CheckoutFormProps {
  intakeSchema: JsonSchema | null | undefined
  submitting: boolean
  onSubmit: (extensionData: Record<string, unknown>) => void
}

const EMPTY_SCHEMA: JsonSchema = { type: 'object', properties: {} }

export function CheckoutForm({ intakeSchema, submitting, onSubmit }: CheckoutFormProps) {
  const schema = intakeSchema ?? EMPTY_SCHEMA
  const uiSchema = buildUiSchema(schema)
  const hasFields = Object.keys(schema.properties ?? {}).length > 0

  const handleSubmit = (data: IChangeEvent) => {
    onSubmit((data.formData ?? {}) as Record<string, unknown>)
  }

  return (
    <div className="rjsf-panel">
      {!hasFields && (
        <p className="text-sm text-gray-600">
          No additional details are needed — confirm to complete your booking.
        </p>
      )}
      <Form
        schema={schema as object}
        uiSchema={uiSchema}
        validator={validator}
        onSubmit={handleSubmit}
        disabled={submitting}
        noHtml5Validate
      >
        <button
          type="submit"
          disabled={submitting}
          className="mt-4 w-full rounded-lg bg-gray-900 px-4 py-2 text-white disabled:opacity-50"
        >
          {submitting ? 'Confirming…' : 'Confirm booking'}
        </button>
      </Form>
    </div>
  )
}
