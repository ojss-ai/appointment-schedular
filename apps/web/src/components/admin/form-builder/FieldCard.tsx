// TASK: ATOM-UI-016
// A single sortable field definition card. Shows the auto-generated camelCase
// key under the label so admins can see the property name (AC-05).
'use client'

import { useSortable } from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { toCamelCase, type BuilderField } from '@/lib/schema-serializer'
import { EnumOptionsEditor } from './EnumOptionsEditor'
import { FieldTypeSelector } from './FieldTypeSelector'

interface FieldCardProps {
  field: BuilderField
  onChange: (patch: Partial<BuilderField>) => void
  onRemove: () => void
}

export function FieldCard({ field, onChange, onRemove }: FieldCardProps) {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } =
    useSortable({ id: field.id })

  return (
    <div
      ref={setNodeRef}
      style={{ transform: CSS.Transform.toString(transform), transition }}
      className={`rounded-xl border border-gray-200 bg-white p-4 ${
        isDragging ? 'opacity-60 shadow-lg' : ''
      }`}
    >
      <div className="flex items-start gap-3">
        <button
          type="button"
          aria-label="Drag to reorder"
          className="cursor-grab touch-none rounded p-1 text-gray-400 hover:bg-gray-100"
          {...attributes}
          {...listeners}
        >
          ⠿
        </button>

        <div className="flex-1 space-y-3">
          <div className="flex flex-wrap items-start gap-3">
            <div className="flex-1">
              <input
                type="text"
                aria-label="Field label"
                placeholder="Field label"
                value={field.label}
                onChange={(e) => onChange({ label: e.target.value })}
                className="w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
              />
              <p className="mt-1 font-mono text-xs text-gray-400">
                key: {toCamelCase(field.label)}
              </p>
            </div>
            <FieldTypeSelector
              value={field.fieldType}
              onChange={(fieldType) => onChange({ fieldType })}
            />
          </div>

          {field.fieldType !== 'checkbox' && field.fieldType !== 'select' && (
            <input
              type="text"
              aria-label="Placeholder"
              placeholder="Placeholder (optional)"
              value={field.placeholder}
              onChange={(e) => onChange({ placeholder: e.target.value })}
              className="w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
            />
          )}

          {field.fieldType === 'select' && (
            <EnumOptionsEditor
              options={field.options}
              onChange={(options) => onChange({ options })}
            />
          )}

          <div className="flex items-center justify-between">
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={field.required}
                onChange={(e) => onChange({ required: e.target.checked })}
              />
              Required
            </label>
            <button
              type="button"
              onClick={onRemove}
              className="rounded-lg border border-red-300 px-2 py-1 text-xs text-red-700 hover:bg-red-50"
            >
              Remove field
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
