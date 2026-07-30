// TASK: ATOM-UI-016
'use client'

import { FIELD_TYPE_LABELS, type FieldType } from '@/lib/schema-serializer'

interface FieldTypeSelectorProps {
  value: FieldType
  onChange: (type: FieldType) => void
}

export function FieldTypeSelector({ value, onChange }: FieldTypeSelectorProps) {
  return (
    <select
      aria-label="Field type"
      value={value}
      onChange={(e) => onChange(e.target.value as FieldType)}
      className="rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
    >
      {(Object.entries(FIELD_TYPE_LABELS) as [FieldType, string][]).map(
        ([type, label]) => (
          <option key={type} value={type}>
            {label}
          </option>
        ),
      )}
    </select>
  )
}
