'use client'

import { useState, useEffect } from 'react'
import Link from 'next/link'
import { usePathname, useRouter } from 'next/navigation'
import {
  ShoppingCart,
  ChefHat,
  LayoutGrid,
  Settings,
  LogOut,
  ChevronLeft,
  ChevronRight,
} from 'lucide-react'
import { logout } from '@/lib/auth'

const NAV_ITEMS = [
  { href: '/pdv',           label: 'PDV',           icon: ShoppingCart },
  { href: '/kds',           label: 'Cozinha',        icon: ChefHat      },
  { href: '/mesas',         label: 'Mesas',          icon: LayoutGrid   },
  { href: '/configuracoes', label: 'Configuracoes',  icon: Settings     },
] as const

const SIDEBAR_KEY = 'mf_sidebar_collapsed'

export function Sidebar() {
  const pathname  = usePathname()
  const router    = useRouter()
  const [collapsed, setCollapsed] = useState(false)
  const [mounted,   setMounted]   = useState(false)

  useEffect(() => {
    setMounted(true)
    try {
      const saved = localStorage.getItem(SIDEBAR_KEY)
      if (saved !== null) setCollapsed(JSON.parse(saved) as boolean)
    } catch {
      // ignore parse errors
    }
  }, [])

  const toggle = () => {
    const next = !collapsed
    setCollapsed(next)
    try { localStorage.setItem(SIDEBAR_KEY, JSON.stringify(next)) } catch { /* ignore */ }
  }

  const handleLogout = () => {
    logout()
    router.push('/login')
  }

  // Placeholder before hydration to avoid layout shift
  if (!mounted) {
    return (
      <aside
        className="flex w-16 flex-shrink-0 flex-col border-r border-border-light bg-bg-primary"
        aria-hidden="true"
      />
    )
  }

  return (
    <aside
      className={[
        'relative flex flex-shrink-0 flex-col border-r border-border-light bg-bg-primary transition-all duration-200',
        collapsed ? 'w-16' : 'w-60',
      ].join(' ')}
      aria-label="Navegacao principal"
    >
      {/* Brand */}
      <div
        className={[
          'flex h-16 flex-shrink-0 items-center border-b border-border-light px-3',
          collapsed ? 'justify-center' : 'gap-3',
        ].join(' ')}
      >
        <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-primary-700 text-sm font-bold text-white select-none">
          MF
        </div>
        {!collapsed && (
          <span className="truncate text-sm font-semibold text-text-primary">
            MenuFlow
          </span>
        )}
      </div>

      {/* Nav items */}
      <nav className="flex-1 overflow-y-auto p-2" aria-label="Menu principal">
        <ul role="list" className="flex flex-col gap-0.5">
          {NAV_ITEMS.map(({ href, label, icon: Icon }) => {
            const isActive =
              pathname === href || pathname.startsWith(href + '/')
            return (
              <li key={href}>
                <Link
                  href={href}
                  title={collapsed ? label : undefined}
                  aria-current={isActive ? 'page' : undefined}
                  className={[
                    'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors duration-150',
                    collapsed ? 'justify-center' : '',
                    isActive
                      ? 'bg-primary-700 text-white'
                      : 'text-text-secondary hover:bg-bg-tertiary hover:text-text-primary',
                  ].join(' ')}
                >
                  <Icon className="h-5 w-5 flex-shrink-0" aria-hidden="true" />
                  {!collapsed && <span>{label}</span>}
                </Link>
              </li>
            )
          })}
        </ul>
      </nav>

      {/* Toggle button */}
      <button
        onClick={toggle}
        aria-label={collapsed ? 'Expandir menu' : 'Recolher menu'}
        className="absolute -right-3 top-20 z-10 flex h-6 w-6 items-center justify-center rounded-full border border-border-light bg-bg-primary shadow-card transition-colors hover:bg-bg-tertiary"
      >
        {collapsed
          ? <ChevronRight className="h-3 w-3 text-text-secondary" aria-hidden="true" />
          : <ChevronLeft  className="h-3 w-3 text-text-secondary" aria-hidden="true" />
        }
      </button>

      {/* Footer */}
      <div className="border-t border-border-light p-3">
        <div
          className={[
            'flex items-center',
            collapsed ? 'justify-center' : 'gap-3',
          ].join(' ')}
        >
          <div
            className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-full bg-primary-700 text-sm font-bold text-white select-none"
            aria-hidden="true"
          >
            A
          </div>
          {!collapsed && (
            <span className="flex-1 truncate text-sm text-text-primary">
              Admin
            </span>
          )}
        </div>
        <button
          onClick={handleLogout}
          title={collapsed ? 'Sair' : undefined}
          aria-label="Sair da conta"
          className={[
            'mt-2 flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-xs text-text-secondary transition-colors hover:bg-error-light hover:text-error-dark',
            collapsed ? 'justify-center' : '',
          ].join(' ')}
        >
          <LogOut className="h-4 w-4 flex-shrink-0" aria-hidden="true" />
          {!collapsed && <span>Sair</span>}
        </button>
      </div>
    </aside>
  )
}
