// TASK: P1-T10 / ATOM-UI-014 / ATOM-UI-015 / ATOM-UI-016
// Central API client — ALL calls to the Spring Boot backend go through here.
// No component or action may call fetch() against the API directly.
import { logger } from './logger'
import type {
  Booking,
  CancelResponse,
  ConfirmResponse,
  Holiday,
  HolidayInput,
  HoldResponse,
  JsonSchema,
  Location,
  LocationInput,
  PageResponse,
  Resource,
  ResourceInput,
  ServiceType,
  ServiceTypeInput,
  SlotAvailabilityResponse,
} from './types'

const API_BASE_URL =
  process.env.API_BASE_URL ??
  process.env.NEXT_PUBLIC_API_URL ??
  'http://localhost:8080'

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    public readonly body: unknown,
    message?: string,
  ) {
    super(message ?? `API request failed with status ${status}`)
    this.name = 'ApiError'
  }
}

export interface ApiRequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'DELETE'
  body?: unknown
  token?: string
  headers?: Record<string, string>
}

/**
 * Typed wrapper around fetch for the scheduler API. Throws ApiError for
 * non-2xx responses so callers can branch on status codes.
 */
export async function apiFetch<T>(
  path: string,
  options: ApiRequestOptions = {},
): Promise<T> {
  const { method = 'GET', body, token, headers = {} } = options

  const res = await fetch(`${API_BASE_URL}${path}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  })

  const contentType = res.headers.get('content-type') ?? ''
  const payload = contentType.includes('json')
    ? await res.json().catch(() => null)
    : await res.text().catch(() => null)

  if (!res.ok) {
    logger.warn({ path, status: res.status }, 'API request failed')
    throw new ApiError(res.status, payload)
  }
  return payload as T
}

const V1 = '/api/v1'

/**
 * Typed endpoint catalogue for docs/API-SPEC.md. Every function takes the
 * caller's JWT and the tenantId derived from the authenticated session —
 * tenant isolation is enforced by always scoping paths with that tenantId.
 * Server-side use only (server components and server actions).
 */
export const api = {
  // -- Locations -----------------------------------------------------------
  getLocations: (tenantId: string, token: string) =>
    apiFetch<PageResponse<Location>>(
      `${V1}/tenants/${tenantId}/locations?page=0&size=100`,
      { token },
    ),

  createLocation: (tenantId: string, body: LocationInput, token: string) =>
    apiFetch<Location>(`${V1}/tenants/${tenantId}/locations`, {
      method: 'POST',
      body,
      token,
    }),

  updateLocation: (
    tenantId: string,
    locationId: string,
    body: LocationInput,
    token: string,
  ) =>
    apiFetch<Location>(`${V1}/tenants/${tenantId}/locations/${locationId}`, {
      method: 'PUT',
      body,
      token,
    }),

  deleteLocation: (tenantId: string, locationId: string, token: string) =>
    apiFetch<void>(`${V1}/tenants/${tenantId}/locations/${locationId}`, {
      method: 'DELETE',
      token,
    }),

  // -- Resources -----------------------------------------------------------
  getResources: (tenantId: string, locationId: string, token: string) =>
    apiFetch<PageResponse<Resource>>(
      `${V1}/tenants/${tenantId}/locations/${locationId}/resources?page=0&size=100`,
      { token },
    ),

  createResource: (
    tenantId: string,
    locationId: string,
    body: ResourceInput,
    token: string,
  ) =>
    apiFetch<Resource>(
      `${V1}/tenants/${tenantId}/locations/${locationId}/resources`,
      { method: 'POST', body, token },
    ),

  updateResource: (
    tenantId: string,
    locationId: string,
    resourceId: string,
    body: ResourceInput,
    token: string,
  ) =>
    apiFetch<Resource>(
      `${V1}/tenants/${tenantId}/locations/${locationId}/resources/${resourceId}`,
      { method: 'PUT', body, token },
    ),

  deleteResource: (
    tenantId: string,
    locationId: string,
    resourceId: string,
    token: string,
  ) =>
    apiFetch<void>(
      `${V1}/tenants/${tenantId}/locations/${locationId}/resources/${resourceId}`,
      { method: 'DELETE', token },
    ),

  // -- Service types -------------------------------------------------------
  getServiceTypes: (tenantId: string, token: string) =>
    apiFetch<PageResponse<ServiceType>>(
      `${V1}/tenants/${tenantId}/service-types?page=0&size=100`,
      { token },
    ),

  createServiceType: (tenantId: string, body: ServiceTypeInput, token: string) =>
    apiFetch<ServiceType>(`${V1}/tenants/${tenantId}/service-types`, {
      method: 'POST',
      body,
      token,
    }),

  updateServiceType: (
    tenantId: string,
    serviceTypeId: string,
    body: Partial<ServiceTypeInput>,
    token: string,
  ) =>
    apiFetch<ServiceType>(
      `${V1}/tenants/${tenantId}/service-types/${serviceTypeId}`,
      { method: 'PUT', body, token },
    ),

  deleteServiceType: (tenantId: string, serviceTypeId: string, token: string) =>
    apiFetch<void>(`${V1}/tenants/${tenantId}/service-types/${serviceTypeId}`, {
      method: 'DELETE',
      token,
    }),

  saveIntakeSchema: (
    tenantId: string,
    serviceTypeId: string,
    intakeSchema: JsonSchema,
    token: string,
  ) =>
    apiFetch<ServiceType>(
      `${V1}/tenants/${tenantId}/service-types/${serviceTypeId}`,
      { method: 'PUT', body: { intakeSchema }, token },
    ),

  // -- Slots ---------------------------------------------------------------
  getSlots: (
    tenantId: string,
    params: {
      locationId: string
      resourceId: string
      serviceTypeId: string
      date: string
      rangeEndDate?: string
    },
    token: string,
  ) => {
    const query = new URLSearchParams({
      locationId: params.locationId,
      resourceId: params.resourceId,
      serviceTypeId: params.serviceTypeId,
      date: params.date,
      ...(params.rangeEndDate ? { rangeEndDate: params.rangeEndDate } : {}),
    })
    return apiFetch<SlotAvailabilityResponse>(
      `${V1}/tenants/${tenantId}/slots?${query.toString()}`,
      { token },
    )
  },

  // -- Bookings ------------------------------------------------------------
  createHold: (
    tenantId: string,
    body: { resourceId: string; serviceTypeId: string; slotStart: string },
    token: string,
  ) =>
    apiFetch<HoldResponse>(`${V1}/tenants/${tenantId}/bookings/hold`, {
      method: 'POST',
      body,
      token,
    }),

  confirmBooking: (
    tenantId: string,
    bookingId: string,
    extensionData: Record<string, unknown>,
    token: string,
  ) =>
    apiFetch<ConfirmResponse>(
      `${V1}/tenants/${tenantId}/bookings/${bookingId}/confirm`,
      { method: 'POST', body: { extensionData }, token },
    ),

  cancelBooking: (
    tenantId: string,
    bookingId: string,
    reason: string,
    token: string,
  ) =>
    apiFetch<CancelResponse>(
      `${V1}/tenants/${tenantId}/bookings/${bookingId}/cancel`,
      { method: 'POST', body: { reason }, token },
    ),

  getBookings: (
    tenantId: string,
    token: string,
    params: Record<string, string> = {},
  ) => {
    const query = new URLSearchParams({ page: '0', size: '50', ...params })
    return apiFetch<PageResponse<Booking>>(
      `${V1}/tenants/${tenantId}/bookings?${query.toString()}`,
      { token },
    )
  },

  getBooking: (tenantId: string, id: string, token: string) =>
    apiFetch<Booking>(`${V1}/tenants/${tenantId}/bookings/${id}`, { token }),

  // -- Holidays ------------------------------------------------------------
  getHolidays: (tenantId: string, locationId: string, token: string) =>
    apiFetch<PageResponse<Holiday>>(
      `${V1}/tenants/${tenantId}/locations/${locationId}/holidays?page=0&size=100`,
      { token },
    ),

  createHoliday: (
    tenantId: string,
    locationId: string,
    body: HolidayInput,
    token: string,
  ) =>
    apiFetch<Holiday>(
      `${V1}/tenants/${tenantId}/locations/${locationId}/holidays`,
      { method: 'POST', body, token },
    ),

  deleteHoliday: (
    tenantId: string,
    locationId: string,
    holidayId: string,
    token: string,
  ) =>
    apiFetch<void>(
      `${V1}/tenants/${tenantId}/locations/${locationId}/holidays/${holidayId}`,
      { method: 'DELETE', token },
    ),
}
