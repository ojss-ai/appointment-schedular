// TASK: ATOM-UI-014
// Booking flow step 3 — slot calendar + checkout (server shell around the
// BookingFlow client component).
import Link from 'next/link'
import { notFound, redirect } from 'next/navigation'
import { api } from '@/lib/api-client'
import { requireSession } from '@/lib/session'
import { BookingFlow } from '@/components/booking/BookingFlow'

interface PageProps {
  params: Promise<{ tenantSlug: string; locationId: string; resourceId: string }>
  searchParams: Promise<{ serviceTypeId?: string }>
}

export default async function SlotCalendarPage({ params, searchParams }: PageProps) {
  const { tenantSlug, locationId, resourceId } = await params
  const { serviceTypeId } = await searchParams
  if (!serviceTypeId) redirect(`/booking/${tenantSlug}/${locationId}`)

  const session = await requireSession()
  const [locations, serviceTypes, resources] = await Promise.all([
    api.getLocations(session.tenantId, session.token),
    api.getServiceTypes(session.tenantId, session.token),
    api.getResources(session.tenantId, locationId, session.token),
  ])

  const location = locations.content.find((l) => l.id === locationId)
  const serviceType = serviceTypes.content.find((s) => s.id === serviceTypeId)
  const resource = resources.content.find((r) => r.id === resourceId)
  if (!location || !serviceType || !resource) notFound()

  return (
    <main className="mx-auto max-w-4xl px-6 py-12">
      <p className="text-sm text-gray-500">
        Step 3 of 3 — {location.name} · {serviceType.name} · {resource.name}
      </p>
      <h1 className="mt-1 text-2xl font-semibold">Pick a time</h1>

      <div className="mt-8">
        <BookingFlow
          locationId={locationId}
          resourceId={resourceId}
          serviceTypeId={serviceType.id}
          resourceName={resource.name}
          serviceTypeName={serviceType.name}
          timezone={location.timezone}
          intakeSchema={serviceType.intakeSchema ?? null}
        />
      </div>

      <Link
        href={`/booking/${tenantSlug}/${locationId}?serviceTypeId=${serviceType.id}`}
        className="mt-8 inline-block text-sm text-gray-600 underline"
      >
        ← Back to selection
      </Link>
    </main>
  )
}
