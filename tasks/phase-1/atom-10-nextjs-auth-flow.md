# ATOM-NEXTJS-AUTH-010: Next.js Auth Flow UI

**Status**: 🟡 Planned
**Feature**: nextjs-auth-ui
**Phase**: 1 (Foundation)
**Tags**: [AUTH]
**Complexity**: Medium
**Agent**: coder
**Dependencies**: ATOM-AUTH-FLOW-009
**Blocks**: None
**PR**: TBD

---

## Overview

This atom implements the complete authentication flow in the Next.js 15 App Router: identifier entry (`/login`), OTP code entry (`/verify`), and JWT storage as an `HttpOnly` cookie. Server Actions handle all API calls to the Spring Boot backend so the JWT never touches client-side JavaScript. A Next.js middleware layer enforces authentication on all protected routes by verifying the `scheduler_token` cookie on every request. The key design decision is that the JWT is stored exclusively in an `HttpOnly` cookie managed by the server action layer — never in `localStorage` or exposed to client components.

---

## User Story

```
As a Booking User
I want to complete the full OTP login flow in a browser UI
So that I can access the scheduling system without a password and have my session secured against XSS
```

---

## Acceptance Criteria

- [ ] **AC-01**: User completes the full auth flow: enter identifier → receive OTP → enter 6-character code → redirected to `/dashboard`
- [ ] **AC-02**: JWT stored as `HttpOnly` cookie named `scheduler_token` (verified with DevTools → Application → Cookies)
- [ ] **AC-03**: JWT is never stored in `localStorage` or exposed to client-side JavaScript
- [ ] **AC-04**: OTP countdown timer is visible and counts from `5:00` to `0:00`
- [ ] **AC-05**: "Resend OTP" button appears after 60 seconds
- [ ] **AC-06**: Incorrect OTP shows an inline error without a full page reload
- [ ] **AC-07**: Pasting a 6-character code into the OTP input auto-fills all 6 cells
- [ ] **AC-08**: Next.js middleware redirects unauthenticated requests from protected routes to `/login`
- [ ] **AC-09**: Next.js middleware deletes an expired JWT cookie and redirects to `/login`
- [ ] **AC-10 (Domain abstraction)**: No industry-specific terms in any component name, route path, or UI label

**Verification Mapping**:

| Criterion | Test Location | Code Location | Status |
|-----------|---------------|---------------|--------|
| AC-01 | E2E (Playwright) | `app/(auth)/login/page.tsx`, `app/(auth)/verify/page.tsx` | 🔜 Planned |
| AC-02 | DevTools manual / Playwright | `lib/auth-actions.ts` | 🔜 Planned |
| AC-03 | Code review | `lib/auth-actions.ts` | 🔜 Planned |
| AC-04 | E2E (Playwright) | `components/auth/AuthStatus.tsx` | 🔜 Planned |
| AC-05 | E2E (Playwright) | `components/auth/AuthStatus.tsx` | 🔜 Planned |
| AC-06 | E2E (Playwright) | `app/(auth)/verify/page.tsx` | 🔜 Planned |
| AC-07 | E2E (Playwright) | `components/auth/OtpInput.tsx` | 🔜 Planned |
| AC-08 | E2E (Playwright) | `middleware.ts` | 🔜 Planned |
| AC-09 | E2E (Playwright) | `middleware.ts` | 🔜 Planned |
| AC-10 | TBD | TBD | 🔜 Planned |

<!-- AC validation passed: YYYY-MM-DD, 10 criteria rewritten, 10 marked TBD -->

---

## Technical Design

### Architecture

The Next.js App Router auth flow uses server components for page shells, client components (`'use client'`) for interactive inputs (`OtpInput`, `AuthStatus`), and server actions (`'use server'`) for all API calls. Server actions run on the server — they can set `HttpOnly` cookies via `next/headers` and call `redirect()`. The middleware runs on the Edge Runtime, validates the JWT using `jose` (not JJWT), and handles redirection without hitting the API server.

### Data Flow / Sequence (if applicable)

