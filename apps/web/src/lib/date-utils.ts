// TASK: ATOM-UI-014
// Small date helpers for the slot calendar (avoids a date library dependency).

/** Monday 00:00 (local) of the week containing `d`. */
export function startOfWeekMonday(d: Date): Date {
  const out = new Date(d)
  out.setHours(0, 0, 0, 0)
  const day = out.getDay() // 0 = Sunday
  const diff = day === 0 ? -6 : 1 - day
  out.setDate(out.getDate() + diff)
  return out
}

export function addDays(d: Date, n: number): Date {
  const out = new Date(d)
  out.setDate(out.getDate() + n)
  return out
}

/** Local calendar date as YYYY-MM-DD. */
export function toISODate(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** YYYY-MM-DD of an ISO instant, evaluated in the given IANA timezone. */
export function isoDateInZone(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date(instant))
}

/** Clock time (e.g. "9:00 AM") of an ISO instant in the given timezone. */
export function timeInZone(instant: string, timeZone: string): string {
  return new Intl.DateTimeFormat('en-US', {
    timeZone,
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(instant))
}

/** Long, human-readable date + time in the given timezone. */
export function dateTimeInZone(instant: string, timeZone?: string): string {
  return new Intl.DateTimeFormat('en-US', {
    ...(timeZone ? { timeZone } : {}),
    weekday: 'short',
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit',
  }).format(new Date(instant))
}
