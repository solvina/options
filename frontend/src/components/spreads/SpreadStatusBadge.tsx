import { cn } from '../../lib/utils'

const styles: Record<string, string> = {
  OPEN: 'bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-300',
  CLOSED_PROFIT: 'bg-green-100 text-green-800 dark:bg-green-900/40 dark:text-green-300',
  CLOSED_STOP: 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-300',
  CLOSED_TIME: 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300',
  CLOSED_MANUAL: 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300',
}

export function SpreadStatusBadge({ status }: { status: string }) {
  return (
    <span
      className={cn(
        'inline-flex items-center rounded px-2 py-0.5 text-xs font-medium',
        styles[status] ?? 'bg-muted text-muted-foreground',
      )}
    >
      {status.replace('CLOSED_', '').replace('_', ' ')}
    </span>
  )
}
