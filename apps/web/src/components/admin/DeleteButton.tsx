// TASK: ATOM-UI-015
// Generic delete/soft-delete button with an inline confirmation step.
// Receives a bound server action from the owning server component.
'use client'

import { useState, useTransition } from 'react'
import { useRouter } from 'next/navigation'
import type { ActionResult } from '@/lib/booking-actions'

interface DeleteButtonProps {
  label?: string
  confirmLabel?: string
  onDelete: () => Promise<ActionResult<Record<never, never>>>
  /** Navigate here after a successful delete; refreshes in place otherwise. */
  redirectTo?: string
}

export function DeleteButton({
  label = 'Delete',
  confirmLabel = 'Confirm delete',
  onDelete,
  redirectTo,
}: DeleteButtonProps) {
  const router = useRouter()
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [isPending, startTransition] = useTransition()

  const run = () => {
    setError(null)
    startTransition(async () => {
      const result = await onDelete()
      if (!result.ok) {
        setError(result.message)
        setConfirming(false)
        return
      }
      if (redirectTo) router.push(redirectTo)
      else router.refresh()
    })
  }

  return (
    <div className="inline-flex flex-col items-end gap-1">
      {confirming ? (
        <span className="inline-flex items-center gap-2">
          <button
            type="button"
            disabled={isPending}
            onClick={run}
            className="rounded-lg bg-red-600 px-3 py-1.5 text-sm text-white hover:bg-red-700 disabled:opacity-50"
          >
            {isPending ? 'Deleting…' : confirmLabel}
          </button>
          <button
            type="button"
            disabled={isPending}
            onClick={() => setConfirming(false)}
            className="text-sm text-gray-600 underline"
          >
            Cancel
          </button>
        </span>
      ) : (
        <button
          type="button"
          onClick={() => setConfirming(true)}
          className="rounded-lg border border-red-300 px-3 py-1.5 text-sm text-red-700 hover:bg-red-50"
        >
          {label}
        </button>
      )}
      {error && (
        <span role="alert" className="text-xs text-red-600">
          {error}
        </span>
      )}
    </div>
  )
}
