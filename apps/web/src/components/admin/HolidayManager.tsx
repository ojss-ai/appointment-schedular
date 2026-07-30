// TASK: ATOM-UI-015
// Holiday management for a location: list, add (RHF + Zod), remove.
'use client'

import { useState, useTransition } from 'react'
import { useRouter } from 'next/navigation'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { addHoliday, removeHoliday } from '@/lib/admin-actions'
import type { Holiday } from '@/lib/types'

const holidaySchema = z.object({
  holidayDate: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Pick a date'),
  name: z.string().trim().min(1, 'Name is required').max(255),
  isRecurring: z.boolean(),
})

type HolidayFormValues = z.infer<typeof holidaySchema>

interface HolidayManagerProps {
  locationId: string
  holidays: Holiday[]
}

export function HolidayManager({ locationId, holidays }: HolidayManagerProps) {
  const router = useRouter()
  const [serverError, setServerError] = useState<string | null>(null)
  const [isPending, startTransition] = useTransition()
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors, isSubmitting },
  } = useForm<HolidayFormValues>({
    resolver: zodResolver(holidaySchema),
    defaultValues: { holidayDate: '', name: '', isRecurring: false },
  })

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null)
    const result = await addHoliday(locationId, values)
    if (!result.ok) {
      setServerError(result.message)
      return
    }
    reset()
    router.refresh()
  })

  const onRemove = (holidayId: string) => {
    setServerError(null)
    startTransition(async () => {
      const result = await removeHoliday(locationId, holidayId)
      if (!result.ok) {
        setServerError(result.message)
        return
      }
      router.refresh()
    })
  }

  return (
    <div className="space-y-4">
      {holidays.length === 0 ? (
        <p className="text-sm text-gray-500">No holidays or closures defined.</p>
      ) : (
        <ul className="space-y-2">
          {holidays.map((holiday) => (
            <li
              key={holiday.id}
              className="flex items-center justify-between rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm"
            >
              <span>
                <span className="font-medium">{holiday.name}</span>{' '}
                <span className="text-gray-500">
                  {holiday.holidayDate}
                  {holiday.isRecurring ? ' · repeats yearly' : ''}
                </span>
              </span>
              <button
                type="button"
                disabled={isPending}
                onClick={() => onRemove(holiday.id)}
                className="rounded-lg border border-red-300 px-2 py-1 text-xs text-red-700 hover:bg-red-50 disabled:opacity-50"
              >
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}

      <form onSubmit={onSubmit} className="flex flex-wrap items-end gap-3" noValidate>
        <div>
          <label htmlFor="holidayDate" className="block text-sm font-medium">
            Date
          </label>
          <input
            id="holidayDate"
            type="date"
            className="mt-1 rounded-lg border border-gray-300 px-3 py-2"
            {...register('holidayDate')}
          />
          {errors.holidayDate && (
            <p className="mt-1 text-sm text-red-600">{errors.holidayDate.message}</p>
          )}
        </div>
        <div className="flex-1">
          <label htmlFor="holidayName" className="block text-sm font-medium">
            Name
          </label>
          <input
            id="holidayName"
            type="text"
            placeholder="e.g. Founders Day"
            className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2"
            {...register('name')}
          />
          {errors.name && (
            <p className="mt-1 text-sm text-red-600">{errors.name.message}</p>
          )}
        </div>
        <label className="flex items-center gap-2 pb-2 text-sm">
          <input type="checkbox" {...register('isRecurring')} />
          Repeats yearly
        </label>
        <button
          type="submit"
          disabled={isSubmitting}
          className="rounded-lg bg-gray-900 px-4 py-2 text-white disabled:opacity-50"
        >
          {isSubmitting ? 'Adding…' : 'Add holiday'}
        </button>
      </form>

      {serverError && (
        <p role="alert" className="text-sm text-red-600">
          {serverError}
        </p>
      )}
    </div>
  )
}
