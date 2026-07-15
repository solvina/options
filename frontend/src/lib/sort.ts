import { useState, createElement } from 'react'
import type { ReactNode } from 'react'
import { useLocalStorage } from './useLocalStorage'

export type SortDir = 'asc' | 'desc'
export interface SortState { key: string; dir: SortDir }

function toggler(setSort: (fn: (s: SortState) => SortState) => void) {
  return (key: string) =>
    setSort(s => s.key === key ? { key, dir: s.dir === 'asc' ? 'desc' : 'asc' } : { key, dir: 'asc' })
}

export function useSortable(defaultKey: string, defaultDir: SortDir = 'asc') {
  const [sort, setSort] = useState<SortState>({ key: defaultKey, dir: defaultDir })
  return { sort, toggle: toggler(setSort) }
}

/**
 * Like [useSortable] but the sort column + direction persist across reloads under `sort.<storageKey>`.
 * Give each table a unique storageKey so their sort states don't clobber each other.
 */
export function usePersistentSortable(storageKey: string, defaultKey: string, defaultDir: SortDir = 'asc') {
  const [sort, setSort] = useLocalStorage<SortState>(`sort.${storageKey}`, { key: defaultKey, dir: defaultDir })
  return { sort, toggle: toggler(setSort) }
}

export function sorted<T>(arr: T[], sort: SortState, getValue: (item: T, key: string) => unknown): T[] {
  return [...arr].sort((a, b) => {
    const av = getValue(a, sort.key)
    const bv = getValue(b, sort.key)
    if (av == null && bv == null) return 0
    if (av == null) return 1
    if (bv == null) return -1
    let cmp: number
    if (typeof av === 'string' && typeof bv === 'string') {
      cmp = av.localeCompare(bv)
    } else {
      cmp = (av as number) < (bv as number) ? -1 : (av as number) > (bv as number) ? 1 : 0
    }
    return sort.dir === 'asc' ? cmp : -cmp
  })
}

export function SortTh({
  label,
  col,
  sort,
  onSort,
  className = 'px-3 py-2 text-left',
}: {
  label: string
  col: string
  sort: SortState
  onSort: (k: string) => void
  className?: string
}): ReactNode {
  const active = sort.key === col
  return createElement(
    'th',
    {
      className: `cursor-pointer select-none whitespace-nowrap ${className}`,
      onClick: () => onSort(col),
    },
    label,
    createElement(
      'span',
      { className: `ml-0.5 text-[10px] ${active ? '' : 'opacity-30'}` },
      active ? (sort.dir === 'asc' ? '▲' : '▼') : '⇅',
    ),
  )
}
