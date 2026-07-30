// TASK: ATOM-UI-014
// "My bookings" list with cancellation for the authenticated user.
import Link from 'next/link'
import { api } from '@/lib/api-client'
import { dateTimeInZone } from '@/lib/date-utils'
import { requireSession } from '@/lib/session'
import { bookingId as normalizeBookingId } from '@/lib/types'
import { CancelBookingButton } from '@/components/booking/CancelBookingButton'

const STATUS_STYLES: Record<string, string> = {
  CONFIRMED: 'bg-green-100 text-green-800',
  PENDING_HOLD: 'bg-amber-100 text-amber-800',
  CANCELLED: 'bg-gray-200 text-gray-600',
  EXPIRED: 'bg-gray-200 text-gray-600',
}

export default async function BookingsPage() {
  const session = await requireSession()
  const bookings = await api.getBookings(session.tenantId, session.token, {
    sort: 'createdAt,desc',
  })

  return (
    <main className="mx-auto max-w-3xl px-6 py-12">
      <h1 className="text-2xl font-semibold">My bookings</h1>

      {bookings.content.length === 0 ? (
        <p className="mt-8 rounded-lg bg-gray-100 px-4 py-6 text-sm text-gray-600">
          You have no bookings yet.
        </p>
      ) : (
        <ul className="mt-8 space-y-3">
          {bookings.content.map((booking) => {
            const id = normalizeBookingId(booking)
            const cancellable =
              booking.status === 'CONFIRMED' || booking.status === 'PENDING_HOLD'
            return (
              <li
                key={id}
                className="flex items-start justify-between gap-4 rounded-xl border border-gray-200 bg-white p-4"
              >
                <div>
                  <p className="font-medium">{dateTimeInZone(booking.slotStart)}</p>
                  <p className="mt-1 text-sm text-gray-600">
                    {booking.serviceTypeName ?? 'Booking'}
                    {booking.resourceName ? ` · ${booking.resourceName}` : ''}
                  </p>
                  {booking.confirmationCode && (
                    <p className="mt-1 font-mono text-xs text-gray-500">
                      {booking.confirmationCode}
                    </p>
                  )}
                </div>
                <div className="flex flex-col items-end gap-2">
                  <span
                    className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                      STATUS_STYLES[booking.status] ?? 'bg-gray-100 text-gray-700'
                    }`}
                  >
                    {booking.status}
                  </span>
                  {cancellable && <CancelBookingButton bookingId={id} />}
                </div>
              </li>
            )
          })}
        </ul>
      )}

      <Link href="/dashboard" className="mt-8 inline-block text-sm text-gray-600 underline">
        ← Back to booking home
      </Link>
    </main>
  )
}
