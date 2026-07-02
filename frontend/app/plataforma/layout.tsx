'use client'

// Layout proprio do painel super-admin /plataforma.
// NAO reutiliza o ClientLayout do tenant: o Topbar do tenant exibe o
// restaurantName (escopo de tenant) e o ClientLayout injeta o CopilotChat
// (IA do tenant) — nada disso se aplica ao super-admin da plataforma.
// ClientLayout.tsx pula /plataforma (lista de prefixos com layout proprio).

import { useEffect, useState } from 'react'
import Link from 'next/link'
import { usePathname, useRouter } from 'next/navigation'
import {
  LayoutDashboard,
  Building2,
  Plug,
  BrainCircuit,
  Users,
  LogOut,
  Menu,
  X,
  ShieldCheck,
  type LucideIcon,
} from 'lucide-react'
import { logout, getToken } from '@/lib/auth'
import { useSuperAdminGuard } from '@/hooks/useSuperAdminGuard'

// ── Navegacao ────────────────────────────────────────────────────────────────

interface NavItem {
  href: string
  label: string
  icon: LucideIcon
  /** exibe a etiqueta "Em breve" ao lado do item */
  soon?: boolean
  /** item sem pagina ainda: nao navega */
  disabled?: boolean
}

const NAV_ITEMS: NavItem[] = [
  { href: '/plataforma',             label: 'Visão Geral', icon: LayoutDashboard },
  { href: '/plataforma/tenants',     label: 'Empresas',    icon: Building2 },
  { href: '/plataforma/integracoes', label: 'Integrações', icon: Plug },
  { href: '/plataforma/ia',          label: 'Uso de IA',   icon: BrainCircuit },
  { href: '/plataforma/usuarios',    label: 'Usuários',    icon: Users },
]

function isActive(pathname: string, href: string): boolean {
  if (href === '/plataforma') return pathname === '/plataforma'
  return pathname === href || pathname.startsWith(href + '/')
}

function getEmailFromToken(): string {
  try {
    const token = getToken()
    if (!token) return ''
    const parts = token.split('.')
    if (parts.length !== 3) return ''
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(b64)) as { email?: string; sub?: string }
    return payload.email ?? payload.sub ?? ''
  } catch {
    return ''
  }
}

// ── Sidebar ──────────────────────────────────────────────────────────────────

function SidebarHeader({ onClose }: { onClose?: () => void }) {
  return (
    <div className="flex items-center justify-between border-b border-border-light px-4 py-4">
      <div className="flex min-w-0 items-center gap-2">
        <ShieldCheck className="h-6 w-6 shrink-0 text-primary-700" aria-hidden="true" />
        <div className="min-w-0">
          <p className="text-sm font-bold text-text-primary">MenuFlow</p>
          <span className="inline-flex items-center rounded-full bg-primary-700 px-2 py-0.5 text-[10px] font-bold uppercase tracking-wider text-white">
            Plataforma
          </span>
        </div>
      </div>
      {onClose && (
        <button
          type="button"
          onClick={onClose}
          aria-label="Fechar menu"
          className="flex h-11 w-11 shrink-0 items-center justify-center rounded-lg text-text-secondary hover:bg-bg-tertiary"
        >
          <X className="h-5 w-5" aria-hidden="true" />
        </button>
      )}
    </div>
  )
}

