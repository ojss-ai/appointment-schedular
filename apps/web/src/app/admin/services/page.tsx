// TASK: ATOM-UI-015
// Service type list + create entry point.
import Link from 'next/link'
import { api } from '@/lib/api-client'
import { requireAdminSession } from '@/lib/session'

export default async function ServicesPage() {
  const session = await requireAdminSession()
  const serviceTypes = await api.getServiceTypes(session.tenantId, session.token)

  return (
    <main>
      <div className="flex items-center justify-between">
        <h1 className="text-2xl font-semibold">Service types</h1>
        <Link
          href="/admin/services/new"
          className="rounded-lg bg-gray-900 px-4 py-2 text-sm text-white"
        >
          + New service type
        </Link>
      </div>

      {serviceTypes.content.length === 0 ? (
        <p className="mt-8 rounded-lg bg-gray-100 px-4 py-6 text-sm text-gray-600">
          No service types yet — define what can be booked.
        </p>
      ) : (
        <ul className="mt-6 space-y-3">
          {serviceTypes.content.map((service) => (
            <li
              key={service.id}
              className="flex items-center justify-between rounded-xl border border-gray-200 bg-white p-4"
            >
              <div>
                <Link
                  href={`/admin/services/${service.id}`}
                  className="font-medium hover:underline"
                >
                  {service.name}
                </Link>
                <p className="mt-1 text-sm text-gray-600">
                  {service.durationMinutes} min · buffer {service.bufferBeforeMin}/
                  {service.bufferAfterMin} min ·{' '}
                  {service.allowedResourceTypes?.join(', ') || 'any resource'}
                </p>
              </div>
              <div className="flex items-center gap-4 text-sm">
                <Link href={`/admin/forms/${service.id}`} className="underline">
                  Intake form
                  {service.intakeSchema &&
                  Object.keys(service.intakeSchema.properties ?? {}).length > 0
                    ? ` (${Object.keys(service.intakeSchema.properties).length})`
                    : ''}
                </Link>
                <Link href={`/admin/services/${service.id}`} className="text-gray-400">
                  Edit →
                </Link>
              </div>
            </li>
          ))}
        </ul>
      )}
    </main>
  )
}
