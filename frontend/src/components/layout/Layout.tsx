import { useEffect, useState } from 'react'
import { NavLink, Outlet } from 'react-router-dom'
import { ConnectionBadge } from './ConnectionBadge'
import { cn } from '../../lib/utils'

const navGroups = [
  {
    label: 'Spreads',
    items: [
      { to: '/spreads/positions', label: 'Positions' },
      { to: '/spreads/analytics', label: 'Analytics' },
      { to: '/scanner', label: 'Scanner' },
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
    label: 'Maintenance',
    items: [
      { to: '/universe', label: 'Universe' },
      { to: '/account', label: 'Account' },
      { to: '/diagnostic', label: 'Diagnostics' },
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
    <div className="flex items-center gap-3 text-xs tabular-nums text-muted-foreground font-mono">
      <span><span className="text-foreground/50 mr-1">CET</span>{fmt('Europe/Prague')}</span>
      <span className="w-px h-3 bg-border" />
      <span><span className="text-foreground/50 mr-1">ET</span>{fmt('America/New_York')}</span>
    </div>
  )
}

export function Layout() {
  return (
    <div className="min-h-screen bg-background text-foreground flex flex-col">
      <header className="border-b border-border px-6 py-3 flex items-center justify-between">
        <div className="flex items-center gap-6">
          <span className="font-semibold text-base tracking-tight shrink-0">Options Engine</span>
          <nav className="flex items-center gap-1">
            {navGroups.map((group, gi) => (
              <div key={group.label} className="flex items-center gap-1">
                {gi > 0 && <span className="w-px h-4 bg-border mx-1" />}
                <span className="text-xs text-muted-foreground px-1 select-none">{group.label}</span>
                {group.items.map(({ to, label }) => (
                  <NavLink
                    key={to}
                    to={to}
                    className={({ isActive }) =>
                      cn(
                        'px-3 py-1.5 rounded-md text-sm transition-colors',
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
        </div>
        <div className="flex items-center gap-4">
          <Clock />
          <ConnectionBadge />
        </div>
      </header>
      <main className="flex-1 p-6">
        <Outlet />
      </main>
    </div>
  )
}
