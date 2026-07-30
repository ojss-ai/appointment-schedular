// TASK: ATOM-UI-014
// Server actions for the customer booking flow. Keeps the API base URL and
// the HttpOnly JWT out of the browser bundle. tenantId is ALWAYS derived from
// the authenticated session (AC-08) — never accepted from the client.
'use server'

import { revalidatePath } from 'next/cache'
import { api, ApiError } from './api-client'
import { logger } from './logger'
import { requireSession } from './session'
import type { ApiErrorBody, SlotAvailabilityResponse } from './types'

export interface ActionFailure {
  ok: false
  code: string
  message: string
}

export type ActionResult<T> = ({ ok: true } & T) | ActionFailure

const FRIENDLY_MESSAGES: Record<string, string> = {
  SLOT_UNAVAILABLE: 'That slot is no longer available — please pick another.',
  HOLD_ALREADY_EXISTS: 'You already hold this slot. Complete or abandon that checkout first.',
  HOLD_EXPIRED: 'Your hold expired — please select a new slot.',
  SLOT_OUTSIDE_OPERATING_HOURS: 'That slot is outside operating hours.',
  EXTENSION_SCHEMA_VIOLATION: 'Some answers were invalid — please review the form.',
  BOOKING_NOT_FOUND: 'This booking could not be found.',
}

function toFailure(err: unknown, context: string): ActionFailure {
  if (err instanceof ApiError) {
    const body = (err.body ?? {}) as ApiErrorBody
    const code = body.code ?? `HTTP_${err.status}`
    logger.warn({ context, code, status: err.status }, 'booking action failed')
    return {
      ok: false,
      code,
      message:
        FRIENDLY_MESSAGES[code] ?? body.message ?? 'Something went wrong. Please try again.',
    }
  }
  logger.error({ context, err: String(err) }, 'booking action failed')
  return { ok: false, code: 'ERROR', message: 'Something went wrong. Please try again.' }
}

/**
 * Slot availability for the calendar. Used as the TanStack Query queryFn from
 * the client — the query unwraps failures into thrown errors.
 */
export async function fetchSlots(params: {
  locationId: string
  resourceId: string
  serviceTypeId: string
  date: string
  rangeEndDate?: string
}): Promise<ActionResult<{ data: SlotAvailabilityResponse }>> {
  const session = await requireSession()
  try {
    const data = await api.getSlots(session.tenantId, params, session.token)
    return { ok: true, data }
  } catch (err) {
    return toFailure(err, 'fetchSlots')
  }
}

/** Reserve a slot: PENDING_HOLD for 10 minutes. */
export async function createHold(
  resourceId: string,
  serviceTypeId: string,
  slotStart: string,
): Promise<
  ActionResult<{
    bookingId: string
    holdExpiresAt: string
    slotStart: string
    slotEnd: string
  }>
> {
  const session = await requireSession()
  try {
    const hold = await api.createHold(
      session.tenantId,
      { resourceId, serviceTypeId, slotStart },
      session.token,
    )
    return {
      ok: true,
      bookingId: hold.bookingId,
      holdExpiresAt: hold.holdExpiresAt,
      slotStart: hold.slotStart,
      slotEnd: hold.slotEnd,
    }
  } catch (err) {
    return toFailure(err, 'createHold')
  }
}

/** Confirm a PENDING_HOLD with the intake form answers. */
export async function confirmBooking(
  bookingId: string,
  extensionData: Record<string, unknown>,
): Promise<
  ActionResult<{
    bookingId: string
    confirmationCode: string
    slotStart: string
    slotEnd: string
  }>
> {
  const session = await requireSession()
  try {
    const confirmed = await api.confirmBooking(
      session.tenantId,
      bookingId,
      extensionData,
      session.token,
    )
    revalidatePath('/bookings')
    return {
      ok: true,
      bookingId: confirmed.bookingId,
      confirmationCode: confirmed.confirmationCode,
      slotStart: confirmed.slotStart,
      slotEnd: confirmed.slotEnd,
    }
  } catch (err) {
    return toFailure(err, 'confirmBooking')
  }
}

/** Cancel a confirmed booking (owner or admin). */
export async function cancelBooking(
  bookingId: string,
  reason: string,
): Promise<ActionResult<{ bookingId: string; cancelledAt: string }>> {
  const session = await requireSession()
  try {
    const cancelled = await api.cancelBooking(
      session.tenantId,
      bookingId,
      reason,
      session.token,
    )
    revalidatePath('/bookings')
    return { ok: true, bookingId: cancelled.bookingId, cancelledAt: cancelled.cancelledAt }
  } catch (err) {
    return toFailure(err, 'cancelBooking')
  }
}
