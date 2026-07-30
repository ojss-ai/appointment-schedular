// TASK: ATOM-UI-014
// Booking confirmation page (AC-05 / AC-06): shows the confirmation code,
// slot date/time, and resource name.
import Link from 'next/link'
import { api } from '@/lib/api-client'
import { dateTimeInZone } from '@/lib/date-utils'
import { requireSession } from '@/lib/session'
import { bookingId as normalizeBookingId } from '@/lib/types'

interface PageProps {
  params: Promise<{ bookingId: string }>
}

export default async function ConfirmationPage({ params }: PageProps) {
  const { bookingId } = await params
  const session = await requireSession()
  const booking = await api.getBooking(session.tenantId, bookingId, session.token)

  return (
    <main className="mx-auto max-w-xl px-6 py-16">
      <div className="rounded-2xl border border-green-200 bg-green-50 p-8 text-center">
        <p className="text-4xl">✓</p>
        <h1 className="mt-2 text-2xl font-semibold text-green-900">Booking confirmed</h1>
        {booking.confirmationCode && (
          <p className="mt-4 text-sm text-green-900">
            Confirmation code{' '}
            <span className="font-mono text-lg font-bold">{booking.confirmationCode}</span>
          </p>
        )}
      </div>

      <dl className="mt-8 space-y-3 rounded-xl border border-gray-200 bg-white p-6 text-sm">
        <div className="flex justify-between gap-4">
          <dt className="text-gray-500">When</dt>
          <dd className="font-medium">{dateTimeInZone(booking.slotStart)}</dd>
        </div>
        <div className="flex justify-between gap-4">
          <dt className="text-gray-500">With</dt>
          <dd className="font-medium">
            {booking.resourceName ?? booking.resourceId ?? '—'}
          </dd>
        </div>
        {booking.serviceTypeName && (
          <div className="flex justify-between gap-4">
            <dt className="text-gray-500">Service</dt>
            <dd className="font-medium">{booking.serviceTypeName}</dd>
          </div>
        )}
        <div className="flex justify-between gap-4">
          <dt className="text-gray-500">Status</dt>
          <dd className="font-medium">{booking.status}</dd>
        </div>
        <div className="flex justify-between gap-4">
          <dt className="text-gray-500">Booking ID</dt>
          <dd className="font-mono text-xs">{normalizeBookingId(booking) || bookingId}</dd>
        </div>
      </dl>

      <div className="mt-8 flex gap-4 text-sm">
        <Link href="/bookings" className="underline">
          View my bookings
        </Link>
        <Link href="/dashboard" className="underline">
          Booking home
        </Link>
      </div>
    </main>
  )
}
