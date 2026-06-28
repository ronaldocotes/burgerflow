'use client'

import { useState, useEffect } from 'react'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import {
  ShoppingCart,
  ChefHat,
  LayoutGrid,
  Settings,
  ChevronLeft,
  ChevronRight,
  type LucideIcon,
} from 'lucide-react'

// ── Estrutura de navegacao ────────────────────────────────────────────────────

interface NavItem {
  href: string
  label: string
  icon: LucideIcon
}

interface NavGroup {
  group: string
  items: NavItem[]
}

const NAV_GROUPS: NavGroup[] = [
  {
    group: 'OPERACAO',
    items: [
      { href: '/pdv',   label: 'PDV',     icon: ShoppingCart },
      { href: '/kds',   label: 'Cozinha', icon: ChefHat      },
      { href: '/mesas', label: 'Mesas',   icon: LayoutGrid   },
    ],
  },
  {
    group: 'SISTEMA',
    items: [
      { href: '/configuracoes', label: 'Configuracoes', icon: Settings },
    ],
  },
]

const SIDEBAR_KEY = 'mf_sidebar'

// ── Sidebar ───────────────────────────────────────────────────────────────────

export function Sidebar() {
  const pathname  = usePathname()
  const [collapsed, setCollapsed] = useState(false)
  const [mounted,   setMounted]   = useState(false)

  useEffect(() => {
    setMounted(true)
    try {
      const saved = localStorage.getItem(SIDEBAR_KEY)
      if (saved === 'collapsed') setCollapsed(true)
    } catch {
      // ignore
    }
  }, [])

  const toggle = () => {
    const next = !collapsed
    setCollapsed(next)
    try {
      localStorage.setItem(SIDEBAR_KEY, next ? 'collapsed' : 'expanded')
    } catch {
      // ignore
    }
  }

  const isCollapsed = !mounted || collapsed

  return (
    <aside
      className={[
        'flex shrink-0 flex-col transition-all duration-200 overflow-hidden',
        isCollapsed ? 'w-16' : 'w-64',
      ].join(' ')}
      aria-label="Navegacao principal"
    >
      <div className="flex min-h-0 flex-1 flex-col border-r border-border-light bg-bg-primary">

        {/* Brand */}
        <div
          className={[
            'flex h-16 shrink-0 items-center border-b border-border-light px-3',
            isCollapsed ? 'justify-center' : 'gap-3',
          ].join(' ')}
        >
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary-700 text-sm font-bold text-white select-none">
            MF
          </div>
          {!isCollapsed && (
            <span className="truncate text-sm font-semibold text-text-primary">
              MenuFlow
            </span>
          )}
        </div>

        {/* Navegacao agrupada */}
        <nav className="flex-1 overflow-y-auto px-2 py-3" aria-label="Menu principal">
          {NAV_GROUPS.map(({ group, items }) => (
            <div key={group} className="mb-3">
              {/* Label do grupo: visivel so quando expandida */}
              {!isCollapsed && (
                <p className="mb-1 px-3 text-[10px] font-semibold uppercase tracking-wider text-text-muted select-none">
                  {group}
                </p>
              )}
              <ul role="list" className="flex flex-col gap-0.5">
                {items.map(({ href, label, icon: Icon }) => {
                  const isActive =
                    pathname === href || pathname.startsWith(href + '/')
                  return (
                    <li key={href}>
                      <Link
                        href={href}
                        title={isCollapsed ? label : undefined}
                        aria-current={isActive ? 'page' : undefined}
                        className={[
                          'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors duration-150',
                          isCollapsed ? 'justify-center' : '',
                          isActive
                            ? 'bg-primary-700 text-white'
                            : 'text-text-secondary hover:bg-bg-tertiary hover:text-text-primary',
                        ].join(' ')}
                      >
                        <Icon className="h-5 w-5 shrink-0" aria-hidden="true" />
                        {!isCollapsed && <span>{label}</span>}
                      </Link>
                    </li>
                  )
                })}
              </ul>
            </div>
          ))}
        </nav>

        {/* Rodape: toggle */}
        <div className="border-t border-border-light p-3">
          <button
            onClick={toggle}
            aria-label={isCollapsed ? 'Expandir menu lateral' : 'Recolher menu lateral'}
            className={[
              'flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-xs text-text-secondary transition-colors hover:bg-bg-tertiary',
              isCollapsed ? 'justify-center' : '',
            ].join(' ')}
          >
            {isCollapsed ? (
              <ChevronRight className="h-4 w-4" aria-hidden="true" />
            ) : (
              <>
                <ChevronLeft className="h-4 w-4" aria-hidden="true" />
                <span>Recolher</span>
              </>
            )}
          </button>
        </div>

      </div>
    </aside>
  )
}