function SidebarNav({
  pathname,
  onNavigate,
}: {
  pathname: string
  onNavigate?: () => void
}) {
  return (
    <nav aria-label="Menu da plataforma" className="flex-1 overflow-y-auto p-3">
      <ul className="space-y-1">
        {NAV_ITEMS.map((item) => {
          const active = isActive(pathname, item.href)
          const Icon = item.icon

          if (item.disabled) {
            return (
              <li key={item.href}>
                <span
                  aria-disabled="true"
                  title="Disponível em breve"
                  className="flex min-h-11 cursor-not-allowed items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium text-text-muted"
                >
                  <Icon className="h-5 w-5 shrink-0" aria-hidden="true" />
                  <span className="flex-1">{item.label}</span>
                  <span className="rounded-full bg-bg-tertiary px-2 py-0.5 text-[10px] font-semibold uppercase text-text-muted">
                    Em breve
                  </span>
                </span>
              </li>
            )
          }

          return (
            <li key={item.href}>
              <Link
                href={item.href}
                onClick={onNavigate}
                aria-current={active ? 'page' : undefined}
                className={`flex min-h-11 items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors ${
                  active
                    ? 'bg-primary-700 text-white'
                    : 'text-text-secondary hover:bg-bg-tertiary hover:text-text-primary'
                }`}
              >
                <Icon className="h-5 w-5 shrink-0" aria-hidden="true" />
                <span className="flex-1">{item.label}</span>
                {item.soon && (
                  <span
                    className={`rounded-full px-2 py-0.5 text-[10px] font-semibold uppercase ${
                      active ? 'bg-white/20 text-white' : 'bg-bg-tertiary text-text-muted'
                    }`}
                  >
                    Em breve
                  </span>
                )}
              </Link>
            </li>
          )
        })}
      </ul>
    </nav>
  )
}

// ── Layout ───────────────────────────────────────────────────────────────────

export default function PlataformaLayout({ children }: { children: React.ReactNode }) {
  const { loading } = useSuperAdminGuard()
  const pathname = usePathname()
  const router = useRouter()
  const [mobileOpen, setMobileOpen] = useState(false)
  const [email, setEmail] = useState('')

  useEffect(() => {
    setEmail(getEmailFromToken())
  }, [])

  // fecha o drawer ao trocar de rota
  useEffect(() => {
    setMobileOpen(false)
  }, [pathname])

  const handleLogout = () => {
    logout()
    router.push('/login')
  }

  // Segura a renderizacao ate o guard confirmar SUPER_ADMIN
  // (evita flash do conteudo para usuario sem acesso).
  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center bg-bg-secondary">
        <p role="status" className="text-sm text-text-muted">
          Verificando acesso…
        </p>
      </div>
    )
  }

  return (
    <div className="flex h-screen overflow-hidden bg-bg-secondary">
      {/* Sidebar fixa (desktop) */}
      <aside className="hidden w-64 shrink-0 flex-col border-r border-border-light bg-bg-primary lg:flex">
        <SidebarHeader />
        <SidebarNav pathname={pathname} />
      </aside>

      {/* Drawer (mobile) */}
      {mobileOpen && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div
            className="absolute inset-0 bg-black/40"
            aria-hidden="true"
            onClick={() => setMobileOpen(false)}
          />
          <aside className="absolute inset-y-0 left-0 flex w-64 flex-col border-r border-border-light bg-bg-primary shadow-xl">
            <SidebarHeader onClose={() => setMobileOpen(false)} />
            <SidebarNav pathname={pathname} onNavigate={() => setMobileOpen(false)} />
          </aside>
        </div>
      )}

      <div className="flex min-w-0 flex-1 flex-col overflow-hidden">
        {/* Topbar */}
        <header className="sticky top-0 z-[9] flex h-14 shrink-0 items-center justify-between gap-2 border-b border-border-light bg-bg-primary px-4">
          <div className="flex min-w-0 items-center gap-2">
            <button
              type="button"
              onClick={() => setMobileOpen(true)}
              aria-label="Abrir menu"
              className="flex h-11 w-11 items-center justify-center rounded-lg text-text-secondary hover:bg-bg-tertiary lg:hidden"
            >
              <Menu className="h-5 w-5" aria-hidden="true" />
            </button>
            <span className="truncate text-sm font-medium text-text-secondary">
              Painel da Plataforma
            </span>
          </div>

          <div className="flex shrink-0 items-center gap-3">
            {email && (
              <span className="hidden max-w-[220px] truncate text-sm text-text-secondary sm:block">
                {email}
              </span>
            )}
            <button
              type="button"
              onClick={handleLogout}
              className="flex min-h-11 items-center gap-2 rounded-lg px-3 py-1.5 text-sm font-medium text-text-secondary hover:bg-bg-tertiary"
            >
              <LogOut className="h-4 w-4" aria-hidden="true" />
              Sair
            </button>
          </div>
        </header>

        <div className="flex-1 overflow-auto">{children}</div>
      </div>
    </div>
  )
}
