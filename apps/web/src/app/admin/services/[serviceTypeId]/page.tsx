// TASK: ATOM-UI-015
// Service type form. Segment "new" creates; otherwise edit + delete.
import Link from 'next/link'
import { notFound } from 'next/navigation'
import { api } from '@/lib/api-client'
import { removeServiceType } from '@/lib/admin-actions'
import { requireAdminSession } from '@/lib/session'
import { DeleteButton } from '@/components/admin/DeleteButton'
import { ServiceTypeForm } from '@/components/admin/ServiceTypeForm'

interface PageProps {
  params: Promise<{ serviceTypeId: string }>
}

export default async function ServiceTypeDetailPage({ params }: PageProps) {
  const { serviceTypeId } = await params
  const session = await requireAdminSession()

  if (serviceTypeId === 'new') {
    return (
      <main className="max-w-2xl">
        <h1 className="text-2xl font-semibold">New service type</h1>
        <div className="mt-6">
          <ServiceTypeForm />
        </div>
      </main>
    )
  }

  const serviceTypes = await api.getServiceTypes(session.tenantId, session.token)
  const serviceType = serviceTypes.content.find((s) => s.id === serviceTypeId)
  if (!serviceType) notFound()

  return (
    <main className="max-w-2xl">
      <div className="flex items-start justify-between">
        <h1 className="text-2xl font-semibold">{serviceType.name}</h1>
        <DeleteButton
          onDelete={removeServiceType.bind(null, serviceTypeId)}
          redirectTo="/admin/services"
        />
      </div>

      <div className="mt-6">
        <ServiceTypeForm serviceType={serviceType} />
      </div>

      <Link
        href="/admin/services"
        className="mt-8 inline-block text-sm text-gray-600 underline"
      >
        ← Back to service types
      </Link>
    </main>
  )
}
