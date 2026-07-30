// TASK: ATOM-UI-015
// Resource list. Resources are scoped per location (API contract), so the
// page filters by a location chosen via the ?locationId= query param.
import Link from 'next/link'
import { api } from '@/lib/api-client'
import { requireAdminSession } from '@/lib/session'

interface PageProps {
  searchParams: Promise<{ locationId?: string }>
}

export default async function ResourcesPage({ searchParams }: PageProps) {
  const { locationId } = await searchParams
  const session = await requireAdminSession()
  const locations = await api.getLocations(session.tenantId, session.token)

  const selectedLocationId = locationId ?? locations.content[0]?.id
  const selectedLocation = locations.content.find((l) => l.id === selectedLocationId)
  const resources = selectedLocationId
    ? await api.getResources(session.tenantId, selectedLocationId, session.token)
    : null

  return (
    <main>
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Resources</h1>
        {selectedLocationId && (
          <Link
            href={`/admin/resources/new?locationId=${selectedLocationId}`}
            className="rounded-lg bg-gray-900 px-4 py-2 text-sm text-white"
          >
            + New resource
          </Link>
        )}
      </div>

      {locations.content.length === 0 ? (
        <p className="mt-8 rounded-lg bg-gray-100 px-4 py-6 text-sm text-gray-600">
          Create a{' '}
          <Link href="/admin/locations/new" className="underline">
            location
          </Link>{' '}
          first — resources live at a location.
        </p>
      ) : (
        <>
          <nav className="mt-4 flex flex-wrap gap-2" aria-label="Filter by location">
            {locations.content.map((location) => (
              <Link
                key={location.id}
                href={`/admin/resources?locationId=${location.id}`}
                className={`rounded-full px-3 py-1 text-sm ${
                  location.id === selectedLocationId
                    ? 'bg-gray-900 text-white'
                    : 'border border-gray-300 hover:bg-gray-100'
                }`}
              >
                {location.name}
              </Link>
            ))}
          </nav>

          {resources && resources.content.length === 0 ? (
            <p className="mt-6 rounded-lg bg-gray-100 px-4 py-6 text-sm text-gray-600">
              No resources at {selectedLocation?.name ?? 'this location'} yet.
            </p>
          ) : (
            <ul className="mt-6 space-y-3">
              {resources?.content.map((resource) => (
                <li key={resource.id}>
                  <Link
                    href={`/admin/resources/${resource.id}?locationId=${selectedLocationId}`}
                    className="flex items-center justify-between rounded-xl border border-gray-200 bg-white p-4 hover:border-gray-900"
                  >
                    <div>
                      <p className="font-medium">{resource.name}</p>
                      <p className="mt-1 text-xs uppercase tracking-wide text-gray-500">
                        {resource.resourceType}
                        {resource.schedule?.length
                          ? ` · ${resource.schedule.length} shift block(s)`
                          : ' · no schedule'}
                      </p>
                    </div>
                    <span className="text-sm text-gray-400">Edit →</span>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </main>
  )
}
