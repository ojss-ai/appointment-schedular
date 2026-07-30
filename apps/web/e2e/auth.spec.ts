// TASK: P1-T10 — Playwright E2E auth flow (requires API + seeded tenant).
import { expect, test } from '@playwright/test'

test.describe('auth flow', () => {
  test('shouldBlockUnauthenticatedAccess_toProtectedRoute', async ({ page }) => {
    await page.goto('/dashboard')
    await expect(page).toHaveURL(/\/login/)
  })

  test('shouldRenderLoginForm', async ({ page }) => {
    await page.goto('/login')
    await expect(page.getByLabel('Email or phone')).toBeVisible()
    await expect(page.getByRole('button', { name: /send code/i })).toBeVisible()
  })

  test('shouldAutoFillOtpCells_onPaste', async ({ page }) => {
    await page.goto('/verify?identifier=user%40example.com&tenantSlug=demo-tenant')
    const first = page.getByLabel('Code character 1')
    await first.click()
    await page.evaluate(() => {
      const target = document.querySelector('input')
      if (!target) return
      const data = new DataTransfer()
      data.setData('text', 'A3K9PQ')
      target.dispatchEvent(
        new ClipboardEvent('paste', { clipboardData: data, bubbles: true }),
      )
    })
    await expect(page.getByLabel('Code character 6')).toHaveValue('Q')
  })

  test('shouldShowCountdownTimer', async ({ page }) => {
    await page.goto('/verify?identifier=user%40example.com&tenantSlug=demo-tenant')
    await expect(page.getByText(/Expires in/)).toBeVisible()
  })

  // Full happy path (request -> verify -> /dashboard with HttpOnly cookie)
  // requires the Spring API plus a seeded tenant and OTP capture; run it
  // against the docker-compose stack in CI.
  test.fixme('shouldCompleteFullAuthFlow_redirectsToDashboard', async () => {})
})
