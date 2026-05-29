import { NavLink, Outlet } from 'react-router-dom'
import { ConnectionBadge } from './ConnectionBadge'
import { cn } from '../../lib/utils'

const navItems = [
  { to: '/spreads', label: 'Spreads' },
  { to: '/flags', label: 'Flags' },
  { to: '/account', label: 'Account' },
  { to: '/scanner', label: 'Scanner' },
  { to: '/analytics', label: 'Analytics' },
  { to: '/flags/analytics', label: 'Flag Analytics' },
  { to: '/universe', label: 'Universe' },
  { to: '/diagnostic', label: 'Diagnostics' },
]

export function Layout() {
  return (
    <div className="min-h-screen bg-background text-foreground flex flex-col">
      <header className="border-b border-border px-6 py-3 flex items-center justify-between">
        <div className="flex items-center gap-8">
          <span className="font-semibold text-base tracking-tight">Options Engine</span>
          <nav className="flex gap-1">
            {navItems.map(({ to, label }) => (
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