```
/login page
  → IdentifierInput form submit
  → requestOtp() server action
      → fetch POST /api/v1/auth/request-otp
      → return { maskedIdentifier, expiresAt }
  → redirect to /verify?identifier=...

/verify page
  → OtpInput (6-cell client component)
  → verifyOtp() server action
      → fetch POST /api/v1/auth/verify-otp
      → if token: cookies().set('scheduler_token', token, { httpOnly: true })
      → redirect('/dashboard')
      → else: return error data to component

middleware.ts (every request)
  → read scheduler_token cookie
  → if absent: redirect /login
  → jwtVerify(token, secret) via jose
  → if invalid/expired: delete cookie, redirect /login
  → else: NextResponse.next()
```

### File Structure

```
apps/web/src/
├── app/
│   ├── (auth)/
│   │   ├── login/
│   │   │   └── page.tsx           ← server component
│   │   └── verify/
│   │       └── page.tsx           ← server component
│   └── layout.tsx
├── components/
│   └── auth/
│       ├── IdentifierInput.tsx    ← client component
│       ├── OtpInput.tsx           ← 6-cell client component
│       └── AuthStatus.tsx         ← countdown timer client component
├── lib/
│   ├── api-client.ts              ← all fetch wrappers
│   └── auth-actions.ts            ← server actions
└── middleware.ts
```

### Interface Contracts

```typescript
// auth-actions.ts — server action signatures
export async function requestOtp(
  identifier: string,
  tenantSlug: string
): Promise<{ maskedIdentifier: string; expiresAt: string }>;

export async function verifyOtp(
  identifier: string,
  tenantSlug: string,
  otp: string
): Promise<{ status: string; message?: string } | never>; // redirects on success

// OtpInput props
interface OtpInputProps {
  value: string[];
  onChange: (val: string[]) => void;
}

// AuthStatus props
interface AuthStatusProps {
  maskedIdentifier: string;
  expiresAt: string;    // ISO timestamp
  onResend: () => void;
}

// middleware config
export const config = { matcher: ['/((?!_next|favicon).*)'] };
```

### Design Rationale

- `HttpOnly` cookie storage for JWT prevents XSS-based token theft. No JavaScript on the client can read `document.cookie` for `HttpOnly` cookies.
- Server Actions (`'use server'`) allow the JWT to be set server-side without exposing it in an API response body — the token is written directly into the cookie store.
- `jose` is used in middleware (not JJWT) because middleware runs on the Edge Runtime, which does not support Node.js crypto APIs — `jose` is a pure-JS implementation compatible with the Edge.
- The 60-second resend timer matches the rate limit window to prevent users from hammering the OTP endpoint.

---

## Test Strategy

**Test type**: E2E (Playwright)

```
- shouldCompleteFullAuthFlow_redirectsToDashboard:
    Given: Spring Boot API running; tenant "mc-clinic" seeded; mock OTP delivery
    Assert: user fills identifier, fills OTP, gets redirected to /dashboard; scheduler_token cookie set as HttpOnly

- shouldBlockUnauthenticatedAccess_toProtectedRoute:
    Given: no scheduler_token cookie
    Assert: GET /dashboard redirects to /login

- shouldAutoFillOtpCells_onPaste:
    Given: user on /verify page; 6-character string in clipboard
    Assert: all 6 OTP input cells filled correctly after Ctrl+V

- shouldShowCountdownTimer_andResendButton:
    Given: user on /verify page after requesting OTP
    Assert: timer visible; after 60 seconds, "Resend code" button appears

- shouldShowInlineError_forWrongOtp:
    Given: user submits incorrect 6-character OTP
    Assert: error message visible without page reload; URL stays at /verify
```

**Coverage requirements**:
- No line coverage target — this atom is UI
- All 5 Playwright E2E scenarios must pass before Phase 1 sign-off

---

## Implementation Constraints

- JWT must be stored in `HttpOnly` cookie named `scheduler_token` — never `localStorage`
- All API calls must go through `apps/web/lib/api-client.ts` (no direct `fetch` in components)
- Middleware must use `jose` for JWT verification (not JJWT — Edge Runtime incompatibility)
- Cookie must have `sameSite: 'lax'`, `secure: true` in production, `maxAge: 86400`
- OTP input must filter to the `OTP_ALPHABET` character set (`[A-Z2-9]`)
- Countdown timer interval must be cleared in the `useEffect` cleanup function
- No `console.log` in production code — use pino logger
- Server Actions must be in files marked `'use server'`

---

## Implementation Plan (TDD)

### RED — Write failing tests first

