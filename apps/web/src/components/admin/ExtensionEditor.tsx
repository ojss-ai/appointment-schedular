// TASK: ATOM-UI-015
// JSONB extension key-value editor. Arbitrary string pairs, no type coercion
// — core business logic never reads this data (CLAUDE.md rule 6).
'use client'

export interface ExtensionEntry {
  key: string
  value: string
}

interface ExtensionEditorProps {
  value: ExtensionEntry[]
  onChange: (entries: ExtensionEntry[]) => void
}

export function ExtensionEditor({ value, onChange }: ExtensionEditorProps) {
  const update = (index: number, patch: Partial<ExtensionEntry>) => {
    onChange(value.map((e, i) => (i === index ? { ...e, ...patch } : e)))
  }

  const remove = (index: number) => {
    onChange(value.filter((_, i) => i !== index))
  }

  const add = () => {
    onChange([...value, { key: '', value: '' }])
  }

  return (
    <div className="space-y-2">
      {value.length === 0 && (
        <p className="text-sm text-gray-500">No extension data.</p>
      )}
      {value.map((entry, index) => (
        <div key={index} className="flex items-center gap-2">
          <input
            type="text"
            aria-label="Extension key"
            placeholder="key"
            value={entry.key}
            onChange={(e) => update(index, { key: e.target.value })}
            className="w-1/3 rounded-lg border border-gray-300 px-2 py-1.5 font-mono text-sm"
          />
          <input
            type="text"
            aria-label="Extension value"
            placeholder="value"
            value={entry.value}
            onChange={(e) => update(index, { value: e.target.value })}
            className="flex-1 rounded-lg border border-gray-300 px-2 py-1.5 text-sm"
          />
          <button
            type="button"
            onClick={() => remove(index)}
            className="rounded-lg border border-red-300 px-2 py-1 text-xs text-red-700 hover:bg-red-50"
            aria-label="Remove pair"
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
        + Add pair
      </button>
    </div>
  )
}
