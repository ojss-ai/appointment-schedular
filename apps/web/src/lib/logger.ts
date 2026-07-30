// TASK: P1-T10 — structured logging (pino), no console.log in production code.
import pino from 'pino'

export const logger = pino({
  level: process.env.LOG_LEVEL ?? 'info',
  base: { app: 'web' },
})
