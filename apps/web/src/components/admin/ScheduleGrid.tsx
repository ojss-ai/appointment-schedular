// TASK: ATOM-UI-015
// 7-column (Mon–Sun) × hourly-row drag-to-select weekly schedule editor.
// Selected hour blocks are serialized to ScheduleEntry[] (contiguous runs
// merged per day) for the resource create/update payload.
'use client'

import { useEffect, useMemo, useRef, useState } from 'react'
import type { ScheduleEntry } from '@/lib/types'

interface ScheduleGridProps {
  value: ScheduleEntry[]
  onChange: (entries: ScheduleEntry[]) => void
}

const DAY_LABELS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
const HOURS = Array.from({ length: 24 }, (_, h) => h)

const cellKey = (day: number, hour: number) => `${day}:${hour}`
const pad = (n: number) => String(n).padStart(2, '0')

function entriesToCells(entries: ScheduleEntry[]): Set<string> {
  const cells = new Set<string>()
  for (const entry of entries) {
    const start = Number.parseInt(entry.startTime.slice(0, 2), 10)
    const endH = Number.parseInt(entry.endTime.slice(0, 2), 10)
    const endM = Number.parseInt(entry.endTime.slice(3, 5), 10) || 0
    // "17:00" → exclusive end 17; "23:59" → 24; "00:00" → 24 (whole night).
    let endHour = endH + (endM > 0 ? 1 : 0)
    if (endHour === 0) endHour = 24
    for (let h = start; h < endHour && h < 24; h += 1) {
      cells.add(cellKey(entry.dayOfWeek, h))
    }
  }
  return cells
}

function cellsToEntries(cells: Set<string>): ScheduleEntry[] {
  const entries: ScheduleEntry[] = []
  for (let day = 1; day <= 7; day += 1) {
    const hours = HOURS.filter((h) => cells.has(cellKey(day, h)))
    let runStart: number | null = null
    for (let i = 0; i <= hours.length; i += 1) {
      const h = hours[i]
      if (runStart === null) {
        if (h !== undefined) runStart = h
        continue
      }
      const prev = hours[i - 1]
      if (h === undefined || h !== prev + 1) {
        entries.push({
          dayOfWeek: day,
          startTime: `${pad(runStart)}:00`,
          endTime: prev + 1 === 24 ? '23:59' : `${pad(prev + 1)}:00`,
        })
        runStart = h ?? null
      }
    }
  }
  return entries
}

export function ScheduleGrid({ value, onChange }: ScheduleGridProps) {
  const [cells, setCells] = useState<Set<string>>(() => entriesToCells(value))
  const dragMode = useRef<'add' | 'remove' | null>(null)

  // Commit whenever a drag finishes anywhere on the page.
  useEffect(() => {
    const stop = () => {
      if (dragMode.current !== null) {
        dragMode.current = null
        setCells((current) => {
          onChange(cellsToEntries(current))
          return current
        })
      }
    }
    window.addEventListener('pointerup', stop)
    return () => window.removeEventListener('pointerup', stop)
  }, [onChange])

  const apply = (day: number, hour: number) => {
    const key = cellKey(day, hour)
    setCells((current) => {
      const next = new Set(current)
      if (dragMode.current === 'add') next.add(key)
      else next.delete(key)
      return next
    })
  }

  const handlePointerDown = (day: number, hour: number) => {
    dragMode.current = cells.has(cellKey(day, hour)) ? 'remove' : 'add'
    apply(day, hour)
  }

  const handlePointerEnter = (day: number, hour: number) => {
    if (dragMode.current !== null) apply(day, hour)
  }

  const summary = useMemo(() => cellsToEntries(cells), [cells])

  return (
    <div className="select-none">
      <div className="grid grid-cols-8 gap-px overflow-hidden rounded-lg border border-gray-200 bg-gray-200 text-xs">
        <div className="bg-gray-50 px-1 py-1" />
        {DAY_LABELS.map((label) => (
          <div key={label} className="bg-gray-50 px-1 py-1 text-center font-medium">
            {label}
          </div>
        ))}
        {HOURS.map((hour) => (
          <div key={hour} className="contents">
            <div className="bg-gray-50 px-1 py-0.5 text-right text-[10px] text-gray-500">
              {pad(hour)}:00
            </div>
            {DAY_LABELS.map((_, i) => {
              const day = i + 1
              const selected = cells.has(cellKey(day, hour))
              return (
                <button
                  key={day}
                  type="button"
                  role="gridcell"
                  aria-pressed={selected}
                  aria-label={`${DAY_LABELS[i]} ${pad(hour)}:00`}
                  onPointerDown={(e) => {
                    e.preventDefault()
                    handlePointerDown(day, hour)
                  }}
                  onPointerEnter={() => handlePointerEnter(day, hour)}
                  className={`h-4 w-full ${
                    selected ? 'bg-gray-900' : 'bg-white hover:bg-gray-100'
                  }`}
                />
              )
            })}
          </div>
        ))}
      </div>
      <p className="mt-2 text-xs text-gray-500">
        Drag across the grid to toggle working hours.{' '}
        {summary.length === 0
          ? 'No shifts selected.'
          : summary
              .map(
                (e) => `${DAY_LABELS[e.dayOfWeek - 1]} ${e.startTime}–${e.endTime}`,
              )
              .join(' · ')}
      </p>
    </div>
  )
}
