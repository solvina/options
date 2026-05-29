import { NavLink, Outlet } from 'react-router-dom'
import { ConnectionBadge } from './ConnectionBadge'
import { cn } from '../../lib/utils'

const navGroups = [
  {
    label: 'Spreads',
    items: [
      { to: '/spreads/positions', label: 'Positions' },
      { to: '/spreads/analytics', label: 'Analytics' },
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
      { to: '/scanner', label: 'Scanner' },
      { to: '/universe', label: 'Universe' },
      { to: '/account', label: 'Account' },
      { to: '/diagnostic', label: 'Diagnostics' },
    ],
  },
]

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
        <ConnectionBadge />
      </header>
      <main className="flex-1 p-6">
        <Outlet />
      </main>
    </div>
  )
}
