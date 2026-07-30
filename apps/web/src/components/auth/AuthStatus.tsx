// TASK: P1-T10
'use client'

import { useEffect, useState } from 'react'

const RESEND_DELAY_MS = 60_000

interface AuthStatusProps {
  maskedIdentifier: string
  expiresAt: string // ISO timestamp
  onResend: () => void
}

/** Shows the masked destination, a 5:00 countdown, and a resend button after 60s. */
export function AuthStatus({ maskedIdentifier, expiresAt, onResend }: AuthStatusProps) {
  const [secondsLeft, setSecondsLeft] = useState(300)
  const [canResend, setCanResend] = useState(false)

  useEffect(() => {
    const target = new Date(expiresAt).getTime()
    const update = () => {
      const diff = Math.max(0, Math.floor((target - Date.now()) / 1000))
      setSecondsLeft(diff)
    }
    update()
    const tick = setInterval(update, 1000)
    const resendTimer = setTimeout(() => setCanResend(true), RESEND_DELAY_MS)
    return () => {
      clearInterval(tick)
      clearTimeout(resendTimer)
    }
  }, [expiresAt])

  const fmt = (s: number) =>
    `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`

  return (
    <div className="text-sm text-gray-600">
      <p>
        Code sent to <strong>{maskedIdentifier}</strong>
      </p>
      <p>
        Expires in <span className="font-mono">{fmt(secondsLeft)}</span>
      </p>
      {canResend && (
        <button
          type="button"
          onClick={onResend}
          className="mt-2 text-blue-600 underline"
        >
          Resend code
        </button>
      )}
    </div>
  )
}
