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

// ── Conteudo compartilhado (desktop e drawer mobile) ─────────────────────────

interface ContentProps {
  collapsed: boolean
  showToggle?: boolean
  onToggle?: () => void
  onNavClick?: () => void
}

function SidebarContent({ collapsed, showToggle, onToggle, onNavClick }: ContentProps) {
  const pathname = usePathname()

  return (
    <div className="flex min-h-0 flex-1 flex-col border-r border-border-light bg-bg-primary">
      {/* Brand */}
      <div
        className={[
          'flex h-16 shrink-0 items-center border-b border-border-light px-3',
          collapsed ? 'justify-center' : 'gap-3',
        ].join(' ')}
      >
        <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-primary-700 text-sm font-bold text-white select-none">
          MF
        </div>
        {!collapsed && (
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
            {!collapsed && (
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
                      title={collapsed ? label : undefined}
                      aria-current={isActive ? 'page' : undefined}
                      onClick={onNavClick}
                      className={[
                        'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors duration-150',
                        collapsed ? 'justify-center' : '',
                        isActive
                          ? 'bg-primary-700 text-white'
                          : 'text-text-secondary hover:bg-bg-tertiary hover:text-text-primary',
                      ].join(' ')}
                    >
                      <Icon className="h-5 w-5 shrink-0" aria-hidden="true" />
                      {!collapsed && <span>{label}</span>}
                    </Link>
                  </li>
                )
              })}
            </ul>
          </div>
        ))}
      </nav>

      {/* Rodape: botao de toggle (desktop) */}
      {showToggle && onToggle && (
        <div className="border-t border-border-light p-3">
          <button
            onClick={onToggle}
            aria-label={collapsed ? 'Expandir menu lateral' : 'Recolher menu lateral'}
            className={[
              'flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-xs text-text-secondary transition-colors hover:bg-bg-tertiary',
              collapsed ? 'justify-center' : '',
            ].join(' ')}
          >
            {collapsed ? (
              <ChevronRight className="h-4 w-4" aria-hidden="true" />
            ) : (
              <>
                <ChevronLeft className="h-4 w-4" aria-hidden="true" />
                <span>Recolher</span>
              </>
            )}
          </button>
        </div>
      )}
    </div>
  )
}

// ── Sidebar principal ─────────────────────────────────────────────────────────

interface SidebarProps {
  mobileOpen: boolean
  onClose: () => void
}

export function Sidebar({ mobileOpen, onClose }: SidebarProps) {
  const [collapsed, setCollapsed] = useState(false)
  const [mounted, setMounted] = useState(false)

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

  return (
    <>
      {/* Mobile: drawer com scrim */}
      {mobileOpen && (
        <div
          className="fixed inset-0 z-50 lg:hidden"
          aria-modal="true"
          role="dialog"
          aria-label="Menu de navegacao"
        >
          <div
            className="absolute inset-0 bg-black/50"
            aria-hidden="true"
            onClick={onClose}
          />
          <aside
            className="absolute inset-y-0 left-0 flex w-64 flex-col"
            onClick={(e) => e.stopPropagation()}
          >
            <SidebarContent collapsed={false} onNavClick={onClose} />
          </aside>
        </div>
      )}

      {/* Desktop: aside no fluxo flexbox */}
      <aside
        className={[
          'hidden lg:flex lg:shrink-0 lg:flex-col transition-all duration-200 overflow-hidden',
          mounted ? (collapsed ? 'lg:w-16' : 'lg:w-64') : 'lg:w-16',
        ].join(' ')}
        aria-label="Navegacao principal"
      >
        <SidebarContent
          collapsed={!mounted || collapsed}
          showToggle
          onToggle={toggle}
        />
      </aside>
    </>
  )
}
