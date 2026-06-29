'use client'

import {
  useState, useEffect } from 'react'
import Image from 'next/image'
import Link from 'next/link'
import {
  usePathname } from 'next/navigation'
import {
  Star,
  Tag,
  ShoppingCart,
  ChefHat,
  LayoutGrid,
  Package,
  Settings,
  ChevronLeft,
  ChevronRight,
  UtensilsCrossed,
  Wallet,
  Users,
  Truck,
  X,
  BarChart2,
  Users2,
  Megaphone,
  Link2,
  type LucideIcon,
} from 'lucide-react'
import {
  useRestaurantInfo } from '@/lib/use-restaurant-info'
import {
  getToken } from '@/lib/auth'

// ── Navegação ─────────────────────────────────────────────────────────────────

interface NavItem {
  href: string
  label: string
  icon: LucideIcon
  /** Se definido, exibe o item apenas para os papéis listados */
  roles?: string[]
}

interface NavGroup {
  group: string
  items: NavItem[]
}

const NAV_GROUPS: NavGroup[] = [
  {
    group: 'OPERAÇÃO',
    items: [
      { href: '/pdv',   label: 'PDV',     icon: ShoppingCart },
      { href: '/kds',   label: 'Cozinha', icon: ChefHat      },
      { href: '/mesas', label: 'Mesas',   icon: LayoutGrid   },
      { href: '/caixa',            label: 'Caixa',       icon: Wallet, },
      { href: '/admin/fidelidade', label: 'Fidelidade',   icon: Star,   roles: ['ADMIN', 'MANAGER', 'CASHIER'] },
    ],
  },
  {
    group: 'SISTEMA',
    items: [
      { href: '/admin/cardapio',      label: 'Cardápio admin', icon: Package },
      { href: '/admin/usuarios',      label: 'Usuários',       icon: Users,  roles: ['ADMIN', 'MANAGER'] },
      { href: '/admin/entregadores',  label: 'Entregadores',   icon: Truck,  roles: ['ADMIN', 'MANAGER'] },
      { href: '/admin/cupons',     label: 'Cupons',         icon: Tag,      roles: ['ADMIN', 'MANAGER'] },
      { href: '/admin/rfv',        label: 'RFV',            icon: Users2,   roles: ['ADMIN', 'MANAGER'] },
      { href: '/admin/campanhas',  label: 'Campanhas',      icon: Megaphone, roles: ['ADMIN', 'MANAGER'] },
      { href: '/admin/tracking',   label: 'Rastreamento',   icon: Link2,     roles: ['ADMIN', 'MANAGER'] },
      { href: '/admin/carrinhos', label: 'Carrinhos',      icon: ShoppingCart, roles: ['ADMIN', 'MANAGER'] },
      { href: '/financeiro/dre', label: 'DRE', icon: BarChart2, roles: ['ADMIN', 'MANAGER'] },
      { href: '/configuracoes',       label: 'Configurações',  icon: Settings },
    ],
  },
]

// ── Decodifica o papel do JWT ────────────────────────────────────────────────

function getUserRoleFromToken(): string | null {
  try {
    const token = getToken()
    if (!token) return null
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(b64)) as { role?: string; roles?: string[] }
    return payload.role ?? ((Array.isArray(payload.roles) && payload.roles[0]) || null)
  } catch {
    return null
  }
}

const SIDEBAR_KEY = 'mf_sidebar'

// ── Badge de marca (fallback quando não há logo da empresa) ──────────────────

function BrandBadge({ collapsed }: { collapsed: boolean }) {
  return (
    <div
      className={[
        'flex shrink-0 items-center justify-center rounded-xl bg-primary-700 text-white',
        collapsed ? 'h-9 w-9' : 'h-9 w-9',
      ].join(' ')}
    >
      <UtensilsCrossed className="h-5 w-5" aria-hidden="true" />
    </div>
  )
}

// ── Nav compartilhado (desktop + drawer mobile) ───────────────────────────────

