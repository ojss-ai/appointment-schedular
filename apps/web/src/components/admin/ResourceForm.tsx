// TASK: ATOM-UI-015
// Resource create/edit form: RHF + Zod scalars, drag-to-select ScheduleGrid,
// BreaksEditor, and the JSONB ExtensionEditor (AC-02 / AC-05).
'use client'

import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { saveResource } from '@/lib/admin-actions'
import { RESOURCE_TYPES, type Resource, type ResourceInput } from '@/lib/types'
import { BreaksEditor } from './BreaksEditor'
import { ExtensionEditor, type ExtensionEntry } from './ExtensionEditor'
import { ScheduleGrid } from './ScheduleGrid'

const TIME_RE = /^([01]\d|2[0-3]):[0-5]\d$/

const scheduleEntrySchema = z.object({
  dayOfWeek: z.number().int().min(1).max(7),
  startTime: z.string().regex(TIME_RE, 'HH:mm'),
  endTime: z.string().regex(TIME_RE, 'HH:mm'),
})

const breakEntrySchema = z.object({
  dayOfWeek: z.number().int().min(1).max(7),
  breakStart: z.string().regex(TIME_RE, 'HH:mm'),
  breakEnd: z.string().regex(TIME_RE, 'HH:mm'),
  label: z.string().max(100).optional(),
})

const extensionEntrySchema = z.object({
  key: z.string().trim().min(1, 'Key is required'),
  value: z.string(),
})

const resourceSchema = z.object({
  name: z.string().trim().min(1, 'Name is required').max(255),
  resourceType: z.enum(RESOURCE_TYPES),
  schedule: z.array(scheduleEntrySchema),
  breaks: z.array(breakEntrySchema),
  extension: z.array(extensionEntrySchema),
})

type ResourceFormValues = z.infer<typeof resourceSchema>

interface ResourceFormProps {
  locationId: string
  resource?: Resource
}

function extensionToEntries(extension: Record<string, unknown> | undefined): ExtensionEntry[] {
  return Object.entries(extension ?? {}).map(([key, value]) => ({
    key,
    value: typeof value === 'string' ? value : JSON.stringify(value),
  }))
}

export function ResourceForm({ locationId, resource }: ResourceFormProps) {
  const router = useRouter()
  const [serverError, setServerError] = useState<string | null>(null)
  const {
    register,
    control,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<ResourceFormValues>({
    resolver: zodResolver(resourceSchema),
    defaultValues: {
      name: resource?.name ?? '',
      resourceType: resource?.resourceType ?? 'STAFF',
      schedule: resource?.schedule ?? [],
      breaks: resource?.breaks ?? [],
      extension: extensionToEntries(resource?.extension),
    },
  })

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null)
    const payload: ResourceInput = {
      name: values.name,
      resourceType: values.resourceType,
      schedule: values.schedule,
      breaks: values.breaks.map((b) => ({ ...b, label: b.label || undefined })),
      extension: Object.fromEntries(values.extension.map((e) => [e.key, e.value])),
    }
    const result = await saveResource(locationId, resource?.id ?? null, payload)
    if (!result.ok) {
      setServerError(result.message)
      return
    }
    router.push('/admin/resources')
    router.refresh()
  })

  return (
    <form onSubmit={onSubmit} className="space-y-6" noValidate>
      <div className="grid gap-4 sm:grid-cols-2">
        <div>
          <label htmlFor="name" className="block text-sm font-medium">
            Name
          </label>
          <input
            id="name"
            type="text"
            placeholder="e.g. Suite 2 or A. Sharma"
            className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2"
            {...register('name')}
          />
          {errors.name && (
            <p className="mt-1 text-sm text-red-600">{errors.name.message}</p>
          )}
        </div>
        <div>
          <label htmlFor="resourceType" className="block text-sm font-medium">
            Resource type
          </label>
          <select
            id="resourceType"
            className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2"
            {...register('resourceType')}
          >
            {RESOURCE_TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
          {errors.resourceType && (
            <p className="mt-1 text-sm text-red-600">{errors.resourceType.message}</p>
          )}
        </div>
      </div>

      <section>
        <h2 className="text-sm font-medium">Weekly shift schedule</h2>
        <p className="mb-2 text-xs text-gray-500">
          The operating matrix: base shifts minus breaks and holidays.
        </p>
        <Controller
          control={control}
          name="schedule"
          render={({ field }) => (
            <ScheduleGrid value={field.value} onChange={field.onChange} />
          )}
        />
      </section>

      <section>
        <h2 className="mb-2 text-sm font-medium">Breaks</h2>
        <Controller
          control={control}
          name="breaks"
          render={({ field }) => (
            <BreaksEditor value={field.value} onChange={field.onChange} />
          )}
        />
      </section>

      <section>
        <h2 className="mb-2 text-sm font-medium">Extension data (JSONB)</h2>
        <Controller
          control={control}
          name="extension"
          render={({ field }) => (
            <ExtensionEditor value={field.value} onChange={field.onChange} />
          )}
        />
        {errors.extension && (
          <p className="mt-1 text-sm text-red-600">
            Extension keys must not be empty.
          </p>
        )}
      </section>

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
        {isSubmitting ? 'Saving…' : resource ? 'Save changes' : 'Create resource'}
      </button>
    </form>
  )
}
