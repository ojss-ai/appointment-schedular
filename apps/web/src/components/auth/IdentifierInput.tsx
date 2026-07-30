// TASK: P1-T10
'use client'

import { useRouter } from 'next/navigation'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { z } from 'zod'
import { useState } from 'react'
import { requestOtp } from '@/lib/auth-actions'

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const E164_RE = /^\+[1-9]\d{1,14}$/

const schema = z.object({
  identifier: z
    .string()
    .trim()
    .min(3, 'Enter your email or phone number')
    .refine((v) => EMAIL_RE.test(v) || E164_RE.test(v), {
      message: 'Enter a valid email or phone number in +15551234567 format',
    }),
  tenantSlug: z
    .string()
    .trim()
    .regex(/^[a-z0-9-]{3,100}$/, 'Enter a valid workspace ID'),
})

type FormValues = z.infer<typeof schema>

export function IdentifierInput() {
  const router = useRouter()
  const [serverError, setServerError] = useState<string | null>(null)
  const {
    register,
    handleSubmit,
    watch,
    formState: { errors, isSubmitting },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      identifier: '',
      tenantSlug: process.env.NEXT_PUBLIC_DEFAULT_TENANT_SLUG ?? '',
    },
  })

  const identifierValue = watch('identifier') ?? ''
  const detected = EMAIL_RE.test(identifierValue)
    ? 'email'
    : E164_RE.test(identifierValue)
      ? 'phone'
      : null

  const onSubmit = handleSubmit(async (values) => {
    setServerError(null)
    try {
      const result = await requestOtp(values.identifier, values.tenantSlug)
      const params = new URLSearchParams({
        identifier: values.identifier,
        tenantSlug: values.tenantSlug,
        masked: result.maskedIdentifier,
        expiresAt: result.expiresAt,
      })
      router.push(`/verify?${params.toString()}`)
    } catch (err) {
      setServerError(err instanceof Error ? err.message : 'Something went wrong.')
    }
  })

  return (
    <form onSubmit={onSubmit} className="space-y-4" noValidate>
      <div>
        <label htmlFor="tenantSlug" className="block text-sm font-medium">
          Workspace
        </label>
        <input
          id="tenantSlug"
          type="text"
          autoComplete="organization"
          placeholder="your-workspace"
          className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2"
          {...register('tenantSlug')}
        />
        {errors.tenantSlug && (
          <p className="mt-1 text-sm text-red-600">{errors.tenantSlug.message}</p>
        )}
      </div>

      <div>
        <label htmlFor="identifier" className="block text-sm font-medium">
          Email or phone
        </label>
        <input
          id="identifier"
          type="text"
          autoComplete="username"
          placeholder="you@example.com or +15551234567"
          className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2"
          {...register('identifier')}
        />
        {detected && (
          <p className="mt-1 text-xs text-gray-500">
            We will send your code via {detected === 'email' ? 'email' : 'SMS'}.
          </p>
        )}
        {errors.identifier && (
          <p className="mt-1 text-sm text-red-600">{errors.identifier.message}</p>
        )}
      </div>

      {serverError && <p className="text-sm text-red-600">{serverError}</p>}

      <button
        type="submit"
        disabled={isSubmitting}
        className="w-full rounded-lg bg-gray-900 px-4 py-2 text-white disabled:opacity-50"
      >
        {isSubmitting ? 'Sending…' : 'Send code'}
      </button>
    </form>
  )
}
