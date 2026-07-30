// TASK: ATOM-UI-015 / ATOM-UI-016
// Server actions for the admin portal. Every action re-derives tenantId and
// the admin role from the authenticated session (AC-08) — tenantId is never
// accepted from a form field, query param, or client argument.
'use server'

import { revalidatePath } from 'next/cache'
import { api, ApiError } from './api-client'
import { logger } from './logger'
import { requireAdminSession } from './session'
import type {
  ApiErrorBody,
  HolidayInput,
  JsonSchema,
  LocationInput,
  ResourceInput,
  ServiceTypeInput,
} from './types'
import type { ActionFailure, ActionResult } from './booking-actions'

function toFailure(err: unknown, context: string): ActionFailure {
  if (err instanceof ApiError) {
    const body = (err.body ?? {}) as ApiErrorBody
    logger.warn({ context, status: err.status, code: body.code }, 'admin action failed')
    return {
      ok: false,
      code: body.code ?? `HTTP_${err.status}`,
      message: body.message ?? 'The request was rejected. Please review and retry.',
    }
  }
  logger.error({ context, err: String(err) }, 'admin action failed')
  return { ok: false, code: 'ERROR', message: 'Something went wrong. Please try again.' }
}

// ---------------------------------------------------------------------------
// Locations
// ---------------------------------------------------------------------------

export async function saveLocation(
  locationId: string | null,
  values: LocationInput,
): Promise<ActionResult<{ id: string }>> {
  const session = await requireAdminSession()
  try {
    const saved = locationId
      ? await api.updateLocation(session.tenantId, locationId, values, session.token)
      : await api.createLocation(session.tenantId, values, session.token)
    revalidatePath('/admin/locations')
    return { ok: true, id: saved?.id ?? locationId ?? '' }
  } catch (err) {
    return toFailure(err, 'saveLocation')
  }
}

export async function removeLocation(
  locationId: string,
): Promise<ActionResult<Record<never, never>>> {
  const session = await requireAdminSession()
  try {
    await api.deleteLocation(session.tenantId, locationId, session.token)
    revalidatePath('/admin/locations')
    return { ok: true }
  } catch (err) {
    return toFailure(err, 'removeLocation')
  }
}

// ---------------------------------------------------------------------------
// Resources
// ---------------------------------------------------------------------------

export async function saveResource(
  locationId: string,
  resourceId: string | null,
  values: ResourceInput,
): Promise<ActionResult<{ id: string }>> {
  const session = await requireAdminSession()
  try {
    const saved = resourceId
      ? await api.updateResource(session.tenantId, locationId, resourceId, values, session.token)
      : await api.createResource(session.tenantId, locationId, values, session.token)
    revalidatePath('/admin/resources')
    return { ok: true, id: saved?.id ?? resourceId ?? '' }
  } catch (err) {
    return toFailure(err, 'saveResource')
  }
}

export async function removeResource(
  locationId: string,
  resourceId: string,
): Promise<ActionResult<Record<never, never>>> {
  const session = await requireAdminSession()
  try {
    await api.deleteResource(session.tenantId, locationId, resourceId, session.token)
    revalidatePath('/admin/resources')
    return { ok: true }
  } catch (err) {
    return toFailure(err, 'removeResource')
  }
}

// ---------------------------------------------------------------------------
// Service types
// ---------------------------------------------------------------------------

export async function saveServiceType(
  serviceTypeId: string | null,
  values: ServiceTypeInput,
): Promise<ActionResult<{ id: string }>> {
  const session = await requireAdminSession()
  try {
    const saved = serviceTypeId
      ? await api.updateServiceType(session.tenantId, serviceTypeId, values, session.token)
      : await api.createServiceType(session.tenantId, values, session.token)
    revalidatePath('/admin/services')
    return { ok: true, id: saved?.id ?? serviceTypeId ?? '' }
  } catch (err) {
    return toFailure(err, 'saveServiceType')
  }
}

export async function removeServiceType(
  serviceTypeId: string,
): Promise<ActionResult<Record<never, never>>> {
  const session = await requireAdminSession()
  try {
    await api.deleteServiceType(session.tenantId, serviceTypeId, session.token)
    revalidatePath('/admin/services')
    return { ok: true }
  } catch (err) {
    return toFailure(err, 'removeServiceType')
  }
}

/** ATOM-UI-016: persist the designed intake schema for a service type. */
export async function saveIntakeSchema(
  serviceTypeId: string,
  schema: JsonSchema,
): Promise<ActionResult<Record<never, never>>> {
  const session = await requireAdminSession()
  try {
    await api.saveIntakeSchema(session.tenantId, serviceTypeId, schema, session.token)
    revalidatePath('/admin/services')
    revalidatePath(`/admin/forms/${serviceTypeId}`)
    return { ok: true }
  } catch (err) {
    return toFailure(err, 'saveIntakeSchema')
  }
}

// ---------------------------------------------------------------------------
// Holidays
// ---------------------------------------------------------------------------

export async function addHoliday(
  locationId: string,
  values: HolidayInput,
): Promise<ActionResult<Record<never, never>>> {
  const session = await requireAdminSession()
  try {
    await api.createHoliday(session.tenantId, locationId, values, session.token)
    revalidatePath(`/admin/locations/${locationId}`)
    return { ok: true }
  } catch (err) {
    return toFailure(err, 'addHoliday')
  }
}

export async function removeHoliday(
  locationId: string,
  holidayId: string,
): Promise<ActionResult<Record<never, never>>> {
  const session = await requireAdminSession()
  try {
    await api.deleteHoliday(session.tenantId, locationId, holidayId, session.token)
    revalidatePath(`/admin/locations/${locationId}`)
    return { ok: true }
  } catch (err) {
    return toFailure(err, 'removeHoliday')
  }
}
