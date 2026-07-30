// TASK: ATOM-UI-016
// Unit tests only — Playwright e2e specs live in e2e/ and run separately.
import { defineConfig } from 'vitest/config'

export default defineConfig({
  test: {
    include: ['src/**/*.test.{ts,tsx}'],
    environment: 'node',
  },
})
