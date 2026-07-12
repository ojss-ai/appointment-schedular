# Next.js 15 + React Skill — Scheduling Framework Patterns

> Reference for the Coder agent. All frontend code lives in `apps/web/`.
> Next.js 15 App Router — server components by default, client components opt-in.

---

## Core Conventions

| Concern | Tool | Notes |
|---|---|---|
| Routing | App Router (`app/`) | No `pages/` directory |
| Data fetching | Server Components + Tanstack Query | Server for initial load, TQ for polling/mutations |
| Forms | React Hook Form + Zod | All forms — no uncontrolled inputs |
| API calls | `apps/web/lib/api-client.ts` | Never `fetch()` directly in components |
| Auth | HttpOnly JWT cookie | Set by `/api/auth/login` server action |
| Tenant config forms | `react-jsonschema-form` | Dynamic form builder for tenant extension fields |
| State | Zustand (UI state) / Tanstack Query (server state) | Never store server data in local state |

---

## Directory Structure

```
apps/web/
├── app/
│   ├── layout.tsx                    ← Root layout (font, theme provider)
│   ├── [tenantSlug]/
│   │   ├── layout.tsx                ← Tenant shell (sidebar, header)
│   │   ├── bookings/
│   │   │   ├── page.tsx              ← Server component: booking list
│   │   │   ├── [bookingId]/
│   │   │   │   └── page.tsx          ← Server component: booking detail
│   │   │   └── new/
│   │   │       └── page.tsx          ← Client component: booking form
│   │   ├── slots/
│   │   │   └── page.tsx              ← Client component: slot picker (polls)
│   │   └── admin/
│   │       └── page.tsx              ← Admin panel
├── components/
│   ├── booking/
│   │   ├── BookingForm.tsx            ← Client component
│   │   ├── BookingCard.tsx            ← Server component
│   │   └── SlotPicker.tsx             ← Client component (polls availability)
│   ├── ui/                            ← shadcn/ui primitives
│   └── providers/
│       └── QueryProvider.tsx          ← TanstackQuery client wrapper
├── lib/
│   ├── api-client.ts                  ← All API calls go here
│   ├── auth.ts                        ← JWT cookie helpers
│   └── validations/
│       ├── booking.schema.ts          ← Zod schemas
│       └── slot.schema.ts
└── types/
    └── api.ts                         ← Response type definitions
```

---

## Server vs. Client Components

```tsx
// ✅ Server component (default) — no 'use client', can async/await
// app/[tenantSlug]/bookings/page.tsx
import { getBookings } from '@/lib/api-client'

export default async function BookingsPage({
  params: { tenantSlug }
}: {
  params: { tenantSlug: string }
}) {
  const bookings = await getBookings(tenantSlug)
  return (
    <ul>
      {bookings.map(b => <BookingCard key={b.id} booking={b} />)}
    </ul>
  )
}

// ✅ Client component — needs interactivity, hooks, or browser APIs
// components/booking/SlotPicker.tsx
'use client'

import { useQuery } from '@tanstack/react-query'
import { fetchAvailableSlots } from '@/lib/api-client'

export function SlotPicker({ tenantId, serviceId, resourceId }: SlotPickerProps) {
  const { data: slots, isLoading } = useQuery({
    queryKey: ['slots', tenantId, serviceId, resourceId, selectedDate],
    queryFn: () => fetchAvailableSlots({ tenantId, serviceId, resourceId, date: selectedDate }),
    refetchInterval: 30_000,  // Poll every 30s for live availability
    staleTime: 20_000,
  })
  // ...
}
```

---

## API Client Pattern

