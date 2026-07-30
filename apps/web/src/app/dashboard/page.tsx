// TASK: P1-T10 / ATOM-UI-014 / ATOM-UI-015
// Booking home — middleware redirects here only with a valid JWT cookie.
import Link from 'next/link'
import { signOut } from '@/lib/auth-actions'
import { getTenantSlug, isAdmin, requireSession } from '@/lib/session'

export default async function DashboardPage() {
  const session = await requireSession()
  const tenantSlug = await getTenantSlug()
  const admin = isAdmin(session)

  return (
    <main className="mx-auto max-w-2xl px-6 py-16">
      <h1 className="text-2xl font-semibold">Booking home</h1>
      <p className="mt-2 text-gray-600">What would you like to do?</p>

      <div className="mt-8 grid gap-4 sm:grid-cols-2">
        <Link
          href={`/booking/${tenantSlug}`}
          className="rounded-xl border border-gray-200 bg-white p-5 hover:border-gray-900"
        >
          <p className="font-medium">Book an appointment</p>
          <p className="mt-1 text-sm text-gray-600">
            Browse locations and available time slots.
          </p>
        </Link>
        <Link
          href="/bookings"
          className="rounded-xl border border-gray-200 bg-white p-5 hover:border-gray-900"
        >
          <p className="font-medium">My bookings</p>
          <p className="mt-1 text-sm text-gray-600">
            View, track, or cancel your bookings.
          </p>
        </Link>
        {admin && (
          <Link
            href="/admin/dashboard"
            className="rounded-xl border border-gray-200 bg-white p-5 hover:border-gray-900"
          >
            <p className="font-medium">Admin portal</p>
            <p className="mt-1 text-sm text-gray-600">
              Manage locations, resources, services, and intake forms.
            </p>
          </Link>
        )}
      </div>

      <form action={signOut} className="mt-10">
        <button
          type="submit"
          className="rounded-lg border border-gray-300 px-4 py-2 text-sm hover:bg-gray-100"
        >
          Sign out
        </button>
      </form>
    </main>
  )
}
