'use client'

import { useEffect, useState } from 'react'
import { usePathname, useRouter } from 'next/navigation'
import { LogOut, ChevronDown, Menu } from 'lucide-react'
import { logout, getToken } from '@/lib/auth'
import { useRestaurantInfo } from '@/lib/use-restaurant-info'

const ROUTE_TITLES: { prefix: string; title: string }[] = [
  { prefix: '/dashboard',        title: 'Dashboard'     },
  { prefix: '/pedidos',          title: 'Pedidos'       },
  { prefix: '/pdv',              title: 'PDV'           },
  { prefix: '/kds',              title: 'Cozinha — KDS' },
  { prefix: '/mesas',            title: 'Mesas'         },
  { prefix: '/admin/cardapio',   title: 'Cardápio admin' },
  { prefix: '/admin/usuarios',   title: 'Usuários'      },
  { prefix: '/configuracoes',    title: 'Configurações' },
  { prefix: '/caixa',            title: 'Caixa'         },
  { prefix: '/financeiro/dre',   title: 'DRE'           },
  { prefix: '/admin/campanhas',  title: 'Campanhas'     },
  { prefix: '/admin/tracking',   title: 'Rastreamento'  },
  { prefix: '/admin/conversoes', title: 'Conversões'    },
  { prefix: '/admin/carrinhos',  title: 'Carrinhos'     },
  { prefix: '/admin/rfv',        title: 'RFV'           },
  { prefix: '/admin/fidelidade', title: 'Fidelidade'    },
  { prefix: '/admin/bot',        title: 'Bot WhatsApp'  },
  { prefix: '/admin/cupons',     title: 'Cupons'        },
  { prefix: '/delivery',           title: 'Entregas'     },
  { prefix: '/admin/entregadores', title: 'Entregadores' },
  { prefix: '/plataforma/tenants',     title: 'Empresas'    },
  { prefix: '/plataforma/integracoes', title: 'Integrações' },
  { prefix: '/plataforma/ia',          title: 'Uso de IA'   },
  { prefix: '/plataforma',             title: 'Visão Geral da Plataforma' },
]

function routeTitle(pathname: string): string {
  // Detalhe de empresa: rota dinâmica /plataforma/tenants/[slug]
  if (pathname.startsWith('/plataforma/tenants/')) return 'Detalhes da Empresa'
  const match = ROUTE_TITLES.find(
    ({ prefix }) => pathname === prefix || pathname.startsWith(prefix + '/')
  )
  return match?.title ?? ''
}

interface JwtPayload {
  sub?: string
  email?: string
  role?: string
  roles?: string[]
}

function decodeJwtPayload(token: string): JwtPayload | null {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    return JSON.parse(atob(b64)) as JwtPayload
  } catch {
    return null
  }
}

const ROLE_LABELS: Record<string, string> = {
  SUPER_ADMIN: 'Plataforma',
  ADMIN:   'Administrador',
  MANAGER: 'Gerente',
  CASHIER: 'Caixa',
  KITCHEN: 'Cozinheiro',
  WAITER:  'Garçom',
  STAFF:   'Colaborador',
}

function getRoleLabel(payload: JwtPayload | null): string {
  if (!payload) return ''
  const role =
    payload.role ??
    (Array.isArray(payload.roles) && payload.roles.length > 0
      ? payload.roles[0]
      : undefined)
  return role ? (ROLE_LABELS[role] ?? role) : ''
}

interface TopbarProps {
  onMenuClick?: () => void
}

export function Topbar({ onMenuClick }: TopbarProps) {
  const pathname = usePathname()
  const router = useRouter()
  const { restaurantName } = useRestaurantInfo()
  const [menuOpen, setMenuOpen] = useState(false)
  const [payload, setPayload] = useState<JwtPayload | null>(null)

  const roleLabel = getRoleLabel(payload)
  const email = payload?.email ?? payload?.sub ?? ''
  const title = routeTitle(pathname)
  const initial = (email[0] ?? 'U').toUpperCase()

  useEffect(() => {
    queueMicrotask(() => {
      const token = getToken()
      setPayload(token ? decodeJwtPayload(token) : null)
    })
  }, [pathname])

  const handleLogout = () => {
    setMenuOpen(false)
    logout()
    router.push('/login')
  }

  return (
    <header
      className="sticky top-0 z-[9] flex h-14 shrink-0 items-center justify-between gap-2 border-b border-border-light bg-bg-primary px-4"
    >
      <div className="flex min-w-0 items-center gap-2">
        <button
          type="button"
          onClick={onMenuClick}
          aria-label="Abrir menu"
          className="lg:hidden flex h-11 w-11 items-center justify-center rounded-lg text-text-secondary hover:bg-bg-tertiary"
        >
          <Menu className="h-5 w-5" aria-hidden="true" />
        </button>
        {restaurantName && (
          <>
            <span className="hidden shrink-0 text-sm font-semibold text-text-primary sm:block">
              {restaurantName}
            </span>
            {title && (
              <span className="hidden text-text-muted sm:block" aria-hidden="true">
                /
              </span>
            )}
          </>
        )}

        {title && (
          <span className="truncate text-sm font-medium text-text-secondary">
            {title}
          </span>
        )}
      </div>

      <div className="relative shrink-0">
        <button
          type="button"
          aria-expanded={menuOpen}
          aria-haspopup="menu"
          onClick={() => setMenuOpen((v) => !v)}
          className="flex min-h-11 items-center gap-2 rounded-lg px-2 py-1.5 text-text-secondary hover:bg-bg-tertiary"
        >
          <div
            className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary-700 text-sm font-bold text-white select-none"
            aria-hidden="true"
          >
            {initial}
          </div>
          <span className="hidden max-w-[120px] truncate text-sm font-medium text-text-primary sm:block">
            {roleLabel || email}
          </span>
          <ChevronDown className="h-4 w-4 shrink-0" aria-hidden="true" />
        </button>

        {menuOpen && (
          <>
            <div
              className="fixed inset-0 z-[8]"
              aria-hidden="true"
              onClick={() => setMenuOpen(false)}
            />
            <div
              role="menu"
              className="absolute right-0 z-[10] mt-1 w-56 overflow-hidden rounded-xl border border-border-light bg-bg-primary shadow-dropdown"
            >
              <div className="border-b border-border-light px-4 py-3">
                {roleLabel && (
                  <p className="text-sm font-medium text-text-primary">{roleLabel}</p>
                )}
                {email && (
                  <p className="truncate text-xs text-text-muted">{email}</p>
                )}
              </div>
              <button
                role="menuitem"
                onClick={handleLogout}
                className="flex min-h-11 w-full items-center gap-2 px-4 py-2.5 text-left text-sm text-text-secondary hover:bg-bg-tertiary"
              >
                <LogOut className="h-4 w-4 shrink-0" aria-hidden="true" />
                Sair
              </button>
            </div>
          </>
        )}
      </div>
    </header>
  )
}
