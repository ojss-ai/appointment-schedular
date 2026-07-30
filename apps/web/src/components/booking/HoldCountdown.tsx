// TASK: ATOM-UI-014
// 10-minute PENDING_HOLD countdown. Driven by (holdExpiresAt - Date.now())
// recalculated every second — never a fixed 600s counter — so it stays
// correct regardless of latency between hold creation and component mount.
'use client'

import { useEffect, useRef, useState } from 'react'

interface HoldCountdownProps {
  holdExpiresAt: string // ISO 8601 UTC
  onExpired: () => void
}

function secondsLeft(holdExpiresAt: string): number {
  return Math.max(0, Math.floor((new Date(holdExpiresAt).getTime() - Date.now()) / 1000))
}

export function HoldCountdown({ holdExpiresAt, onExpired }: HoldCountdownProps) {
  const [secs, setSecs] = useState(() => secondsLeft(holdExpiresAt))
  const onExpiredRef = useRef(onExpired)
  onExpiredRef.current = onExpired

  useEffect(() => {
    setSecs(secondsLeft(holdExpiresAt))
    const tick = setInterval(() => {
      const diff = secondsLeft(holdExpiresAt)
      setSecs(diff)
      if (diff === 0) {
        clearInterval(tick)
        onExpiredRef.current()
      }
    }, 1000)
    return () => clearInterval(tick)
  }, [holdExpiresAt])

  const fmt = (s: number) => `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`

  return (
    <div
      role="timer"
      aria-live="polite"
      className={`text-sm ${secs < 60 ? 'text-red-600' : 'text-gray-600'}`}
    >
      Slot held — session expires in{' '}
      <span className="font-mono font-bold">{fmt(secs)}</span>
    </div>
  )
}