1. Create Playwright E2E test file `apps/web/e2e/auth.spec.ts`
2. Write `shouldCompleteFullAuthFlow_redirectsToDashboard` — fails (no pages exist)
3. Write `shouldBlockUnauthenticatedAccess_toProtectedRoute` — fails (no middleware)

### GREEN — Minimum code to pass

1. Implement `middleware.ts` with JWT validation and redirect logic
2. Create `lib/auth-actions.ts` with `requestOtp()` and `verifyOtp()` server actions
3. Create `app/(auth)/login/page.tsx` with `IdentifierInput` form
4. Create `app/(auth)/verify/page.tsx` with `OtpInput` and `AuthStatus` components
5. Implement `components/auth/OtpInput.tsx` with paste support
6. Implement `components/auth/AuthStatus.tsx` with countdown timer
7. Run Playwright E2E — happy path and middleware tests pass

### REFACTOR — Quality pass

1. Extract cookie configuration to a shared constant
2. Add error boundary to verify page for unexpected server action failures
3. Verify no JWT value appears in any `console.log` or network response body
4. Run `/security-scan` on auth components

---

## Implementation Reference

### middleware.ts

**File**: `apps/web/src/middleware.ts`

```typescript
// TASK: P1-T10
import { NextRequest, NextResponse } from 'next/server'
import { jwtVerify } from 'jose'

const PUBLIC_PATHS = ['/login', '/verify', '/api/auth']

export async function middleware(req: NextRequest) {
  const isPublic = PUBLIC_PATHS.some(p => req.nextUrl.pathname.startsWith(p))
  if (isPublic) return NextResponse.next()

  const token = req.cookies.get('scheduler_token')?.value
  if (!token) {
    return NextResponse.redirect(new URL('/login', req.url))
  }

  try {
    const secret = new TextEncoder().encode(process.env.JWT_SECRET!)
    await jwtVerify(token, secret, { issuer: 'scheduler-api', audience: 'scheduler-clients' })
    return NextResponse.next()
  } catch {
    const res = NextResponse.redirect(new URL('/login', req.url))
    res.cookies.delete('scheduler_token')
    return res
  }
}

export const config = { matcher: ['/((?!_next|favicon).*)'] }
```

### auth-actions.ts

**File**: `apps/web/src/lib/auth-actions.ts`

```typescript
// TASK: P1-T10
'use server'
import { cookies } from 'next/headers'
import { redirect } from 'next/navigation'

const API = process.env.API_BASE_URL

export async function requestOtp(identifier: string, tenantSlug: string) {
  const res = await fetch(`${API}/api/v1/auth/request-otp`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ identifier, tenantSlug }),
  })
  if (!res.ok) throw new Error('Failed to send OTP')
  return res.json()
}

export async function verifyOtp(identifier: string, tenantSlug: string, otp: string) {
  const res = await fetch(`${API}/api/v1/auth/verify-otp`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ identifier, tenantSlug, otp }),
  })
  const data = await res.json()
  if (res.ok && data.token) {
    const cookieStore = await cookies()
    cookieStore.set('scheduler_token', data.token, {
      httpOnly: true,
      secure: process.env.NODE_ENV === 'production',
      sameSite: 'lax',
      maxAge: 60 * 60 * 24, // 24 hours
      path: '/',
    })
    redirect('/dashboard')
  }
  return data  // return error response to client
}
```

### OtpInput.tsx

**File**: `apps/web/src/components/auth/OtpInput.tsx`

```typescript
// TASK: P1-T10
'use client'
import { useRef, KeyboardEvent, ClipboardEvent } from 'react'

interface Props {
  value: string[]
  onChange: (val: string[]) => void
}

export function OtpInput({ value, onChange }: Props) {
  const refs = Array.from({ length: 6 }, () => useRef<HTMLInputElement>(null))

  const handleChange = (idx: number, char: string) => {
    const next = [...value]
    next[idx] = char.toUpperCase().replace(/[^A-Z2-9]/g, '').slice(-1)
    onChange(next)
    if (next[idx] && idx < 5) refs[idx + 1].current?.focus()
  }

  const handleKeyDown = (idx: number, e: KeyboardEvent) => {
    if (e.key === 'Backspace' && !value[idx] && idx > 0) {
      refs[idx - 1].current?.focus()
    }
  }

  const handlePaste = (e: ClipboardEvent) => {
    const text = e.clipboardData.getData('text').toUpperCase().replace(/[^A-Z2-9]/g, '')
    const next = Array(6).fill('')
    text.split('').slice(0, 6).forEach((c, i) => (next[i] = c))
    onChange(next)
    refs[Math.min(text.length, 5)].current?.focus()
  }

  return (
    <div className="flex gap-2" onPaste={handlePaste}>
      {refs.map((ref, i) => (
        <input
          key={i}
          ref={ref}
          className="w-12 h-14 text-center text-xl border rounded-lg uppercase"
          maxLength={1}
          value={value[i] || ''}
          onChange={e => handleChange(i, e.target.value)}
          onKeyDown={e => handleKeyDown(i, e)}
        />
      ))}
    </div>
  )
}
```

