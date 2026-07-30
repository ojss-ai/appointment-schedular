// TASK: ATOM-UI-014
// Booking flow step 2 — service + resource selector (server component).
// Pick a service first, then a resource whose type the service allows.
import Link from 'next/link'
import { notFound } from 'next/navigation'
import { api } from '@/lib/api-client'
import { requireSession } from '@/lib/session'

interface PageProps {
  params: Promise<{ tenantSlug: string; locationId: string }>
  searchParams: Promise<{ serviceTypeId?: string }>
}

export default async function ServiceResourceSelectorPage({ params, searchParams }: PageProps) {
  const { tenantSlug, locationId } = await params
  const { serviceTypeId } = await searchParams
  const session = await requireSession()

  const [locations, serviceTypes, resources] = await Promise.all([
    api.getLocations(session.tenantId, session.token),
    api.getServiceTypes(session.tenantId, session.token),
    api.getResources(session.tenantId, locationId, session.token),
  ])

  const location = locations.content.find((l) => l.id === locationId)
  if (!location) notFound()

  const selectedService = serviceTypeId
    ? serviceTypes.content.find((s) => s.id === serviceTypeId)
    : undefined

  const eligibleResources = selectedService
    ? resources.content.filter(
        (r) =>
          r.status !== 'inactive' &&
          (selectedService.allowedResourceTypes?.length
            ? selectedService.allowedResourceTypes.includes(r.resourceType)
            : true),
      )
    : []

  return (
    <main className="mx-auto max-w-3xl px-6 py-12">
      <p className="text-sm text-gray-500">Step 2 of 3 — {location.name}</p>
      <h1 className="mt-1 text-2xl font-semibold">
        {selectedService ? 'Choose who or what to book' : 'Choose a service'}
      </h1>

      {!selectedService ? (
        serviceTypes.content.length === 0 ? (
          <p className="mt-8 rounded-lg bg-gray-100 px-4 py-6 text-sm text-gray-600">
            No services are available for booking yet.
          </p>
        ) : (
          <ul className="mt-8 space-y-3">
            {serviceTypes.content.map((service) => (
              <li key={service.id}>
                <Link
                  href={`/booking/${tenantSlug}/${locationId}?serviceTypeId=${service.id}`}
                  className="block rounded-xl border border-gray-200 bg-white p-4 hover:border-gray-900"
                >
                  <div className="flex items-center justify-between">
                    <p className="font-medium">{service.name}</p>
                    <p className="text-sm text-gray-500">{service.durationMinutes} min</p>
                  </div>
                  {service.description && (
                    <p className="mt-1 text-sm text-gray-600">{service.description}</p>
                  )}
                </Link>
              </li>
            ))}
          </ul>
        )
      ) : (
        <>
          <p className="mt-2 text-sm text-gray-600">
            Service: <span className="font-medium">{selectedService.name}</span> (
            {selectedService.durationMinutes} min) ·{' '}
            <Link href={`/booking/${tenantSlug}/${locationId}`} className="underline">
              change
            </Link>
          </p>
          {eligibleResources.length === 0 ? (
            <p className="mt-8 rounded-lg bg-gray-100 px-4 py-6 text-sm text-gray-600">
              Nothing at this location can currently provide this service.
            </p>
          ) : (
            <ul className="mt-8 grid gap-4 sm:grid-cols-2">
              {eligibleResources.map((resource) => (
                <li key={resource.id}>
                  <Link
                    href={`/booking/${tenantSlug}/${locationId}/${resource.id}?serviceTypeId=${selectedService.id}`}
                    className="block rounded-xl border border-gray-200 bg-white p-4 hover:border-gray-900"
                  >
                    <p className="font-medium">{resource.name}</p>
                    <p className="mt-1 text-xs uppercase tracking-wide text-gray-500">
                      {resource.resourceType}
                    </p>
                  </Link>
                </li>
              ))}
            </ul>
          )}
        </>
      )}

      <Link
        href={`/booking/${tenantSlug}`}
        className="mt-8 inline-block text-sm text-gray-600 underline"
      >
        ← Back to locations
      </Link>
    </main>
  )
}
