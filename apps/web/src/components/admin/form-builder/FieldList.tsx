// TASK: ATOM-UI-016
// Sortable field list — @dnd-kit/sortable with keyboard accessibility.
'use client'

import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import {
  SortableContext,
  arrayMove,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable'
import type { BuilderField } from '@/lib/schema-serializer'
import { FieldCard } from './FieldCard'

interface FieldListProps {
  fields: BuilderField[]
  onChange: (fields: BuilderField[]) => void
}

export function FieldList({ fields, onChange }: FieldListProps) {
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event
    if (!over || active.id === over.id) return
    const oldIndex = fields.findIndex((f) => f.id === active.id)
    const newIndex = fields.findIndex((f) => f.id === over.id)
    if (oldIndex < 0 || newIndex < 0) return
    onChange(arrayMove(fields, oldIndex, newIndex))
  }

  const patchField = (id: string, patch: Partial<BuilderField>) => {
    onChange(fields.map((f) => (f.id === id ? { ...f, ...patch } : f)))
  }

  const removeField = (id: string) => {
    onChange(fields.filter((f) => f.id !== id))
  }

  if (fields.length === 0) {
    return (
      <p className="rounded-lg bg-gray-100 px-4 py-6 text-center text-sm text-gray-600">
        No fields — click “Add field” to begin.
      </p>
    )
  }

  return (
    <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
      <SortableContext items={fields.map((f) => f.id)} strategy={verticalListSortingStrategy}>
        <div className="space-y-3">
          {fields.map((field) => (
            <FieldCard
              key={field.id}
              field={field}
              onChange={(patch) => patchField(field.id, patch)}
              onRemove={() => removeField(field.id)}
            />
          ))}
        </div>
      </SortableContext>
    </DndContext>
  )
}
