// TASK: ATOM-UI-014
'use client'

import { timeInZone } from '@/lib/date-utils'
import type { AvailableSlot } from '@/lib/types'

interface DayColumnProps {
  /** YYYY-MM-DD in the location's timezone. */
  date: string
  label: string
  slots: AvailableSlot[]
  timezone: string
  disabled?: boolean
  onSelect: (slot: AvailableSlot) => void
}

export function DayColumn({ date, label, slots, timezone, disabled, onSelect }: DayColumnProps) {
  return (
    <div className="space-y-2" data-date={date}>
      <p className="text-center text-sm font-medium text-gray-700">{label}</p>
      {slots.length === 0 ? (
        <p className="rounded-lg bg-gray-100 px-2 py-3 text-center text-xs text-gray-500">
          No availability
        </p>
      ) : (
        slots.map((slot) => (
          <button
            key={slot.startTime}
            type="button"
            disabled={disabled}
            onClick={() => onSelect(slot)}
            className="w-full rounded-lg border border-gray-300 px-2 py-1.5 text-sm hover:border-gray-900 hover:bg-gray-900 hover:text-white disabled:opacity-50"
          >
            {timeInZone(slot.startTime, timezone)}
          </button>
        ))
      )}
    </div>
  )
}
