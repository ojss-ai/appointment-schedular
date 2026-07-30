// TASK: P1-T10
'use server'

import { cookies } from 'next/headers'
import { redirect } from 'next/navigation'
import { ApiError, apiFetch } from './api-client'
import { logger } from './logger'

const AUTH_COOKIE = 'scheduler_token'
// ATOM-UI-014: remember the tenant slug for booking-flow URLs (display only —
// all API tenant scoping derives from the JWT claim, never from this cookie).
const TENANT_SLUG_COOKIE = 'scheduler_tenant_slug'

const AUTH_COOKIE_OPTIONS = {
  httpOnly: true,
  secure: process.env.NODE_ENV === 'production',
  sameSite: 'lax' as const,
  maxAge: 60 * 60 * 24, // 24 hours
  path: '/',
}

export interface RequestOtpResult {
  status: string
  maskedIdentifier: string
  expiresAt: string
}

export interface VerifyOtpFailure {
  status: string // 'OTP_INVALID' | 'OTP_EXPIRED' | 'ERROR'
  message?: string
}

/** Ask the API to dispatch an OTP. Never reveals whether the identifier exists. */
export async function requestOtp(
  identifier: string,
  tenantSlug: string,
): Promise<RequestOtpResult> {
  try {
    return await apiFetch<RequestOtpResult>('/api/v1/auth/request-otp', {
      method: 'POST',
      body: { identifier, tenantSlug },
    })
  } catch (err) {
    if (err instanceof ApiError && err.status === 429) {
      throw new Error('Too many code requests. Please wait and try again.')
    }
    logger.error({ err: String(err) }, 'requestOtp failed')
    throw new Error('Could not send a code. Please try again.')
  }
}

/**
 * Verify the OTP. On success the JWT is written into an HttpOnly cookie
 * (never exposed to client JavaScript) and the user is redirected to
 * /dashboard. On failure the error payload is returned for inline display.
 */
export async function verifyOtp(
  identifier: string,
  tenantSlug: string,
  otp: string,
): Promise<VerifyOtpFailure> {
  let token: string | null = null
  try {
    const data = await apiFetch<{ status: string; token: string | null; message: string | null }>(
      '/api/v1/auth/verify-otp',
      { method: 'POST', body: { identifier, tenantSlug, otp } },
    )
    token = data.token
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      const body = err.body as { status?: string; message?: string } | null
      return {
        status: body?.status ?? 'OTP_INVALID',
        message: body?.message ?? 'The code you entered is incorrect.',
      }
    }
    logger.error({ err: String(err) }, 'verifyOtp failed')
    return { status: 'ERROR', message: 'Something went wrong. Please try again.' }
  }

  if (!token) {
    return { status: 'OTP_INVALID', message: 'The code you entered is incorrect.' }
  }

  const cookieStore = await cookies()
  cookieStore.set(AUTH_COOKIE, token, AUTH_COOKIE_OPTIONS)
  cookieStore.set(TENANT_SLUG_COOKIE, tenantSlug, AUTH_COOKIE_OPTIONS)
  redirect('/dashboard')
}

/** Clear the session cookie and return to login. */
export async function signOut(): Promise<void> {
  const cookieStore = await cookies()
  cookieStore.delete(AUTH_COOKIE)
  cookieStore.delete(TENANT_SLUG_COOKIE)
  redirect('/login')
}
