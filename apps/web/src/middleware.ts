// TASK: P1-T10 / ATOM-UI-015
// Edge middleware: verifies the scheduler_token cookie with jose (Edge
// Runtime has no Node crypto, so JJWT-style libs are unusable here).
// ATOM-UI-015 adds the /admin/* role guard: non-admin users are redirected
// to the booking home. This is a UX-level redirect only — the API enforces
// the role server-side via @PreAuthorize.
import { NextRequest, NextResponse } from 'next/server'
import { jwtVerify } from 'jose'

const PUBLIC_PATHS = ['/login', '/verify']
const ADMIN_PATHS = ['/admin']
const ADMIN_ROLES = ['admin', 'super_admin', 'ROLE_ADMIN']
const AUTH_COOKIE = 'scheduler_token'

export async function middleware(req: NextRequest) {
  const { pathname } = req.nextUrl
  const isPublic = PUBLIC_PATHS.some(
    (p) => pathname === p || pathname.startsWith(`${p}/`),
  )
  if (isPublic) return NextResponse.next()

  const token = req.cookies.get(AUTH_COOKIE)?.value
  if (!token) {
    return NextResponse.redirect(new URL('/login', req.url))
  }

  try {
    const secret = new TextEncoder().encode(process.env.JWT_SECRET ?? '')
    const { payload } = await jwtVerify(token, secret, {
      issuer: 'scheduler-api',
      audience: 'scheduler-clients',
    })

    const isAdminPath = ADMIN_PATHS.some(
      (p) => pathname === p || pathname.startsWith(`${p}/`),
    )
    if (isAdminPath) {
      const roles = Array.isArray(payload.roleClaims)
        ? (payload.roleClaims as unknown[]).map(String)
        : []
      if (!roles.some((r) => ADMIN_ROLES.includes(r))) {
        return NextResponse.redirect(new URL('/', req.url))
      }
    }
    return NextResponse.next()
  } catch {
    // Invalid or expired token: drop the cookie and re-authenticate.
    const res = NextResponse.redirect(new URL('/login', req.url))
    res.cookies.delete(AUTH_COOKIE)
    return res
  }
}

export const config = {
  matcher: ['/((?!_next|favicon.ico|.*\\.(?:svg|png|jpg|jpeg|gif|webp|ico)$).*)'],
}
