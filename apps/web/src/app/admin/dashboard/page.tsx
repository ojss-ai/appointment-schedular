// TASK: ATOM-UI-015
// Admin overview: configuration stats and recent bookings.
import Link from 'next/link'
import { api } from '@/lib/api-client'
import { dateTimeInZone } from '@/lib/date-utils'
import { requireAdminSession } from '@/lib/session'
import { bookingId as normalizeBookingId } from '@/lib/types'

export default async function AdminDashboardPage() {
  const session = await requireAdminSession()

  const [locations, serviceTypes, bookings] = await Promise.all([
    api.getLocations(session.tenantId, session.token),
    api.getServiceTypes(session.tenantId, session.token),
    api.getBookings(session.tenantId, session.token, { size: '10', sort: 'createdAt,desc' }),
  ])

  const resourceCounts = await Promise.all(
    locations.content
      .slice(0, 10)
      .map((l) =>
        api
          .getResources(session.tenantId, l.id, session.token)
          .then((r) => r.totalElements ?? r.content.length)
          .catch(() => 0),
      ),
  )
  const resourceTotal = resourceCounts.reduce((sum, n) => sum + n, 0)

  const stats = [
    { label: 'Locations', value: locations.totalElements ?? locations.content.length, href: '/admin/locations' },
    { label: 'Resources', value: resourceTotal, href: '/admin/resources' },
    { label: 'Service types', value: serviceTypes.totalElements ?? serviceTypes.content.length, href: '/admin/services' },
    { label: 'Bookings (recent page)', value: bookings.totalElements ?? bookings.content.length, href: '/admin/dashboard' },
  ]

  return (
    <main>
      <h1 className="text-2xl font-semibold">Dashboard</h1>

      <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map((stat) => (
          <Link
            key={stat.label}
            href={stat.href}
            className="rounded-xl border border-gray-200 bg-white p-4 hover:border-gray-900"
          >
            <p className="text-3xl font-semibold">{stat.value}</p>
            <p className="mt-1 text-sm text-gray-600">{stat.label}</p>
          </Link>
        ))}
      </div>

      <h2 className="mt-10 text-lg font-semibold">Recent bookings</h2>
      {bookings.content.length === 0 ? (
        <p className="mt-4 rounded-lg bg-gray-100 px-4 py-6 text-sm text-gray-600">
          No bookings yet.
        </p>
      ) : (
        <table className="mt-4 w-full text-left text-sm">
          <thead>
            <tr className="border-b border-gray-200 text-xs uppercase tracking-wide text-gray-500">
              <th className="py-2 pr-4">When</th>
              <th className="py-2 pr-4">Resource</th>
              <th className="py-2 pr-4">Status</th>
              <th className="py-2">Confirmation</th>
            </tr>
          </thead>
          <tbody>
            {bookings.content.map((booking) => (
              <tr key={normalizeBookingId(booking)} className="border-b border-gray-100">
                <td className="py-2 pr-4">{dateTimeInZone(booking.slotStart)}</td>
                <td className="py-2 pr-4">
                  {booking.resourceName ?? booking.resourceId ?? '—'}
                </td>
                <td className="py-2 pr-4">{booking.status}</td>
                <td className="py-2 font-mono text-xs">
                  {booking.confirmationCode ?? '—'}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </main>
  )
}
