// TASK: ATOM-UI-016
// Inline add/edit/remove editor for Select field options (AC-06).
'use client'

interface EnumOptionsEditorProps {
  options: string[]
  onChange: (options: string[]) => void
}

export function EnumOptionsEditor({ options, onChange }: EnumOptionsEditorProps) {
  const update = (index: number, value: string) => {
    onChange(options.map((o, i) => (i === index ? value : o)))
  }

  const remove = (index: number) => {
    onChange(options.filter((_, i) => i !== index))
  }

  const add = () => {
    onChange([...options, `Option ${options.length + 1}`])
  }

  return (
    <div className="space-y-1.5">
      <p className="text-xs font-medium text-gray-600">Options</p>
      {options.length === 0 && (
        <p className="text-xs text-gray-500">No options yet — add at least one.</p>
      )}
      {options.map((option, index) => (
        <div key={index} className="flex items-center gap-2">
          <input
            type="text"
            aria-label={`Option ${index + 1}`}
            value={option}
            onChange={(e) => update(index, e.target.value)}
            className="flex-1 rounded-lg border border-gray-300 px-2 py-1 text-sm"
          />
          <button
            type="button"
            onClick={() => remove(index)}
            aria-label={`Remove option ${index + 1}`}
            className="rounded-lg border border-red-300 px-2 py-0.5 text-xs text-red-700 hover:bg-red-50"
          >
            ×
          </button>
        </div>
      ))}
      <button
        type="button"
        onClick={add}
        className="rounded-lg border border-gray-300 px-2 py-1 text-xs hover:bg-gray-100"
      >
        + Add option
      </button>
    </div>
  )
}
