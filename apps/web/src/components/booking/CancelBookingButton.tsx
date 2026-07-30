// TASK: ATOM-UI-014
'use client'

import { useState, useTransition } from 'react'
import { useRouter } from 'next/navigation'
import { cancelBooking } from '@/lib/booking-actions'

interface CancelBookingButtonProps {
  bookingId: string
}

export function CancelBookingButton({ bookingId }: CancelBookingButtonProps) {
  const router = useRouter()
  const [error, setError] = useState<string | null>(null)
  const [confirming, setConfirming] = useState(false)
  const [isPending, startTransition] = useTransition()

  const runCancel = () => {
    setError(null)
    startTransition(async () => {
      const result = await cancelBooking(bookingId, 'Cancelled by booking owner')
      if (!result.ok) {
        setError(result.message)
        setConfirming(false)
        return
      }
      setConfirming(false)
      router.refresh()
    })
  }

  if (confirming) {
    return (
      <span className="inline-flex items-center gap-2">
        <button
          type="button"
          disabled={isPending}
          onClick={runCancel}
          className="rounded-lg bg-red-600 px-3 py-1 text-xs text-white hover:bg-red-700 disabled:opacity-50"
        >
          {isPending ? 'Cancelling…' : 'Confirm cancel'}
        </button>
        <button
          type="button"
          disabled={isPending}
          onClick={() => setConfirming(false)}
          className="text-xs text-gray-600 underline"
        >
          Keep booking
        </button>
      </span>
    )
  }

  return (
    <span className="inline-flex flex-col items-end gap-1">
      <button
        type="button"
        onClick={() => setConfirming(true)}
        className="rounded-lg border border-red-300 px-3 py-1 text-xs text-red-700 hover:bg-red-50"
      >
        Cancel
      </button>
      {error && (
        <span role="alert" className="text-xs text-red-600">
          {error}
        </span>
      )}
    </span>
  )
}
