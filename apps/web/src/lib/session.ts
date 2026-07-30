// TASK: ATOM-UI-014 / ATOM-UI-015
// Server-side session helper. Decodes the HttpOnly JWT cookie so that server
// components and server actions derive tenantId / userId / roles from the
// authenticated session — NEVER from user input (AC-08 tenant isolation).
import { cookies } from 'next/headers'
import { redirect } from 'next/navigation'
import { jwtVerify } from 'jose'

export const AUTH_COOKIE = 'scheduler_token'
export const TENANT_SLUG_COOKIE = 'scheduler_tenant_slug'

const ADMIN_ROLES = ['admin', 'super_admin', 'ROLE_ADMIN']

export interface Session {
  token: string
  tenantId: string
  userId: string
  roles: string[]
}

/** Read + verify the session JWT. Returns null when unauthenticated. */
export async function getSession(): Promise<Session | null> {
  const cookieStore = await cookies()
  const token = cookieStore.get(AUTH_COOKIE)?.value
  if (!token) return null

  try {
    const secret = new TextEncoder().encode(process.env.JWT_SECRET ?? '')
    const { payload } = await jwtVerify(token, secret, {
      issuer: 'scheduler-api',
      audience: 'scheduler-clients',
    })
    const tenantId = payload.tenantId
    const userId = payload.userId ?? payload.sub
    if (typeof tenantId !== 'string' || typeof userId !== 'string') return null
    const roles = Array.isArray(payload.roleClaims)
      ? (payload.roleClaims as unknown[]).map(String)
      : []
    return { token, tenantId, userId, roles }
  } catch {
    return null
  }
}

/** Session or redirect to /login. */
export async function requireSession(): Promise<Session> {
  const session = await getSession()
  if (!session) redirect('/login')
  return session
}

export function isAdmin(session: Session): boolean {
  return session.roles.some((r) => ADMIN_ROLES.includes(r))
}

/** Admin session or redirect (defense in depth — middleware redirects too). */
export async function requireAdminSession(): Promise<Session> {
  const session = await requireSession()
  if (!isAdmin(session)) redirect('/')
  return session
}

/** Tenant slug remembered at sign-in, used only for display/URLs. */
export async function getTenantSlug(): Promise<string> {
  const cookieStore = await cookies()
  return (
    cookieStore.get(TENANT_SLUG_COOKIE)?.value ??
    process.env.NEXT_PUBLIC_DEFAULT_TENANT_SLUG ??
    'workspace'
  )
}
