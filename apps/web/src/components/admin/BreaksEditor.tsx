// TASK: ATOM-UI-015
// Break-window editor for a resource's weekly operating matrix.
'use client'

import type { BreakEntry } from '@/lib/types'

interface BreaksEditorProps {
  value: BreakEntry[]
  onChange: (breaks: BreakEntry[]) => void
}

const DAY_LABELS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday']

export function BreaksEditor({ value, onChange }: BreaksEditorProps) {
  const update = (index: number, patch: Partial<BreakEntry>) => {
    onChange(value.map((b, i) => (i === index ? { ...b, ...patch } : b)))
  }

  const remove = (index: number) => {
    onChange(value.filter((_, i) => i !== index))
  }

  const add = () => {
    onChange([...value, { dayOfWeek: 1, breakStart: '12:00', breakEnd: '13:00', label: '' }])
  }

  return (
    <div className="space-y-2">
      {value.length === 0 && (
        <p className="text-sm text-gray-500">No breaks defined.</p>
      )}
      {value.map((entry, index) => (
        <div key={index} className="flex flex-wrap items-center gap-2">
          <select
            aria-label="Day of week"
            value={entry.dayOfWeek}
            onChange={(e) => update(index, { dayOfWeek: Number(e.target.value) })}
            className="rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
          >
            {DAY_LABELS.map((label, i) => (
              <option key={label} value={i + 1}>
                {label}
              </option>
            ))}
          </select>
          <input
            type="time"
            aria-label="Break start"
            value={entry.breakStart}
            onChange={(e) => update(index, { breakStart: e.target.value })}
            className="rounded-lg border border-gray-300 px-2 py-1 text-sm"
          />
          <span className="text-sm text-gray-500">to</span>
          <input
            type="time"
            aria-label="Break end"
            value={entry.breakEnd}
            onChange={(e) => update(index, { breakEnd: e.target.value })}
            className="rounded-lg border border-gray-300 px-2 py-1 text-sm"
          />
          <input
            type="text"
            aria-label="Break label"
            placeholder="Label (e.g. Lunch)"
            value={entry.label ?? ''}
            onChange={(e) => update(index, { label: e.target.value })}
            className="flex-1 rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
          />
          <button
            type="button"
            onClick={() => remove(index)}
            className="rounded-lg border border-red-300 px-2 py-1 text-xs text-red-700 hover:bg-red-50"
            aria-label="Remove break"
          >
            Remove
          </button>
        </div>
      ))}
      <button
        type="button"
        onClick={add}
        className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-100"
      >
        + Add break
      </button>
    </div>
  )
}
