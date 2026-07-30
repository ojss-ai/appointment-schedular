// TASK: P1-T10
import { redirect } from 'next/navigation'
import { VerifyForm } from '@/components/auth/VerifyForm'

interface VerifyPageProps {
  searchParams: Promise<{
    identifier?: string
    tenantSlug?: string
    masked?: string
    expiresAt?: string
  }>
}

export default async function VerifyPage({ searchParams }: VerifyPageProps) {
  const { identifier, tenantSlug, masked, expiresAt } = await searchParams
  if (!identifier || !tenantSlug) {
    redirect('/login')
  }
  return (
    <main className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-6">
      <h1 className="text-2xl font-semibold">Enter your code</h1>
      <div className="mt-8">
        <VerifyForm
          identifier={identifier}
          tenantSlug={tenantSlug}
          maskedIdentifier={masked ?? identifier}
          initialExpiresAt={expiresAt ?? new Date(Date.now() + 300_000).toISOString()}
        />
      </div>
    </main>
  )
}
