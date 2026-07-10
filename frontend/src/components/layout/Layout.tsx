import { useEffect, useState } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { Menu, X } from 'lucide-react'
import { ConnectionBadge } from './ConnectionBadge'
import { DataFlowBadge } from './DataFlowBadge'
import { FatalBanner } from './FatalBanner'
import { cn } from '../../lib/utils'

const navGroups = [
  {
    label: 'Overview',
    items: [
      { to: '/dashboard', label: 'Dashboard' },
      { to: '/reports', label: 'Reports' },
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
  const [menuOpen, setMenuOpen] = useState(false)
  const location = useLocation()

  // Close the mobile drawer whenever the route changes.
  useEffect(() => {
    setMenuOpen(false)
  }, [location.pathname])

  return (
    <div className="min-h-screen bg-background text-foreground">
      <FatalBanner />
      <div className="md:flex">
      {/* Mobile top bar — hamburger, brand, and the two status lights (dot-only) always visible. */}
      <header className="md:hidden sticky top-0 z-20 flex items-center gap-3 border-b border-border bg-background px-4 py-2">
        <button
          type="button"
          aria-label="Open menu"
          onClick={() => setMenuOpen(true)}
          className="-ml-1 p-1 text-muted-foreground hover:text-foreground"
        >
          <Menu className="h-5 w-5" />
        </button>
        <span className="font-semibold text-sm tracking-tight">Options Engine</span>
        <div className="ml-auto flex items-center gap-3">
          <ConnectionBadge compact />
          <DataFlowBadge compact />
        </div>
      </header>

      {/* Backdrop behind the mobile drawer. */}
      {menuOpen && (
        <div
          className="fixed inset-0 z-30 bg-black/40 md:hidden"
          onClick={() => setMenuOpen(false)}
          aria-hidden
        />
      )}

      <aside
        className={cn(
          'fixed inset-y-0 left-0 z-40 w-64 border-r border-border bg-background px-4 py-4',
          'flex flex-col gap-4 overflow-y-auto transition-transform duration-200',
          'md:static md:z-auto md:w-56 md:translate-x-0 md:transition-none',
          menuOpen ? 'translate-x-0' : '-translate-x-full',
        )}
      >
        {/* Top-left: brand, time, and status */}
        <div className="flex flex-col gap-3">
          <div className="flex items-start justify-between">
            <span className="font-semibold text-base tracking-tight">Options Engine</span>
            <button
              type="button"
              aria-label="Close menu"
              onClick={() => setMenuOpen(false)}
              className="md:hidden -mr-1 p-1 text-muted-foreground hover:text-foreground"
            >
              <X className="h-5 w-5" />
            </button>
          </div>
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

      <main className="flex-1 min-w-0 p-4 md:p-6 overflow-x-auto">
        <Outlet />
      </main>
      </div>
    </div>
  )
}
