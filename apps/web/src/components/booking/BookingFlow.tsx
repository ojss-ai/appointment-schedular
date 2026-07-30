// TASK: ATOM-UI-014
// Client orchestrator for the booking flow: slot selection → PENDING_HOLD
// checkout (with countdown) → confirmation redirect. Handles the 409
// SLOT_UNAVAILABLE conflict inline without a page reload (AC-02) and hold
// expiry by returning to the calendar (AC-04).
'use client'

import { useState, useTransition } from 'react'
import { useRouter } from 'next/navigation'
import { useQueryClient } from '@tanstack/react-query'
import { confirmBooking, createHold } from '@/lib/booking-actions'
import { dateTimeInZone } from '@/lib/date-utils'
import type { AvailableSlot, JsonSchema } from '@/lib/types'
import { CheckoutForm } from './CheckoutForm'
import { HoldCountdown } from './HoldCountdown'
import { SlotCalendar } from './SlotCalendar'

interface BookingFlowProps {
  locationId: string
  resourceId: string
  serviceTypeId: string
  resourceName: string
  serviceTypeName: string
  timezone: string
  intakeSchema: JsonSchema | null
}

interface ActiveHold {
  bookingId: string
  holdExpiresAt: string
  slotStart: string
  slotEnd: string
}

export function BookingFlow({
  locationId,
  resourceId,
  serviceTypeId,
  resourceName,
  serviceTypeName,
  timezone,
  intakeSchema,
}: BookingFlowProps) {
  const router = useRouter()
  const queryClient = useQueryClient()
  const [hold, setHold] = useState<ActiveHold | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [isPending, startTransition] = useTransition()

  const refreshSlots = () =>
    queryClient.invalidateQueries({ queryKey: ['slots', resourceId, serviceTypeId] })

  const handleSlotSelect = (slot: AvailableSlot) => {
    setNotice(null)
    startTransition(async () => {
      const result = await createHold(resourceId, serviceTypeId, slot.startTime)
      if (!result.ok) {
        // 409 SLOT_UNAVAILABLE / HOLD_ALREADY_EXISTS: inline error, no reload.
        setNotice(result.message)
        await refreshSlots()
        return
      }
      setHold({
        bookingId: result.bookingId,
        holdExpiresAt: result.holdExpiresAt,
        slotStart: result.slotStart,
        slotEnd: result.slotEnd,
      })
    })
  }

  const handleHoldExpired = () => {
    setHold(null)
    setNotice('Session expired — please select a new slot.')
    void refreshSlots()
  }

  const handleConfirm = (extensionData: Record<string, unknown>) => {
    if (!hold) return
    setNotice(null)
    startTransition(async () => {
      const result = await confirmBooking(hold.bookingId, extensionData)
      if (!result.ok) {
        if (result.code === 'HOLD_EXPIRED') {
          handleHoldExpired()
        } else {
          setNotice(result.message)
        }
        return
      }
      router.push(`/booking/confirmation/${result.bookingId}`)
    })
  }

  return (
    <div className="space-y-6">
      {notice && (
        <p role="alert" className="rounded-lg bg-amber-50 px-4 py-3 text-sm text-amber-800">
          {notice}
        </p>
      )}

      {hold === null ? (
        <SlotCalendar
          locationId={locationId}
          resourceId={resourceId}
          serviceTypeId={serviceTypeId}
          timezone={timezone}
          disabled={isPending}
          onSelect={handleSlotSelect}
        />
      ) : (
        <div className="space-y-6">
          <div className="rounded-xl border border-gray-200 bg-white p-4">
            <div className="flex items-start justify-between gap-4">
              <div>
                <h2 className="text-lg font-semibold">{serviceTypeName}</h2>
                <p className="text-sm text-gray-600">with {resourceName}</p>
                <p className="mt-1 text-sm font-medium">
                  {dateTimeInZone(hold.slotStart, timezone)}
                </p>
              </div>
              <HoldCountdown holdExpiresAt={hold.holdExpiresAt} onExpired={handleHoldExpired} />
            </div>
          </div>

          <CheckoutForm
            intakeSchema={intakeSchema}
            submitting={isPending}
            onSubmit={handleConfirm}
          />

          <button
            type="button"
            onClick={() => {
              setHold(null)
              void refreshSlots()
            }}
            className="text-sm text-gray-600 underline hover:text-gray-900"
          >
            ← Back to slot selection
          </button>
        </div>
      )}
    </div>
  )
}
