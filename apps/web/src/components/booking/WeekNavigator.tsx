// TASK: ATOM-UI-014
'use client'

import { addDays, startOfWeekMonday } from '@/lib/date-utils'

interface WeekNavigatorProps {
  /** Monday of the currently displayed week. */
  date: Date
  onChange: (monday: Date) => void
}

const LABEL_FMT = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric' })

export function WeekNavigator({ date, onChange }: WeekNavigatorProps) {
  const currentMonday = startOfWeekMonday(new Date())
  const isCurrentWeek = date.getTime() <= currentMonday.getTime()
  const weekEnd = addDays(date, 6)

  return (
    <div className="flex items-center justify-between">
      <button
        type="button"
        onClick={() => onChange(addDays(date, -7))}
        disabled={isCurrentWeek}
        className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-100 disabled:opacity-40"
        aria-label="Previous week"
      >
        ← Prev
      </button>
      <p className="text-sm font-medium">
        {LABEL_FMT.format(date)} – {LABEL_FMT.format(weekEnd)}
      </p>
      <button
        type="button"
        onClick={() => onChange(addDays(date, 7))}
        className="rounded-lg border border-gray-300 px-3 py-1.5 text-sm hover:bg-gray-100"
        aria-label="Next week"
      >
        Next →
      </button>
    </div>
  )
}