function NavContent({
  collapsed,
  onNavClick,
  userRole,
}: {
  collapsed: boolean
  onNavClick?: () => void
  userRole: string | null
}) {
  const pathname = usePathname()
  return (
    <nav className="flex-1 overflow-y-auto px-2 py-3" aria-label="Menu principal">
      {NAV_GROUPS.map(({ group, items }) => {
        const visibleItems = items.filter(
          ({ roles }) => !roles || (userRole !== null && roles.includes(userRole)),
        )
        if (visibleItems.length === 0) return null
        return (
          <div key={group} className="mb-3">
            {!collapsed && (
              <p className="mb-1 px-3 text-[10px] font-semibold uppercase tracking-wider text-text-muted select-none">
                {group}
              </p>
            )}
            <ul role="list" className="flex flex-col gap-0.5">
              {visibleItems.map(({ href, label, icon: Icon }) => {
                const isActive = pathname === href || pathname.startsWith(href + '/')
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
        )
      })}
    </nav>
  )
}

// ── Sidebar ───────────────────────────────────────────────────────────────────

interface SidebarProps {
  mobileOpen?: boolean
  onClose?: () => void
}

export function Sidebar({ mobileOpen = false, onClose }: SidebarProps) {
  const [collapsed, setCollapsed] = useState(false)
  const [mounted,   setMounted]   = useState(false)
  const [userRole,  setUserRole]  = useState<string | null>(null)
  const { restaurantName, logoUrl } = useRestaurantInfo()

  useEffect(() => {
    queueMicrotask(() => {
      setMounted(true)
      try {
        const saved = localStorage.getItem(SIDEBAR_KEY)
        if (saved === 'collapsed') setCollapsed(true)
      } catch { /* ignore */ }
      setUserRole(getUserRoleFromToken())
    })
  }, [])

  const toggle = () => {
    const next = !collapsed
    setCollapsed(next)
    try { localStorage.setItem(SIDEBAR_KEY, next ? 'collapsed' : 'expanded') }
    catch { /* ignore */ }
  }

  const isCollapsed = !mounted || collapsed

  const brandBlock = (collapsed: boolean) => (
    <div className={['flex h-16 shrink-0 items-center border-b border-border-light px-3', collapsed ? 'justify-center' : 'gap-3'].join(' ')}>
      {logoUrl ? (
        <Image src={logoUrl} alt={restaurantName ?? 'Logo'} width={36} height={36} className="shrink-0 rounded-xl object-contain" />
      ) : (
        <BrandBadge collapsed={collapsed} />
      )}
      {!collapsed && <span className="truncate text-sm font-semibold text-text-primary">{restaurantName ?? 'MenuFlow'}</span>}
    </div>
  )

  return (
    <>
      {/* ── Drawer mobile (lg:hidden) ─────────────────────────────────────── */}
      {mobileOpen && (
        <div className="fixed inset-0 z-50 lg:hidden" role="dialog" aria-modal="true" aria-label="Menu de navegação">
          {/* Scrim */}
          <div className="absolute inset-0 bg-black/50" aria-hidden="true" onClick={onClose} />
          {/* Painel */}
          <aside className="absolute inset-y-0 left-0 flex w-72 flex-col bg-bg-primary shadow-xl">
            <div className="flex h-16 shrink-0 items-center gap-3 border-b border-border-light px-3">
              {logoUrl ? (
                <Image src={logoUrl} alt={restaurantName ?? 'Logo'} width={36} height={36} className="shrink-0 rounded-xl object-contain" />
              ) : (
                <BrandBadge collapsed={false} />
              )}
              <span className="flex-1 truncate text-sm font-semibold text-text-primary">{restaurantName ?? 'MenuFlow'}</span>
              <button onClick={onClose} aria-label="Fechar menu" className="rounded-lg p-1 text-text-muted hover:bg-bg-tertiary">
                <X className="h-5 w-5" aria-hidden="true" />
              </button>
            </div>
            <NavContent collapsed={false} onNavClick={onClose} userRole={userRole} />
          </aside>
        </div>
      )}

      {/* ── Sidebar desktop (hidden em mobile) ───────────────────────────── */}
      <aside
        className={['hidden lg:flex shrink-0 flex-col transition-all duration-200 overflow-hidden', isCollapsed ? 'lg:w-16' : 'lg:w-64'].join(' ')}
        aria-label="Navegação principal"
      >
        <div className="flex min-h-0 flex-1 flex-col border-r border-border-light bg-bg-primary">
          {brandBlock(isCollapsed)}
          <NavContent collapsed={isCollapsed} userRole={userRole} />
          <div className="border-t border-border-light p-3">
            <button
              onClick={toggle}
              aria-label={isCollapsed ? 'Expandir menu lateral' : 'Recolher menu lateral'}
              className={['flex w-full items-center gap-2 rounded-lg px-2 py-1.5 text-xs text-text-secondary transition-colors hover:bg-bg-tertiary', isCollapsed ? 'justify-center' : ''].join(' ')}
            >
              {isCollapsed ? <ChevronRight className="h-4 w-4" aria-hidden="true" /> : <><ChevronLeft className="h-4 w-4" aria-hidden="true" /><span>Recolher</span></>}
            </button>
          </div>
        </div>
      </aside>
    </>
  )
}
