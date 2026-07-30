// TASK: ATOM-UI-014 / ATOM-UI-015 / ATOM-UI-016
// Shared domain types mirroring docs/API-SPEC.md. Generic domain terms only
// (Resource / Service / Booking / Location / Tenant) — never industry terms.

export interface PageResponse<T> {
  content: T[]
  totalElements: number
  page: number
  size: number
}

export interface ApiErrorBody {
  code?: string
  message?: string
  field?: string
}

// ---------------------------------------------------------------------------
// Location
// ---------------------------------------------------------------------------

export interface Address {
  line1?: string
  line2?: string
  city?: string
  state?: string
  postalCode?: string
  countryCode?: string
}

export interface Location {
  id: string
  name: string
  address?: Address
  coordinates?: { latitude: number; longitude: number }
  timezone: string
  status: string
}

export interface LocationInput {
  name: string
  addressLine1: string
  city: string
  state?: string
  postalCode: string
  countryCode: string
  latitude?: number
  longitude?: number
  timezone: string
}

// ---------------------------------------------------------------------------
// Resource
// ---------------------------------------------------------------------------

export const RESOURCE_TYPES = ['STAFF', 'ROOM', 'EQUIPMENT'] as const
export type ResourceType = (typeof RESOURCE_TYPES)[number]

/** Weekly operating-matrix entry. dayOfWeek: 1 = Monday … 7 = Sunday. */
export interface ScheduleEntry {
  dayOfWeek: number
  startTime: string // HH:mm
  endTime: string // HH:mm
}

export interface BreakEntry {
  dayOfWeek: number
  breakStart: string // HH:mm
  breakEnd: string // HH:mm
  label?: string
}

export interface Resource {
  id: string
  name: string
  resourceType: ResourceType
  extension?: Record<string, unknown>
  schedule?: ScheduleEntry[]
  breaks?: BreakEntry[]
  status?: string
}

export interface ResourceInput {
  name: string
  resourceType: ResourceType
  extension: Record<string, unknown>
  schedule: ScheduleEntry[]
  breaks: BreakEntry[]
}

// ---------------------------------------------------------------------------
// Service type
// ---------------------------------------------------------------------------

export interface JsonSchemaProperty {
  type: string
  title?: string
  format?: string
  enum?: string[]
  examples?: string[]
}

/** JSON Schema draft-07 object schema used for tenant intake forms. */
export interface JsonSchema {
  type: 'object'
  properties: Record<string, JsonSchemaProperty>
  required?: string[]
}

export interface ServiceType {
  id: string
  name: string
  description?: string
  durationMinutes: number
  bufferBeforeMin: number
  bufferAfterMin: number
  allowedResourceTypes: ResourceType[]
  intakeSchema?: JsonSchema | null
}

export interface ServiceTypeInput {
  name: string
  description?: string
  durationMinutes: number
  bufferBeforeMin: number
  bufferAfterMin: number
  allowedResourceTypes: ResourceType[]
  intakeSchema?: JsonSchema | null
}

// ---------------------------------------------------------------------------
// Slots
// ---------------------------------------------------------------------------

export interface AvailableSlot {
  startTime: string // ISO 8601 UTC
  endTime: string
  durationMinutes: number
  available?: boolean
}

export interface SlotAvailabilityResponse {
  resourceId: string
  serviceTypeId: string
  date: string
  timezone: string
  slots: AvailableSlot[]
  computedAt: string
}

// ---------------------------------------------------------------------------
// Booking
// ---------------------------------------------------------------------------

export type BookingStatus = 'PENDING_HOLD' | 'CONFIRMED' | 'CANCELLED' | 'EXPIRED'

export interface HoldResponse {
  bookingId: string
  status: BookingStatus
  slotStart: string
  slotEnd: string
  holdExpiresAt: string
}

export interface ConfirmResponse {
  bookingId: string
  status: BookingStatus
  confirmationCode: string
  slotStart: string
  slotEnd: string
}

export interface CancelResponse {
  bookingId: string
  status: BookingStatus
  cancelledAt: string
}

export interface Booking {
  id?: string
  bookingId?: string
  status: BookingStatus
  slotStart: string
  slotEnd: string
  resourceId?: string
  resourceName?: string
  serviceTypeId?: string
  serviceTypeName?: string
  locationId?: string
  confirmationCode?: string
  createdAt?: string
}

/** Booking payloads vary between `id` and `bookingId`; normalize once. */
export function bookingId(b: Booking): string {
  return b.id ?? b.bookingId ?? ''
}

// ---------------------------------------------------------------------------
// Holidays
// ---------------------------------------------------------------------------

export interface Holiday {
  id: string
  holidayDate: string // YYYY-MM-DD
  name: string
  isRecurring: boolean
}

export interface HolidayInput {
  holidayDate: string
  name: string
  isRecurring: boolean
}
