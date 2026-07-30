// TASK: ATOM-UI-016
// Live preview panel: renders the current schema with react-jsonschema-form
// exactly as the customer checkout will (AC-02 / AC-04).
'use client'

import Form from '@rjsf/core'
import validator from '@rjsf/validator-ajv8'
import { buildUiSchema } from '@/lib/schema-serializer'
import type { JsonSchema } from '@/lib/types'

interface FormPreviewProps {
  schema: JsonSchema
}

export function FormPreview({ schema }: FormPreviewProps) {
  const hasFields = Object.keys(schema.properties ?? {}).length > 0

  return (
    <div className="rjsf-panel rounded-xl border border-gray-200 bg-white p-4">
      <p className="mb-3 text-xs font-semibold uppercase tracking-wide text-gray-500">
        Live preview
      </p>
      {hasFields ? (
        <Form
          schema={schema as object}
          uiSchema={buildUiSchema(schema)}
          validator={validator}
          onSubmit={() => undefined}
        >
          {/* Hide the submit button in preview */}
          <span />
        </Form>
      ) : (
        <p className="text-sm text-gray-500">
          The preview updates in real time as you add fields.
        </p>
      )}
    </div>
  )
}
