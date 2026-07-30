// TASK: ATOM-UI-015
// Location create/edit form — React Hook Form + Zod, immediate field-level
// validation (AC-06/AC-07). tenantId is never a form field (AC-08).
'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { saveLocation } from '@/lib/admin-actions'
import type { Location, LocationInput } from '@/lib/types'

const optionalCoordinate = (min: number, max: number) =>
  z.preprocess(
    (v) => (v === '' || v === null || v === undefined ? undefined : Number(v)),
    z
      .number({ invalid_type_error: 'Enter a number' })
      .min(min, `Must be ≥ ${min}`)
      .max(max, `Must be ≤ ${max}`)
      .optional(),
  )

const locationSchema = z.object({
  name: z.string().trim().min(1, 'Name is required').max(255),
  addressLine1: z.string().trim().min(1, 'Address is required').max(255),
  city: z.string().trim().min(1, 'City is required').max(100),
  state: z.string().trim().max(100).optional(),
  postalCode: z.string().trim().min(1, 'Postal code is required').max(20),
  countryCode: z
    .string()
    .trim()
    .length(2, 'Two-letter code')
    .regex(/^[A-Z]{2}$/, 'Uppercase ISO code, e.g. US'),
  timezone: z.string().trim().min(1, 'Timezone is required'),
  latitude: optionalCoordinate(-90, 90),
  longitude: optionalCoordinate(-180, 180),
})

type LocationFormValues = z.infer<typeof locationSchema>

const COMMON_TIMEZONES = [
  'America/New_York',
  'America/Chicago',
  'America/Denver',
  'America/Los_Angeles',
  'Europe/London',
  'Europe/Berlin',
  'Asia/Kathmandu',
  'Asia/Tokyo',
  'Australia/Sydney',
  'UTC',
]

interface LocationFormProps {
  location?: Location
}

export function LocationForm({ location }: LocationFormProps) {
  const router = useRouter()
  const [serverError, setServerError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LocationFormValues>({
    resolver: zodResolver(locationSchema),
    defaultValues: {
      name: location?.name ?? '',
      addressLine1: location?.address?.line1 ?? '',
      city: location?.address?.city ?? '',
      state: location?.address?.state ?? '',
      postalCode: location?.address?.postalCode ?? '',
      countryCode: location?.address?.countryCode ?? 'US',
      timezone: location?.timezone ?? 'America/New_York',
      latitude: location?.coordinates?.latitude,
      longitude: location?.coordinates?.longitude,
    },
  })

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null)
    const payload: LocationInput = {
      name: values.name,
      addressLine1: values.addressLine1,
      city: values.city,
      state: values.state || undefined,
      postalCode: values.postalCode,
      countryCode: values.countryCode,
      timezone: values.timezone,
      latitude: values.latitude,
      longitude: values.longitude,
    }
    const result = await saveLocation(location?.id ?? null, payload)
    if (!result.ok) {
      setServerError(result.message)
      return
    }
    router.push('/admin/locations')
    router.refresh()
  })

  const field = (
    id: keyof LocationFormValues,
    label: string,
    props: React.InputHTMLAttributes<HTMLInputElement> = {},
  ) => (
    <div>
      <label htmlFor={id} className="block text-sm font-medium">
        {label}
      </label>
      <input
        id={id}
        type="text"
        className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2"
        {...props}
        {...register(id)}
      />
      {errors[id] && (
        <p className="mt-1 text-sm text-red-600">{errors[id]?.message as string}</p>
      )}
    </div>
  )

  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
      {field('name', 'Name', { placeholder: 'Downtown branch' })}
      {field('addressLine1', 'Address line 1', { placeholder: '123 Main St' })}
      <div className="grid gap-4 sm:grid-cols-2">
        {field('city', 'City')}
        {field('state', 'State / region (optional)')}
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        {field('postalCode', 'Postal code')}
        {field('countryCode', 'Country code', { placeholder: 'US', maxLength: 2 })}
      </div>
      <div>
        <label htmlFor="timezone" className="block text-sm font-medium">
          Timezone (IANA)
        </label>
        <input
          id="timezone"
          type="text"
          list="timezone-options"
          className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2"
          {...register('timezone')}
        />
        <datalist id="timezone-options">
          {COMMON_TIMEZONES.map((tz) => (
            <option key={tz} value={tz} />
          ))}
        </datalist>
        {errors.timezone && (
          <p className="mt-1 text-sm text-red-600">{errors.timezone.message}</p>
        )}
      </div>
      <div className="grid gap-4 sm:grid-cols-2">
        {field('latitude', 'Latitude (optional)', { inputMode: 'decimal' })}
        {field('longitude', 'Longitude (optional)', { inputMode: 'decimal' })}
      </div>

      {serverError && (
        <p role="alert" className="text-sm text-red-600">
          {serverError}
        </p>
      )}

      <button
        type="submit"
        disabled={isSubmitting}
        className="rounded-lg bg-gray-900 px-4 py-2 text-white disabled:opacity-50"
      >
        {isSubmitting ? 'Saving…' : location ? 'Save changes' : 'Create location'}
      </button>
    </form>
  )
}
