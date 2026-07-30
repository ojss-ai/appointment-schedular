// TASK: ATOM-UI-015
// Location detail: edit form, holiday management, soft-delete.
// The special segment "new" renders the create form.
import Link from 'next/link'
import { notFound } from 'next/navigation'
import { api } from '@/lib/api-client'
import { removeLocation } from '@/lib/admin-actions'
import { requireAdminSession } from '@/lib/session'
import { DeleteButton } from '@/components/admin/DeleteButton'
import { HolidayManager } from '@/components/admin/HolidayManager'
import { LocationForm } from '@/components/admin/LocationForm'

interface PageProps {
  params: Promise<{ locationId: string }>
}

export default async function LocationDetailPage({ params }: PageProps) {
  const { locationId } = await params
  const session = await requireAdminSession()

  if (locationId === 'new') {
    return (
      <main className="max-w-2xl">
        <h1 className="text-2xl font-semibold">New location</h1>
        <div className="mt-6">
          <LocationForm />
        </div>
      </main>
    )
  }

  const [locations, holidays] = await Promise.all([
    api.getLocations(session.tenantId, session.token),
    api.getHolidays(session.tenantId, locationId, session.token).catch(() => ({
      content: [],
      totalElements: 0,
      page: 0,
      size: 0,
    })),
  ])
  const location = locations.content.find((l) => l.id === locationId)
  if (!location) notFound()

  return (
    <main className="max-w-2xl">
      <div className="flex items-start justify-between">
        <h1 className="text-2xl font-semibold">{location.name}</h1>
        <DeleteButton
          label="Deactivate"
          confirmLabel="Confirm deactivate"
          onDelete={removeLocation.bind(null, locationId)}
          redirectTo="/admin/locations"
        />
      </div>

      <div className="mt-6">
        <LocationForm location={location} />
      </div>

      <section className="mt-10">
        <h2 className="text-lg font-semibold">Holidays & closures</h2>
        <p className="mb-4 mt-1 text-sm text-gray-600">
          Days when this location is closed — excluded from the operating matrix.
        </p>
        <HolidayManager locationId={locationId} holidays={holidays.content} />
      </section>

      <Link
        href="/admin/locations"
        className="mt-8 inline-block text-sm text-gray-600 underline"
      >
        ← Back to locations
      </Link>
    </main>
  )
}
