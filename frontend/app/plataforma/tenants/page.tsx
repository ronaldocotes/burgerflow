'use client'

// Empresas — lista de tenants da plataforma + criação (modal Nova Empresa)

import { FormEvent, useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import Link from 'next/link'
import {
  Building2,
  CheckCircle,
  XCircle,
  Plus,
  Search,
  Copy,
  AlertTriangle,
  RefreshCw,
} from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { useModalA11y } from '@/lib/use-modal-a11y'
import { useSuperAdminGuard } from '@/lib/use-super-admin-guard'
import {
  type TenantSummary,
  type TenantCreated,
  type TenantPlan,
  PLANS,
  PLAN_LABELS,
  PLAN_BADGE,
  SLUG_PATTERN,
  formatDate,
} from '@/lib/platform'

// ── Badges ────────────────────────────────────────────────────────────────────

function PlanBadge({ plan }: { plan: TenantPlan }) {
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${PLAN_BADGE[plan] ?? 'bg-bg-tertiary text-text-secondary'}`}>
      {PLAN_LABELS[plan] ?? plan}
    </span>
  )
}

function StatusBadge({ isActive }: { isActive: boolean }) {
  return isActive ? (
    <span className="inline-flex items-center gap-1 rounded-full bg-success-light px-2.5 py-0.5 text-xs font-semibold text-success-dark">
      <CheckCircle className="h-3.5 w-3.5" aria-hidden="true" /> Ativa
    </span>
  ) : (
    <span className="inline-flex items-center gap-1 rounded-full bg-bg-tertiary px-2.5 py-0.5 text-xs font-semibold text-text-secondary">
      <XCircle className="h-3.5 w-3.5" aria-hidden="true" /> Inativa
    </span>
  )
}

// ── Modal Nova Empresa ────────────────────────────────────────────────────────

type CreateState = 'form' | 'loading' | 'done'

function ModalNovaEmpresa({ onClose, onCreated }: { onClose: () => void; onCreated: () => void }) {
  const ref = useRef<HTMLDivElement>(null)
  useModalA11y(ref as React.RefObject<HTMLElement>, onClose)

  const titleId = useId()
  const [slug, setSlug] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [plan, setPlan] = useState<TenantPlan>('BASIC')
  const [adminEmail, setAdminEmail] = useState('')
  const [state, setState] = useState<CreateState>('form')
  const [error, setError] = useState<string | null>(null)
  const [created, setCreated] = useState<TenantCreated | null>(null)
  const [copied, setCopied] = useState(false)

  const slugInvalid = slug.length > 0 && !SLUG_PATTERN.test(slug)
  const canSubmit = !slugInvalid && slug && displayName.trim() && adminEmail.trim()

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!canSubmit) return
    setState('loading')
    setError(null)
    try {
      const res = await api.post<TenantCreated>('/admin/tenants', {
        slug: slug.trim(),
        displayName: displayName.trim(),
        plan,
        adminEmail: adminEmail.trim(),
      })
      setCreated(res)
      setState('done')
      onCreated()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao criar empresa.')
      setState('form')
    }
  }

  async function copyLink() {
    if (!created) return
    await navigator.clipboard.writeText(created.inviteLink)
    setCopied(true)
    setTimeout(() => setCopied(false), 2000)
  }

  return (
    <div className="modal-overlay p-4">
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="w-full max-w-md rounded-2xl bg-bg-primary p-6 shadow-xl"
      >
        <h2 id={titleId} className="mb-5 text-lg font-bold text-text-primary">
          {state === 'done' ? 'Empresa criada' : 'Nova empresa'}
        </h2>

        {state !== 'done' ? (
          <form onSubmit={(e) => void submit(e)} className="space-y-4" noValidate>
            <div>
              <label htmlFor="tenant-slug" className="form-label">Slug</label>
              <input
                id="tenant-slug"
                type="text"
                className="input-field w-full font-mono"
                value={slug}
                onChange={(e) => setSlug(e.target.value.toLowerCase())}
                placeholder="minha-hamburgueria"
                autoComplete="off"
                required
                disabled={state === 'loading'}
                aria-required="true"
                aria-invalid={slugInvalid || undefined}
                aria-describedby="tenant-slug-hint"
              />
              <p id="tenant-slug-hint" className={`mt-1 text-xs ${slugInvalid ? 'text-error' : 'text-text-muted'}`}>
                Apenas letras minúsculas, números e hífens (ex.: black-burguer). Não pode ser alterado depois.
              </p>
            </div>

            <div>
              <label htmlFor="tenant-name" className="form-label">Nome da empresa</label>
              <input
                id="tenant-name"
                type="text"
                className="input-field w-full"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
                placeholder="Black Burguer CR"
                required
                disabled={state === 'loading'}
                aria-required="true"
              />
            </div>

            <div>
              <label htmlFor="tenant-plan" className="form-label">Plano</label>
              <select
                id="tenant-plan"
                className="input-field w-full"
                value={plan}
                onChange={(e) => setPlan(e.target.value as TenantPlan)}
                disabled={state === 'loading'}
              >
                {PLANS.map((p) => (
                  <option key={p} value={p}>{PLAN_LABELS[p]}</option>
                ))}
              </select>
            </div>

            <div>
              <label htmlFor="tenant-admin-email" className="form-label">E-mail do administrador</label>
              <input
                id="tenant-admin-email"
                type="email"
                className="input-field w-full"
                value={adminEmail}
                onChange={(e) => setAdminEmail(e.target.value)}
                placeholder="dono@restaurante.com"
                autoComplete="email"
                required
                disabled={state === 'loading'}
                aria-required="true"
              />
            </div>

            {error && <p role="alert" className="form-error">{error}</p>}

            <div className="flex gap-3 pt-1">
              <button type="button" className="btn-outline flex-1" onClick={onClose} disabled={state === 'loading'}>
                Cancelar
              </button>
              <button
                type="submit"
                className="btn-primary flex flex-1 items-center justify-center gap-2"
                disabled={state === 'loading' || !canSubmit}
              >
                <Plus className="h-4 w-4" aria-hidden="true" />
                {state === 'loading' ? 'Criando...' : 'Criar empresa'}
              </button>
            </div>
          </form>
        ) : (
          <div className="space-y-4">
            <div className="flex items-center gap-2 rounded-lg bg-success-light px-4 py-3 text-sm text-success-dark">
              <CheckCircle className="h-4 w-4 shrink-0" aria-hidden="true" />
              Empresa {created?.displayName} criada. Convite enviado para {created?.adminEmail}.
            </div>
            <div>
              <p className="form-label mb-1">Link do convite do administrador</p>
              <div className="flex gap-2">
                <input
                  readOnly
                  value={created?.inviteLink ?? ''}
                  className="input-field min-w-0 flex-1 text-xs"
                  aria-label="Link do convite"
                  onClick={(e) => (e.target as HTMLInputElement).select()}
                />
                <button
                  type="button"
                  onClick={() => void copyLink()}
                  className="btn-outline inline-flex shrink-0 items-center gap-1.5"
                >
                  <Copy className="h-4 w-4" aria-hidden="true" />
                  {copied ? 'Copiado!' : 'Copiar'}
                </button>
              </div>
              <p role="alert" className="mt-2 flex items-start gap-1.5 text-xs font-medium text-warning-dark">
                <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                Copie o link agora — ele não será mostrado novamente.
              </p>
            </div>
            <button type="button" className="btn-primary w-full" onClick={onClose}>
              Fechar
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

// ── Skeletons ─────────────────────────────────────────────────────────────────

function SkeletonRows({ rows = 5 }: { rows?: number }) {
  return (
    <tbody className="divide-y divide-border-light">
      {Array.from({ length: rows }).map((_, i) => (
        <tr key={i} className="animate-pulse">
          {Array.from({ length: 7 }).map((__, j) => (
            <td key={j} className="px-4 py-3"><div className="h-4 w-20 rounded bg-bg-tertiary" /></td>
          ))}
        </tr>
      ))}
    </tbody>
  )
}

// ── Página ────────────────────────────────────────────────────────────────────

export default function TenantsPage() {
  useSuperAdminGuard()

  const [tenants, setTenants] = useState<TenantSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const searchId = useId()

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setTenants(await api.get<TenantSummary[]>('/admin/tenants'))
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao carregar empresas.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return tenants
    return tenants.filter(
      (t) => t.slug.toLowerCase().includes(q) || t.displayName.toLowerCase().includes(q),
    )
  }, [tenants, query])

  return (
    <main className="mx-auto max-w-6xl p-4 sm:p-6">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-text-primary">Empresas</h1>
          <p className="mt-0.5 text-sm text-text-secondary">
            Restaurantes hospedados na plataforma.
          </p>
        </div>
        <button
          type="button"
          className="btn-primary inline-flex items-center gap-2"
          onClick={() => setModalOpen(true)}
        >
          <Plus className="h-4 w-4" aria-hidden="true" />
          Nova Empresa
        </button>
      </div>

      {/* Busca */}
      <div className="relative mb-4 max-w-sm">
        <label htmlFor={searchId} className="sr-only">Buscar por slug ou nome</label>
        <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-text-muted" aria-hidden="true" />
        <input
          id={searchId}
          type="search"
          className="input-field input-with-leading-icon w-full"
          placeholder="Buscar por slug ou nome..."
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>

      {error ? (
        <div
          role="alert"
          className="flex flex-wrap items-center gap-3 rounded-xl border border-error/30 bg-error-light px-4 py-3"
        >
          <AlertTriangle className="h-5 w-5 shrink-0 text-error-dark" aria-hidden="true" />
          <p className="min-w-0 flex-1 text-sm font-medium text-error-dark">{error}</p>
          <button type="button" onClick={() => void load()} className="btn-outline inline-flex items-center gap-2">
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
            Tentar novamente
          </button>
        </div>
      ) : (
        <>
          {/* Tabela desktop */}
          <div className="hidden overflow-hidden rounded-xl border border-border-light bg-bg-primary md:block">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-border-light bg-bg-secondary">
                <tr>
                  <th scope="col" className="px-4 py-2.5 font-semibold text-text-secondary">Slug</th>
                  <th scope="col" className="px-4 py-2.5 font-semibold text-text-secondary">Nome</th>
                  <th scope="col" className="px-4 py-2.5 font-semibold text-text-secondary">Plano</th>
                  <th scope="col" className="px-4 py-2.5 font-semibold text-text-secondary">Status</th>
                  <th scope="col" className="px-4 py-2.5 font-semibold text-text-secondary">Expira em</th>
                  <th scope="col" className="px-4 py-2.5 font-semibold text-text-secondary">Pedidos/mês</th>
                  <th scope="col" className="px-4 py-2.5 font-semibold text-text-secondary">Ações</th>
                </tr>
              </thead>
              {loading ? (
                <SkeletonRows />
              ) : (
                <tbody className="divide-y divide-border-light">
                  {filtered.length === 0 ? (
                    <tr>
                      <td colSpan={7} className="px-4 py-12 text-center">
                        <Building2 className="mx-auto mb-2 h-8 w-8 text-text-muted" aria-hidden="true" />
                        <p className="text-sm font-medium text-text-primary">
                          {query ? 'Nenhuma empresa encontrada para a busca' : 'Nenhuma empresa cadastrada'}
                        </p>
                        <p className="mt-1 text-sm text-text-secondary">
                          {query ? 'Tente outro termo.' : 'Clique em "Nova Empresa" para criar a primeira.'}
                        </p>
                      </td>
                    </tr>
                  ) : (
                    filtered.map((t) => (
                      <tr key={t.id} className="hover:bg-bg-secondary">
                        <td className="px-4 py-3 font-mono text-xs text-text-secondary">{t.slug}</td>
                        <td className="px-4 py-3 font-medium text-text-primary">{t.displayName}</td>
                        <td className="px-4 py-3"><PlanBadge plan={t.plan} /></td>
                        <td className="px-4 py-3"><StatusBadge isActive={t.isActive} /></td>
                        <td className="px-4 py-3 text-text-secondary">{formatDate(t.expiresAt)}</td>
                        <td className="px-4 py-3 text-text-secondary">
                          {t.ordersThisMonth != null ? (
                            t.ordersThisMonth.toLocaleString('pt-BR')
                          ) : (
                            // uso detalhado fica na página da empresa (evita N+1 na listagem)
                            <span className="text-text-muted" title="Ver detalhes" aria-label="Uso disponível na página de detalhes">—</span>
                          )}
                        </td>
                        <td className="px-4 py-3">
                          <Link
                            href={`/plataforma/tenants/${t.slug}`}
                            className="inline-flex min-h-11 items-center rounded-lg px-2 text-sm font-medium text-primary-700 hover:bg-bg-tertiary"
                          >
                            Detalhes
                          </Link>
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              )}
            </table>
          </div>

          {/* Cards mobile */}
          <div className="space-y-3 md:hidden">
            {loading ? (
              Array.from({ length: 3 }).map((_, i) => (
                <div key={i} className="h-28 animate-pulse rounded-xl bg-bg-tertiary" />
              ))
            ) : filtered.length === 0 ? (
              <div className="flex flex-col items-center gap-2 rounded-xl border border-border-light bg-bg-primary px-4 py-10 text-center">
                <Building2 className="h-8 w-8 text-text-muted" aria-hidden="true" />
                <p className="text-sm font-medium text-text-primary">
                  {query ? 'Nenhuma empresa encontrada para a busca' : 'Nenhuma empresa cadastrada'}
                </p>
              </div>
            ) : (
              filtered.map((t) => (
                <div key={t.id} className="rounded-xl border border-border-light bg-bg-primary p-4">
                  <div className="mb-2 flex items-start justify-between gap-2">
                    <div className="min-w-0">
                      <p className="truncate font-semibold text-text-primary">{t.displayName}</p>
                      <p className="truncate font-mono text-xs text-text-muted">{t.slug}</p>
                    </div>
                    <StatusBadge isActive={t.isActive} />
                  </div>
                  <div className="flex items-center justify-between gap-2">
                    <PlanBadge plan={t.plan} />
                    <Link
                      href={`/plataforma/tenants/${t.slug}`}
                      className="inline-flex min-h-11 items-center rounded-lg px-2 text-sm font-medium text-primary-700"
                    >
                      Ver detalhes
                    </Link>
                  </div>
                </div>
              ))
            )}
          </div>
        </>
      )}

      {modalOpen && (
        <ModalNovaEmpresa
          onClose={() => setModalOpen(false)}
          onCreated={() => void load()}
        />
      )}
    </main>
  )
}
