// TASK: P1-T10
'use client'

import { useState, useTransition } from 'react'
import { requestOtp, verifyOtp } from '@/lib/auth-actions'
import { AuthStatus } from './AuthStatus'
import { OtpInput, OTP_LENGTH } from './OtpInput'

interface VerifyFormProps {
  identifier: string
  tenantSlug: string
  maskedIdentifier: string
  initialExpiresAt: string
}

export function VerifyForm({
  identifier,
  tenantSlug,
  maskedIdentifier,
  initialExpiresAt,
}: VerifyFormProps) {
  const [otp, setOtp] = useState<string[]>(Array(OTP_LENGTH).fill(''))
  const [error, setError] = useState<string | null>(null)
  const [expiresAt, setExpiresAt] = useState(initialExpiresAt)
  const [isPending, startTransition] = useTransition()

  const code = otp.join('')

  const submit = () => {
    setError(null)
    startTransition(async () => {
      // On success verifyOtp sets the HttpOnly cookie and redirects
      // server-side; we only see a return value on failure.
      const failure = await verifyOtp(identifier, tenantSlug, code)
      if (failure) {
        setError(failure.message ?? 'The code you entered is incorrect.')
        setOtp(Array(OTP_LENGTH).fill(''))
      }
    })
  }

  const resend = () => {
    setError(null)
    startTransition(async () => {
      try {
        const result = await requestOtp(identifier, tenantSlug)
        setExpiresAt(result.expiresAt)
        setOtp(Array(OTP_LENGTH).fill(''))
      } catch (err) {
        setError(err instanceof Error ? err.message : 'Could not resend the code.')
      }
    })
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault()
        submit()
      }}
      className="space-y-6"
    >
      <OtpInput value={otp} onChange={setOtp} />
      {error && (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      )}
      <AuthStatus
        maskedIdentifier={maskedIdentifier}
        expiresAt={expiresAt}
        onResend={resend}
      />
      <button
        type="submit"
        disabled={isPending || code.length < OTP_LENGTH}
        className="w-full rounded-lg bg-gray-900 px-4 py-2 text-white disabled:opacity-50"
      >
        {isPending ? 'Verifying…' : 'Verify'}
      </button>
    </form>
  )
}
