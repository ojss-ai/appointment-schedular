// TASK: ATOM-UI-015
// Resource form + schedule grid + extension editor. Segment "new" creates.
import Link from 'next/link'
import { notFound, redirect } from 'next/navigation'
import { api } from '@/lib/api-client'
import { removeResource } from '@/lib/admin-actions'
import { requireAdminSession } from '@/lib/session'
import { DeleteButton } from '@/components/admin/DeleteButton'
import { ResourceForm } from '@/components/admin/ResourceForm'

interface PageProps {
  params: Promise<{ resourceId: string }>
  searchParams: Promise<{ locationId?: string }>
}

export default async function ResourceDetailPage({ params, searchParams }: PageProps) {
  const { resourceId } = await params
  const { locationId } = await searchParams
  if (!locationId) redirect('/admin/resources')

  const session = await requireAdminSession()

  if (resourceId === 'new') {
    return (
      <main className="max-w-3xl">
        <h1 className="text-2xl font-semibold">New resource</h1>
        <div className="mt-6">
          <ResourceForm locationId={locationId} />
        </div>
      </main>
    )
  }

  const resources = await api.getResources(session.tenantId, locationId, session.token)
  const resource = resources.content.find((r) => r.id === resourceId)
  if (!resource) notFound()

  return (
    <main className="max-w-3xl">
      <div className="flex items-start justify-between">
        <h1 className="text-2xl font-semibold">{resource.name}</h1>
        <DeleteButton
          label="Deactivate"
          confirmLabel="Confirm deactivate"
          onDelete={removeResource.bind(null, locationId, resourceId)}
          redirectTo="/admin/resources"
        />
      </div>

      <div className="mt-6">
        <ResourceForm locationId={locationId} resource={resource} />
      </div>

      <Link
        href={`/admin/resources?locationId=${locationId}`}
        className="mt-8 inline-block text-sm text-gray-600 underline"
      >
        ← Back to resources
      </Link>
    </main>
  )
}
