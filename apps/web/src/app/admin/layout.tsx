// TASK: ATOM-UI-015
// Admin portal shell. Middleware already redirects non-admins away from
// /admin/*; requireAdminSession here is defense in depth.
import Link from 'next/link'
import { requireAdminSession } from '@/lib/session'

const NAV = [
  { href: '/admin/dashboard', label: 'Dashboard' },
  { href: '/admin/locations', label: 'Locations' },
  { href: '/admin/resources', label: 'Resources' },
  { href: '/admin/services', label: 'Services' },
]

export default async function AdminLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  await requireAdminSession()

  return (
    <div className="mx-auto flex min-h-screen max-w-6xl gap-8 px-6 py-10">
      <aside className="w-44 shrink-0">
        <p className="text-xs font-semibold uppercase tracking-wide text-gray-500">
          Admin portal
        </p>
        <nav className="mt-4 space-y-1">
          {NAV.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className="block rounded-lg px-3 py-2 text-sm hover:bg-gray-100"
            >
              {item.label}
            </Link>
          ))}
        </nav>
        <Link
          href="/dashboard"
          className="mt-6 block px-3 text-xs text-gray-500 underline"
        >
          ← Booking home
        </Link>
      </aside>
      <div className="min-w-0 flex-1">{children}</div>
    </div>
  )
}
