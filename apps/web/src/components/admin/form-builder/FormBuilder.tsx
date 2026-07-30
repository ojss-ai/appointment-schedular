// TASK: ATOM-UI-016
// Main intake form builder: field list (add / drag-reorder / remove) on the
// left, live react-jsonschema-form preview on the right. Saving serializes
// FieldDef[] → JSON Schema draft-07 and PUTs it to the ServiceType API via a
// server action (tenantId from session only — AC-08).
'use client'

import { useMemo, useState, useTransition } from 'react'
import { useRouter } from 'next/navigation'
import { saveIntakeSchema } from '@/lib/admin-actions'
import {
  fieldDefFromBuilder,
  newBuilderField,
  schemaToBuilderFields,
  serializeToJsonSchema,
  type BuilderField,
} from '@/lib/schema-serializer'
import type { JsonSchema } from '@/lib/types'
import { FieldList } from './FieldList'
import { FormPreview } from './FormPreview'

interface FormBuilderProps {
  serviceTypeId: string
  serviceTypeName: string
  initialSchema: JsonSchema | null
}

export function FormBuilder({
  serviceTypeId,
  serviceTypeName,
  initialSchema,
}: FormBuilderProps) {
  const router = useRouter()
  const [fields, setFields] = useState<BuilderField[]>(() =>
    schemaToBuilderFields(initialSchema),
  )
  const [status, setStatus] = useState<
    { kind: 'idle' } | { kind: 'saved' } | { kind: 'error'; message: string }
  >({ kind: 'idle' })
  const [isPending, startTransition] = useTransition()

  // Serialized on every fields change — the preview re-renders in real time.
  const schema = useMemo(
    () => serializeToJsonSchema(fields.map(fieldDefFromBuilder)),
    [fields],
  )

  const duplicateKeys = useMemo(() => {
    const keys = Object.keys(schema.properties)
    const labelKeys = fields.map((f) => fieldDefFromBuilder(f).key)
    return labelKeys.length !== keys.length
  }, [fields, schema])

  const handleFieldsChange = (next: BuilderField[]) => {
    setStatus({ kind: 'idle' })
    setFields(next)
  }

  const addField = () => {
    setStatus({ kind: 'idle' })
    setFields((current) => [...current, newBuilderField()])
  }

  const save = () => {
    setStatus({ kind: 'idle' })
    startTransition(async () => {
      const result = await saveIntakeSchema(serviceTypeId, schema)
      if (!result.ok) {
        setStatus({ kind: 'error', message: result.message })
        return
      }
      setStatus({ kind: 'saved' })
      router.refresh()
    })
  }

  const hasEmptyLabels = fields.some((f) => f.label.trim() === '')
  const hasEmptySelect = fields.some(
    (f) => f.fieldType === 'select' && f.options.filter((o) => o.trim()).length === 0,
  )
  const blocked = hasEmptyLabels || hasEmptySelect || duplicateKeys

  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-gray-500">
            Fields
          </h2>
          <button
            type="button"
            onClick={addField}
            className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-100"
          >
            + Add field
          </button>
        </div>

        <FieldList fields={fields} onChange={handleFieldsChange} />

        {hasEmptyLabels && (
          <p className="text-sm text-amber-700">Every field needs a label before saving.</p>
        )}
        {hasEmptySelect && (
          <p className="text-sm text-amber-700">
            Select fields need at least one option before saving.
          </p>
        )}
        {duplicateKeys && (
          <p className="text-sm text-amber-700">
            Two fields generate the same key — make their labels distinct.
          </p>
        )}

        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={save}
            disabled={isPending || blocked}
            className="rounded-lg bg-gray-900 px-4 py-2 text-white disabled:opacity-50"
          >
            {isPending ? 'Saving…' : `Save intake form for ${serviceTypeName}`}
          </button>
          {status.kind === 'saved' && (
            <span className="text-sm text-green-700">Saved — live for new bookings.</span>
          )}
        </div>
        {status.kind === 'error' && (
          <p role="alert" className="text-sm text-red-600">
            {status.message}
          </p>
        )}
      </div>

      <FormPreview schema={schema} />
    </div>
  )
}
