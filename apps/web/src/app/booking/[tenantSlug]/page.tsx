// TASK: ATOM-UI-014
// Booking flow step 1 — location selector (server component). All data is
// fetched with the tenantId from the authenticated session; the slug in the
// URL is display-only and never used for API scoping (AC-08).
import Link from 'next/link'
import { api } from '@/lib/api-client'
import { requireSession } from '@/lib/session'

interface PageProps {
  params: Promise<{ tenantSlug: string }>
}

export default async function LocationSelectorPage({ params }: PageProps) {
  const { tenantSlug } = await params
  const session = await requireSession()
  const locations = await api.getLocations(session.tenantId, session.token)
  const active = locations.content.filter((l) => l.status !== 'inactive')

  return (
    <main className="mx-auto max-w-3xl px-6 py-12">
      <p className="text-sm text-gray-500">Step 1 of 3</p>
      <h1 className="mt-1 text-2xl font-semibold">Choose a location</h1>

      {active.length === 0 ? (
        <p className="mt-8 rounded-lg bg-gray-100 px-4 py-6 text-sm text-gray-600">
          No locations are available for booking yet.
        </p>
      ) : (
        <ul className="mt-8 grid gap-4 sm:grid-cols-2">
          {active.map((location) => (
            <li key={location.id}>
              <Link
                href={`/booking/${tenantSlug}/${location.id}`}
                className="block rounded-xl border border-gray-200 bg-white p-4 hover:border-gray-900"
              >
                <p className="font-medium">{location.name}</p>
                {location.address?.line1 && (
                  <p className="mt-1 text-sm text-gray-600">
                    {location.address.line1}
                    {location.address.city ? `, ${location.address.city}` : ''}
                  </p>
                )}
                <p className="mt-1 text-xs text-gray-500">{location.timezone}</p>
              </Link>
            </li>
          ))}
        </ul>
      )}

      <Link href="/dashboard" className="mt-8 inline-block text-sm text-gray-600 underline">
        ← Back to booking home
      </Link>
    </main>
  )
}
