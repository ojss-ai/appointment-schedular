// TASK: ATOM-UI-014
// Loading placeholder shown while the slot query is in flight.
export function SlotSkeleton() {
  return (
    <div
      className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-7"
      role="status"
      aria-label="Loading availability"
    >
      {Array.from({ length: 7 }).map((_, col) => (
        <div key={col} className="space-y-2">
          <div className="h-5 animate-pulse rounded bg-gray-200" />
          {Array.from({ length: 4 }).map((_, row) => (
            <div key={row} className="h-8 animate-pulse rounded-lg bg-gray-100" />
          ))}
        </div>
      ))}
    </div>
  )
}