```typescript
// lib/api-client.ts — all API calls centralized here
const API_BASE = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080'

async function apiFetch<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    ...options,
    credentials: 'include',  // Send HttpOnly JWT cookie
    headers: {
      'Content-Type': 'application/json',
      ...options?.headers,
    },
  })
  if (!res.ok) {
    const error = await res.json().catch(() => ({ message: res.statusText }))
    throw new ApiError(res.status, error.message)
  }
  return res.json()
}

export const fetchAvailableSlots = (params: SlotQueryParams) =>
  apiFetch<SlotWindow[]>(
    `/api/v1/tenants/${params.tenantId}/slots/available?` +
    new URLSearchParams({
      serviceId: params.serviceId,
      resourceId: params.resourceId ?? '',
      date: params.date,
    })
  )

export const createBooking = (tenantId: string, body: CreateBookingRequest) =>
  apiFetch<BookingResponse>(`/api/v1/tenants/${tenantId}/bookings`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
```

---

## Forms — React Hook Form + Zod

```typescript
// lib/validations/booking.schema.ts
import { z } from 'zod'

export const bookingSchema = z.object({
  resourceId: z.string().uuid('Invalid resource'),
  serviceId: z.string().uuid('Invalid service'),
  startTime: z.string().datetime('Invalid date/time'),
  notes: z.string().max(500).optional(),
})

export type BookingFormValues = z.infer<typeof bookingSchema>
```

```tsx
// components/booking/BookingForm.tsx
'use client'

import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { bookingSchema, type BookingFormValues } from '@/lib/validations/booking.schema'
import { createBooking } from '@/lib/api-client'

export function BookingForm({ tenantId }: { tenantId: string }) {
  const qc = useQueryClient()
  const form = useForm<BookingFormValues>({
    resolver: zodResolver(bookingSchema),
  })

  const mutation = useMutation({
    mutationFn: (data: BookingFormValues) => createBooking(tenantId, data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['bookings', tenantId] })
      form.reset()
    },
  })

  return (
    <form onSubmit={form.handleSubmit(data => mutation.mutate(data))}>
      {/* fields */}
      <button type="submit" disabled={mutation.isPending}>
        {mutation.isPending ? 'Booking...' : 'Confirm Booking'}
      </button>
      {mutation.isError && <p className="text-red-500">{mutation.error.message}</p>}
    </form>
  )
}
```

---

## Server Actions (Auth / Mutations)

```typescript
// app/[tenantSlug]/bookings/actions.ts
'use server'

import { cookies } from 'next/headers'
import { redirect } from 'next/navigation'
import { createBooking } from '@/lib/api-client'

export async function createBookingAction(tenantId: string, formData: FormData) {
  const result = bookingSchema.safeParse(Object.fromEntries(formData))
  if (!result.success) {
    return { error: result.error.flatten() }
  }
  try {
    await createBooking(tenantId, result.data)
    redirect(`/${tenantId}/bookings`)
  } catch (e) {
    return { error: { message: 'Booking failed. Please try again.' } }
  }
}
```

---

## Tanstack Query Setup

```tsx
// components/providers/QueryProvider.tsx
'use client'

import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useState } from 'react'

export function QueryProvider({ children }: { children: React.ReactNode }) {
  const [client] = useState(() => new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: 1,
      },
    },
  }))
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}
```

---

## TypeScript Types

```typescript
// types/api.ts
export interface BookingResponse {
  id: string
  tenantId: string
  resourceId: string
  serviceId: string
  status: 'PENDING_HOLD' | 'CONFIRMED' | 'CANCELLED' | 'COMPLETED' | 'NO_SHOW'
  startTime: string    // ISO 8601
  endTime: string
  createdAt: string
}

export interface SlotWindow {
  start: string        // ISO 8601
  end: string
  available: boolean
}

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message)
  }
}
```

---

## Anti-Patterns (Never Do)

- ❌ `fetch()` directly in components — use `api-client.ts`
- ❌ `useState` for server data — use Tanstack Query
- ❌ `'use client'` on page files in `app/` — default to server, opt-in only
- ❌ Storing JWT in `localStorage` — use HttpOnly cookies only
- ❌ Skipping Zod validation on form submit — all forms use `zodResolver`
- ❌ `any` TypeScript type — use proper types from `types/api.ts`
- ❌ Direct DOM manipulation — always React state
