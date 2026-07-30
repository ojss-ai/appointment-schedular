// TASK: ATOM-UI-016
// Intake form builder page (server shell around the FormBuilder client).
import Link from 'next/link'
import { notFound } from 'next/navigation'
import { api } from '@/lib/api-client'
import { requireAdminSession } from '@/lib/session'
import { FormBuilder } from '@/components/admin/form-builder/FormBuilder'

interface PageProps {
  params: Promise<{ serviceTypeId: string }>
}

export default async function FormBuilderPage({ params }: PageProps) {
  const { serviceTypeId } = await params
  const session = await requireAdminSession()

  const serviceTypes = await api.getServiceTypes(session.tenantId, session.token)
  const serviceType = serviceTypes.content.find((s) => s.id === serviceTypeId)
  if (!serviceType) notFound()

  return (
    <main>
      <h1 className="text-2xl font-semibold">Intake form — {serviceType.name}</h1>
      <p className="mt-1 text-sm text-gray-600">
        Design the questions customers answer at checkout. Saved as JSON Schema
        and validated on every booking confirmation.
      </p>

      <div className="mt-8">
        <FormBuilder
          serviceTypeId={serviceType.id}
          serviceTypeName={serviceType.name}
          initialSchema={serviceType.intakeSchema ?? null}
        />
      </div>

      <Link
        href={`/admin/services/${serviceType.id}`}
        className="mt-8 inline-block text-sm text-gray-600 underline"
      >
        ← Back to service type
      </Link>
    </main>
  )
}
