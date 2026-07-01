import { useEffect, useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { ConnectionBadge } from './ConnectionBadge'
import { DataFlowBadge } from './DataFlowBadge'
import { cn } from '../../lib/utils'

const navGroups = [
  {
    label: 'Overview',
    items: [
      { to: '/dashboard', label: 'Dashboard' },
    ],
  },
  {
    label: 'Bull Puts',
    items: [
      { to: '/spreads/positions', label: 'Positions' },
      { to: '/spreads/analytics', label: 'Analytics' },
      { to: '/scanner', label: 'Scanner' },
    ],
  },
  {
    label: 'Bear Calls',
    items: [
      { to: '/bear-calls/positions', label: 'Positions' },
    ],
  },
  {
    label: 'Flags',
    items: [
      { to: '/flags/positions', label: 'Positions' },
      { to: '/flags/analytics', label: 'Analytics' },
    ],
  },
  {
    label: 'Backtest',
    items: [
      { to: '/backtest', label: 'Flag' },
    ],
  },
  {
    label: 'Maintenance',
    items: [
      { to: '/universe', label: 'Universe' },
      { to: '/account', label: 'Account' },
      { to: '/diagnostic', label: 'Diagnostics' },
      { to: '/historical', label: 'Historical' },
    ],
  },
]

function Clock() {
  const [now, setNow] = useState(() => new Date())

  useEffect(() => {
    const id = setInterval(() => setNow(new Date()), 1000)
    return () => clearInterval(id)
  }, [])

  const fmt = (tz: string) =>
    now.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', second: '2-digit', timeZone: tz })

  return (
    <div className="flex flex-col gap-0.5 text-xs tabular-nums text-muted-foreground font-mono">
      <span><span className="text-foreground/50 mr-1 inline-block w-6">CET</span>{fmt('Europe/Prague')}</span>
      <span><span className="text-foreground/50 mr-1 inline-block w-6">ET</span>{fmt('America/New_York')}</span>
    </div>
  )
}

export function Layout() {
  return (
    <div className="min-h-screen bg-background text-foreground flex">
      <aside className="w-56 shrink-0 border-r border-border flex flex-col gap-4 px-4 py-4">
        {/* Top-left: brand, time, and status */}
        <div className="flex flex-col gap-3">
          <span className="font-semibold text-base tracking-tight">Options Engine</span>
          <Clock />
          <div className="flex flex-col gap-1.5">
            <ConnectionBadge />
            <DataFlowBadge />
          </div>
        </div>

        <div className="h-px bg-border" />

        {/* Navigation groups, stacked */}
        <nav className="flex flex-col gap-4">
          {navGroups.map((group) => (
            <div key={group.label} className="flex flex-col gap-0.5">
              <span className="text-[11px] uppercase tracking-wide text-muted-foreground/70 px-2 select-none">
                {group.label}
              </span>
              {group.items.map(({ to, label }) => (
                <NavLink
                  key={to}
                  to={to}
                  className={({ isActive }) =>
                    cn(
                      'px-2 py-1.5 rounded-md text-sm transition-colors',
                      isActive
                        ? 'bg-primary text-primary-foreground'
                        : 'text-muted-foreground hover:text-foreground hover:bg-accent',
                    )
                  }
                >
                  {label}
                </NavLink>
              ))}
            </div>
          ))}
        </nav>
      </aside>
      <main className="flex-1 p-6 overflow-x-auto">
        <Outlet />
      </main>
    </div>
  )
}
