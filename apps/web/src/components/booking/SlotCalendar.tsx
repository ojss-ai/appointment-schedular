// TASK: ATOM-UI-014
// 7-day rolling slot calendar. Availability comes from the fetchSlots server
// action (which funnels through lib/api-client.ts) via TanStack Query with a
// 30s stale time and 30s polling interval. Week navigation changes the query
// key which triggers a fresh fetch automatically (AC-07).
'use client'

import { useMemo, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { fetchSlots } from '@/lib/booking-actions'
import { addDays, isoDateInZone, startOfWeekMonday, toISODate } from '@/lib/date-utils'
import type { AvailableSlot } from '@/lib/types'
import { DayColumn } from './DayColumn'
import { SlotSkeleton } from './SlotSkeleton'
import { WeekNavigator } from './WeekNavigator'

interface SlotCalendarProps {
  locationId: string
  resourceId: string
  serviceTypeId: string
  /** Fallback timezone until the slot response reports the location zone. */
  timezone?: string
  disabled?: boolean
  onSelect: (slot: AvailableSlot) => void
}

const DAY_LABEL_FMT = new Intl.DateTimeFormat('en-US', {
  weekday: 'short',
  month: 'short',
  day: 'numeric',
})

export function SlotCalendar({
  locationId,
  resourceId,
  serviceTypeId,
  timezone,
  disabled,
  onSelect,
}: SlotCalendarProps) {
  const [weekStart, setWeekStart] = useState(() => startOfWeekMonday(new Date()))

  const dateStr = toISODate(weekStart)
  const rangeEndDate = toISODate(addDays(weekStart, 6))

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['slots', resourceId, serviceTypeId, locationId, dateStr],
    queryFn: async () => {
      const result = await fetchSlots({
        locationId,
        resourceId,
        serviceTypeId,
        date: dateStr,
        rangeEndDate,
      })
      if (!result.ok) throw new Error(result.message)
      return result.data
    },
    staleTime: 30_000,
    refetchInterval: 30_000,
  })

  const zone = data?.timezone ?? timezone ?? 'UTC'

  const days = useMemo(() => {
    const byDate = new Map<string, AvailableSlot[]>()
    for (const slot of data?.slots ?? []) {
      if (slot.available === false) continue
      const key = isoDateInZone(slot.startTime, zone)
      const bucket = byDate.get(key) ?? []
      bucket.push(slot)
      byDate.set(key, bucket)
    }
    return Array.from({ length: 7 }, (_, i) => {
      const day = addDays(weekStart, i)
      const key = toISODate(day)
      return {
        date: key,
        label: DAY_LABEL_FMT.format(day),
        slots: (byDate.get(key) ?? []).sort((a, b) =>
          a.startTime.localeCompare(b.startTime),
        ),
      }
    })
  }, [data, weekStart, zone])

  return (
    <div className="space-y-4">
      <WeekNavigator date={weekStart} onChange={setWeekStart} />
      {isLoading ? (
        <SlotSkeleton />
      ) : isError ? (
        <p role="alert" className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">
          {error instanceof Error ? error.message : 'Could not load availability.'}
        </p>
      ) : (
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-7">
          {days.map((day) => (
            <DayColumn
              key={day.date}
              date={day.date}
              label={day.label}
              slots={day.slots}
              timezone={zone}
              disabled={disabled}
              onSelect={onSelect}
            />
          ))}
        </div>
      )}
      <p className="text-xs text-gray-500">
        Times shown in {zone}. Availability refreshes automatically every 30 seconds.
      </p>
    </div>
  )
}
