// TASK: ATOM-UI-015
// Location list + create entry point.
import Link from 'next/link'
import { api } from '@/lib/api-client'
import { requireAdminSession } from '@/lib/session'

export default async function LocationsPage() {
  const session = await requireAdminSession()
  const locations = await api.getLocations(session.tenantId, session.token)

  return (
    <main>
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Locations</h1>
        <Link
          href="/admin/locations/new"
          className="rounded-lg bg-gray-900 px-4 py-2 text-sm text-white"
        >
          + New location
        </Link>
      </div>

      {locations.content.length === 0 ? (
        <p className="mt-8 rounded-lg bg-gray-100 px-4 py-6 text-sm text-gray-600">
          No locations yet — create your first branch.
        </p>
      ) : (
        <ul className="mt-6 space-y-3">
          {locations.content.map((location) => (
            <li key={location.id}>
              <Link
                href={`/admin/locations/${location.id}`}
                className="flex items-center justify-between rounded-xl border border-gray-200 bg-white p-4 hover:border-gray-900"
              >
                <div>
                  <p className="font-medium">{location.name}</p>
                  <p className="mt-1 text-sm text-gray-600">
                    {[location.address?.line1, location.address?.city]
                      .filter(Boolean)
                      .join(', ') || '—'}{' '}
                    · {location.timezone}
                  </p>
                </div>
                <span
                  className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                    location.status === 'inactive'
                      ? 'bg-gray-200 text-gray-600'
                      : 'bg-green-100 text-green-800'
                  }`}
                >
                  {location.status}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}
