// TASK: ATOM-UI-015
// Service type create/edit form. Duration constrained to 5–480 minutes with
// client-side Zod validation (AC-06).
'use client'

import { useState } from 'react'
import Link from 'next/link'
import { useRouter } from 'next/navigation'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { saveServiceType } from '@/lib/admin-actions'
import { RESOURCE_TYPES, type ServiceType, type ServiceTypeInput } from '@/lib/types'

const serviceTypeSchema = z.object({
  name: z.string().trim().min(1, 'Name is required').max(255),
  description: z.string().trim().max(2000).optional(),
  durationMinutes: z
    .number({ invalid_type_error: 'Enter a number' })
    .int('Whole minutes only')
    .min(5, 'Minimum 5 minutes')
    .max(480, 'Maximum 480 minutes'),
  bufferBeforeMin: z
    .number({ invalid_type_error: 'Enter a number' })
    .int('Whole minutes only')
    .min(0, 'Cannot be negative')
    .max(240, 'Maximum 240 minutes'),
  bufferAfterMin: z
    .number({ invalid_type_error: 'Enter a number' })
    .int('Whole minutes only')
    .min(0, 'Cannot be negative')
    .max(240, 'Maximum 240 minutes'),
  allowedResourceTypes: z
    .array(z.enum(RESOURCE_TYPES))
    .min(1, 'Select at least one resource type'),
})

type ServiceTypeFormValues = z.infer<typeof serviceTypeSchema>

interface ServiceTypeFormProps {
  serviceType?: ServiceType
}

export function ServiceTypeForm({ serviceType }: ServiceTypeFormProps) {
  const router = useRouter()
  const [serverError, setServerError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<ServiceTypeFormValues>({
    resolver: zodResolver(serviceTypeSchema),
    defaultValues: {
      name: serviceType?.name ?? '',
      description: serviceType?.description ?? '',
      durationMinutes: serviceType?.durationMinutes ?? 30,
      bufferBeforeMin: serviceType?.bufferBeforeMin ?? 0,
      bufferAfterMin: serviceType?.bufferAfterMin ?? 0,
      allowedResourceTypes: serviceType?.allowedResourceTypes ?? ['STAFF'],
    },
  })

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null)
    const payload: ServiceTypeInput = {
      name: values.name,
      description: values.description || undefined,
      durationMinutes: values.durationMinutes,
      bufferBeforeMin: values.bufferBeforeMin,
      bufferAfterMin: values.bufferAfterMin,
      allowedResourceTypes: values.allowedResourceTypes,
      // Intake schema is managed by the form builder (ATOM-UI-016);
      // preserve it on update.
      intakeSchema: serviceType?.intakeSchema ?? null,
    }
    const result = await saveServiceType(serviceType?.id ?? null, payload)
    if (!result.ok) {
      setServerError(result.message)
      return
    }
    router.push('/admin/services')
    router.refresh()
  })

  const numberField = (
    id: 'durationMinutes' | 'bufferBeforeMin' | 'bufferAfterMin',
    label: string,
  ) => (
    <div>
      <label htmlFor={id} className="block text-sm font-medium">
        {label}
      </label>
      <input
        id={id}
        type="number"
        className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2"
        {...register(id, { valueAsNumber: true })}
      />
      {errors[id] && (
        <p className="mt-1 text-sm text-red-600">{errors[id]?.message}</p>
      )}
    </div>
  )

  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
      <div>
        <label htmlFor="name" className="block text-sm font-medium">
          Name
        </label>
        <input
          id="name"
          type="text"
          placeholder="e.g. Initial consultation"
          className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2"
          {...register('name')}
        />
        {errors.name && (
          <p className="mt-1 text-sm text-red-600">{errors.name.message}</p>
        )}
      </div>

      <div>
        <label htmlFor="description" className="block text-sm font-medium">
          Description (optional)
        </label>
        <textarea
          id="description"
          rows={3}
          className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2"
          {...register('description')}
        />
        {errors.description && (
          <p className="mt-1 text-sm text-red-600">{errors.description.message}</p>
        )}
      </div>

      <div className="grid gap-4 sm:grid-cols-3">
        {numberField('durationMinutes', 'Duration (5–480 min)')}
        {numberField('bufferBeforeMin', 'Buffer before (min)')}
        {numberField('bufferAfterMin', 'Buffer after (min)')}
      </div>

      <fieldset>
        <legend className="text-sm font-medium">Allowed resource types</legend>
        <div className="mt-2 flex gap-6">
          {RESOURCE_TYPES.map((t) => (
            <label key={t} className="flex items-center gap-2 text-sm">
              <input type="checkbox" value={t} {...register('allowedResourceTypes')} />
              {t}
            </label>
          ))}
        </div>
        {errors.allowedResourceTypes && (
          <p className="mt-1 text-sm text-red-600">
            {errors.allowedResourceTypes.message}
          </p>
        )}
      </fieldset>

      {serviceType && (
        <p className="text-sm text-gray-600">
          Intake form:{' '}
          <Link href={`/admin/forms/${serviceType.id}`} className="underline">
            open the form builder
          </Link>{' '}
          to design the questions customers answer at checkout.
        </p>
      )}

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
        {isSubmitting ? 'Saving…' : serviceType ? 'Save changes' : 'Create service type'}
      </button>
    </form>
  )
}