### AuthStatus.tsx

**File**: `apps/web/src/components/auth/AuthStatus.tsx`

```typescript
// TASK: P1-T10
'use client'
import { useEffect, useState } from 'react'

interface Props {
  maskedIdentifier: string
  expiresAt: string          // ISO timestamp
  onResend: () => void
}

export function AuthStatus({ maskedIdentifier, expiresAt, onResend }: Props) {
  const [secondsLeft, setSecondsLeft] = useState(300)
  const [canResend, setCanResend] = useState(false)

  useEffect(() => {
    const target = new Date(expiresAt).getTime()
    const tick = setInterval(() => {
      const diff = Math.max(0, Math.floor((target - Date.now()) / 1000))
      setSecondsLeft(diff)
      if (diff === 0) clearInterval(tick)
    }, 1000)
    const resendTimer = setTimeout(() => setCanResend(true), 60_000)
    return () => { clearInterval(tick); clearTimeout(resendTimer) }
  }, [expiresAt])

  const fmt = (s: number) => `${Math.floor(s/60)}:${String(s%60).padStart(2,'0')}`

  return (
    <div className="text-sm text-gray-600">
      <p>Code sent to <strong>{maskedIdentifier}</strong></p>
      <p>Expires in <span className="font-mono">{fmt(secondsLeft)}</span></p>
      {canResend && (
        <button onClick={onResend} className="text-blue-600 underline mt-2">
          Resend code
        </button>
      )}
    </div>
  )
}
```

---

## Integration Points

**Depends on**: ATOM-AUTH-FLOW-009 (`POST /api/v1/auth/request-otp` and `POST /api/v1/auth/verify-otp` must be live)

**Enables**: Phase 2 booking UI atoms — authenticated users can now reach protected routes

**Cascading updates required**:
- `docs/memory/api-contracts.md` — confirm frontend consumption of both auth endpoints
- `tasks/MASTER-TASK-LIST.md` — mark atom complete; Phase 1 complete

---

## Files Changed

| File | Type | Purpose |
|------|------|---------|
| `apps/web/src/middleware.ts` | New | JWT validation and route protection |
| `apps/web/src/lib/auth-actions.ts` | New | Server actions for OTP flow |
| `apps/web/src/app/(auth)/login/page.tsx` | New | Identifier entry page |
| `apps/web/src/app/(auth)/verify/page.tsx` | New | OTP entry page |
| `apps/web/src/components/auth/IdentifierInput.tsx` | New | Email/phone input component |
| `apps/web/src/components/auth/OtpInput.tsx` | New | 6-cell OTP input with paste support |
| `apps/web/src/components/auth/AuthStatus.tsx` | New | Countdown timer with resend button |
| `apps/web/e2e/auth.spec.ts` | New | Playwright E2E auth tests |
| `tasks/MASTER-TASK-LIST.md` | Modified | Mark atom complete; Phase 1 complete |

---

## PR Checklist

- [ ] All acceptance criteria met and Verification Mapping table filled in
- [ ] Playwright E2E tests pass (`pnpm exec playwright test`)
- [ ] JWT confirmed as `HttpOnly` cookie — not in localStorage or response body
- [ ] Zero industry-specific terms in any component name, route, or UI label
- [ ] All Next.js API calls through `apps/web/lib/api-client.ts`
- [ ] No `console.log` in production components
- [ ] Middleware uses `jose` (not JJWT) for Edge Runtime compatibility
- [ ] Atom status updated to ✅ Complete
- [ ] `MASTER-TASK-LIST.md` updated — Phase 1 complete

---

*Last updated: 2026-06-18 | Feature: nextjs-auth-ui | Phase: 1*
