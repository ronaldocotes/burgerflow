'use client'

// Detalhes da Empresa — cabeçalho (plano/status), módulos (toggle),
// métricas de uso, migrations e danger zone (desativação com confirmação por slug).

import { useCallback, useEffect, useId, useState } from 'react'
import Link from 'next/link'
import { useParams, useRouter } from 'next/navigation'
import {
  ArrowLeft,
  CheckCircle,
  XCircle,
  AlertTriangle,
  RefreshCw,
  DatabaseZap,
  ShieldAlert,
  CalendarClock,
  BarChart3,
} from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { useSuperAdminGuard } from '@/lib/use-super-admin-guard'
import {
  type TenantSummary,
  type TenantPlan,
  type ModuleStatus,
  type MigrationStatus,
  type MigrationOverview,
  type TenantUsageResponse,
  PLANS,
  PLAN_LABELS,
  PLAN_BADGE,
  formatDate,
  formatDateTime,
} from '@/lib/platform'

// ── Erro com retry (reutilizado por seção) ───────────────────────────────────

function SectionError({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div
      role="alert"
      className="flex flex-wrap items-center gap-3 rounded-xl border border-error/30 bg-error-light px-4 py-3"
    >
      <AlertTriangle className="h-5 w-5 shrink-0 text-error-dark" aria-hidden="true" />
      <p className="min-w-0 flex-1 text-sm font-medium text-error-dark">{message}</p>
      <button type="button" onClick={onRetry} className="btn-outline inline-flex items-center gap-2">
        <RefreshCw className="h-4 w-4" aria-hidden="true" />
        Tentar novamente
      </button>
    </div>
  )
}

function SectionSkeleton({ lines = 3 }: { lines?: number }) {
  return (
    <div className="space-y-3 rounded-xl border border-border-light bg-bg-primary p-4">
      {Array.from({ length: lines }).map((_, i) => (
        <div key={i} className="h-8 animate-pulse rounded-lg bg-bg-tertiary" />
      ))}
    </div>
  )
}

// ── Toggle switch acessível ───────────────────────────────────────────────────

function ModuleToggle({
  module,
  onToggle,
  busy,
}: {
  module: ModuleStatus
  onToggle: (key: string, enabled: boolean) => void
  busy: boolean
}) {
  const id = useId()
  return (
    <div className="flex items-center justify-between gap-3 px-4 py-3">
      <div className="min-w-0">
        <p id={id} className="text-sm font-medium text-text-primary">{module.label}</p>
        <p className="mt-0.5 text-xs text-text-muted">
          {module.isOverride ? (
            <span className="inline-flex items-center gap-1 font-semibold text-warning-dark">
              <AlertTriangle className="h-3 w-3" aria-hidden="true" /> Override manual
            </span>
          ) : (
            'Padrão do plano'
          )}
        </p>
      </div>
      <button
        type="button"
        role="switch"
        aria-checked={module.enabled}
        aria-labelledby={id}
        disabled={busy}
        onClick={() => onToggle(module.key, !module.enabled)}
        className={[
          'relative inline-flex h-7 w-12 shrink-0 items-center rounded-full transition-colors duration-200',
          'focus:outline-none focus:ring-2 focus:ring-primary-500 focus:ring-offset-2',
          'disabled:cursor-not-allowed disabled:opacity-50',
          module.enabled ? 'bg-primary-700' : 'bg-border-medium',
        ].join(' ')}
      >
        <span
          aria-hidden="true"
          className={[
            'inline-block h-5 w-5 transform rounded-full bg-white shadow transition-transform duration-200',
            module.enabled ? 'translate-x-6' : 'translate-x-1',
          ].join(' ')}
        />
      </button>
    </div>
  )
}

// ── Página ────────────────────────────────────────────────────────────────────

export default function TenantDetailPage() {
  useSuperAdminGuard()

  const params = useParams<{ slug: string }>()
  const slug = params.slug
  const router = useRouter()

  // Cabeçalho (dados do tenant via lista — não há GET individual na Fase 1)
  const [tenant, setTenant] = useState<TenantSummary | null>(null)
  const [tenantLoading, setTenantLoading] = useState(true)
  const [tenantError, setTenantError] = useState<string | null>(null)

  // Plano (edição inline)
  const [planDraft, setPlanDraft] = useState<TenantPlan>('BASIC')
  const [planSaving, setPlanSaving] = useState(false)
  const [planMsg, setPlanMsg] = useState<string | null>(null)
  const [planErr, setPlanErr] = useState<string | null>(null)

  // Módulos
  const [modules, setModules] = useState<ModuleStatus[]>([])
  const [modulesLoading, setModulesLoading] = useState(true)
  const [modulesError, setModulesError] = useState<string | null>(null)
  const [togglingKey, setTogglingKey] = useState<string | null>(null)
  const [toggleErr, setToggleErr] = useState<string | null>(null)

  // Uso (Fase 2) — erro silencioso: não bloqueia a página se o endpoint falhar
  const [usage, setUsage] = useState<TenantUsageResponse | null>(null)
  const [usageLoading, setUsageLoading] = useState(true)
  const [usageError, setUsageError] = useState(false)

  // Migrations
  const [migrations, setMigrations] = useState<MigrationStatus[]>([])
  const [migrationsLoading, setMigrationsLoading] = useState(true)
  const [migrationsError, setMigrationsError] = useState<string | null>(null)
  const [migrating, setMigrating] = useState(false)
  const [migrateMsg, setMigrateMsg] = useState<string | null>(null)
  const [migrateErr, setMigrateErr] = useState<string | null>(null)

  // Danger zone
  const [confirmSlug, setConfirmSlug] = useState('')
  const [deactivating, setDeactivating] = useState(false)
  const [deactivateErr, setDeactivateErr] = useState<string | null>(null)
  const [reactivating, setReactivating] = useState(false)
  const confirmId = useId()

  const loadTenant = useCallback(async () => {
    setTenantLoading(true)
    setTenantError(null)
    try {
      const list = await api.get<TenantSummary[]>('/admin/tenants')
      const found = list.find((t) => t.slug === slug) ?? null
      if (!found) {
        setTenantError(`Empresa "${slug}" não encontrada.`)
      } else {
        setTenant(found)
        setPlanDraft(found.plan)
      }
    } catch (err) {
      setTenantError(err instanceof ApiError ? err.message : 'Erro ao carregar a empresa.')
    } finally {
      setTenantLoading(false)
    }
  }, [slug])

  const loadModules = useCallback(async () => {
    setModulesLoading(true)
    setModulesError(null)
    try {
      setModules(await api.get<ModuleStatus[]>(`/admin/tenants/${slug}/modules`))
    } catch (err) {
      setModulesError(err instanceof ApiError ? err.message : 'Erro ao carregar módulos.')
    } finally {
      setModulesLoading(false)
    }
  }, [slug])

  const loadUsage = useCallback(async () => {
    setUsageLoading(true)
    setUsageError(false)
    try {
      setUsage(await api.get<TenantUsageResponse>(`/admin/tenants/${slug}/usage`))
    } catch {
      // silencioso por decisão de UX: uso é informativo, não pode bloquear a página
      setUsageError(true)
    } finally {
      setUsageLoading(false)
    }
  }, [slug])

  const loadMigrations = useCallback(async () => {
    setMigrationsLoading(true)
    setMigrationsError(null)
    try {
      // Resposta é um OBJETO agregado ({ ..., tenants: [...] }), não um array.
      // Guarda defensiva: se `tenants` não vier como array, degrada p/ vazio.
      const overview = await api.get<MigrationOverview>('/admin/tenants/migration-status')
      const rows = Array.isArray(overview?.tenants) ? overview.tenants : []
      setMigrations(rows.filter((m) => m.tenantSlug === slug))
    } catch (err) {
      setMigrationsError(err instanceof ApiError ? err.message : 'Erro ao carregar migrations.')
    } finally {
      setMigrationsLoading(false)
    }
  }, [slug])

  // Seções carregam independentes — falha em uma não bloqueia as outras
  useEffect(() => {
    void loadTenant()
    void loadModules()
    void loadUsage()
    void loadMigrations()
  }, [loadTenant, loadModules, loadUsage, loadMigrations])

  async function savePlan() {
    if (!tenant || planDraft === tenant.plan) return
    setPlanSaving(true)
    setPlanErr(null)
    setPlanMsg(null)
    try {
      const updated = await api.patch<TenantSummary>(`/admin/tenants/${slug}`, { plan: planDraft })
      setTenant(updated)
      setPlanDraft(updated.plan)
      setPlanMsg('Plano atualizado.')
      setTimeout(() => setPlanMsg(null), 3000)
      // módulos default dependem do plano → recarrega
      void loadModules()
    } catch (err) {
      setPlanErr(err instanceof ApiError ? err.message : 'Erro ao atualizar o plano.')
    } finally {
      setPlanSaving(false)
    }
  }

  async function toggleModule(key: string, enabled: boolean) {
    setTogglingKey(key)
    setToggleErr(null)
    try {
      const updated = await api.put<ModuleStatus>(
        `/admin/tenants/${slug}/modules/${key}`,
        { enabled },
      )
      setModules((prev) => prev.map((m) => (m.key === key ? updated : m)))
    } catch (err) {
      // rollback visual: estado local não foi alterado antes do PUT
      setToggleErr(err instanceof ApiError ? err.message : 'Erro ao alterar o módulo.')
    } finally {
      setTogglingKey(null)
    }
  }

  async function applyMigrations() {
    setMigrating(true)
    setMigrateErr(null)
    setMigrateMsg(null)
    try {
      await api.post(`/admin/tenants/${slug}/migrate`, {})
      setMigrateMsg('Migrations aplicadas.')
      setTimeout(() => setMigrateMsg(null), 3000)
      void loadMigrations()
    } catch (err) {
      setMigrateErr(err instanceof ApiError ? err.message : 'Erro ao aplicar migrations.')
    } finally {
      setMigrating(false)
    }
  }

  async function deactivate() {
    if (confirmSlug !== slug) return
    setDeactivating(true)
    setDeactivateErr(null)
    try {
      await api.patch<TenantSummary>(`/admin/tenants/${slug}`, { isActive: false })
      router.push('/plataforma/tenants')
    } catch (err) {
      setDeactivateErr(err instanceof ApiError ? err.message : 'Erro ao desativar a empresa.')
      setDeactivating(false)
    }
  }

  async function reactivate() {
    setReactivating(true)
    setDeactivateErr(null)
    try {
      const updated = await api.patch<TenantSummary>(`/admin/tenants/${slug}`, { isActive: true })
      setTenant(updated)
    } catch (err) {
      setDeactivateErr(err instanceof ApiError ? err.message : 'Erro ao reativar a empresa.')
    } finally {
      setReactivating(false)
    }
  }

  const pendingCount = migrations.filter((m) => m.drift).length

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-6">
      <Link
        href="/plataforma/tenants"
        className="mb-4 inline-flex min-h-11 items-center gap-1.5 rounded-lg px-2 text-sm font-medium text-text-secondary hover:bg-bg-tertiary hover:text-text-primary"
      >
        <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        Voltar para Empresas
      </Link>

      {/* ── Cabeçalho ─────────────────────────────────────────────────────── */}
      {tenantLoading ? (
        <SectionSkeleton lines={2} />
      ) : tenantError ? (
        <SectionError message={tenantError} onRetry={() => void loadTenant()} />
      ) : tenant && (
        <section aria-label="Dados da empresa" className="rounded-xl border border-border-light bg-bg-primary p-5">
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div className="min-w-0">
              <div className="flex flex-wrap items-center gap-2">
                <h1 className="text-xl font-bold text-text-primary">{tenant.displayName}</h1>
                {tenant.isActive ? (
                  <span className="inline-flex items-center gap-1 rounded-full bg-success-light px-2.5 py-0.5 text-xs font-semibold text-success-dark">
                    <CheckCircle className="h-3.5 w-3.5" aria-hidden="true" /> Ativa
                  </span>
                ) : (
                  <span className="inline-flex items-center gap-1 rounded-full bg-bg-tertiary px-2.5 py-0.5 text-xs font-semibold text-text-secondary">
                    <XCircle className="h-3.5 w-3.5" aria-hidden="true" /> Inativa
                  </span>
                )}
              </div>
              <p className="mt-1 font-mono text-xs text-text-muted">{tenant.slug}</p>
              <p className="mt-2 inline-flex items-center gap-1.5 text-sm text-text-secondary">
                <CalendarClock className="h-4 w-4 shrink-0 text-text-muted" aria-hidden="true" />
                Expira em: {formatDate(tenant.expiresAt)}
              </p>
            </div>
            <span className={`inline-flex items-center rounded-full px-3 py-1 text-sm font-semibold ${PLAN_BADGE[tenant.plan]}`}>
              {PLAN_LABELS[tenant.plan] ?? tenant.plan}
            </span>
          </div>

          {/* Plano — edição inline */}
          <div className="mt-4 border-t border-border-light pt-4">
            <label htmlFor="plan-select" className="form-label">Plano</label>
            <div className="flex flex-wrap items-center gap-2">
              <select
                id="plan-select"
                className="input-field max-w-[220px]"
                value={planDraft}
                onChange={(e) => setPlanDraft(e.target.value as TenantPlan)}
                disabled={planSaving}
              >
                {PLANS.map((p) => (
                  <option key={p} value={p}>{PLAN_LABELS[p]}</option>
                ))}
              </select>
              <button
                type="button"
                className="btn-primary"
                onClick={() => void savePlan()}
                disabled={planSaving || planDraft === tenant.plan}
              >
                {planSaving ? 'Salvando...' : 'Salvar plano'}
              </button>
            </div>
            {planMsg && (
              <p role="status" className="mt-2 inline-flex items-center gap-1.5 text-sm font-medium text-success-dark">
                <CheckCircle className="h-4 w-4" aria-hidden="true" /> {planMsg}
              </p>
            )}
            {planErr && <p role="alert" className="form-error">{planErr}</p>}
          </div>
        </section>
      )}

      {/* ── Módulos ───────────────────────────────────────────────────────── */}
      <section aria-labelledby="modules-title" className="mt-8">
        <h2 id="modules-title" className="mb-3 text-sm font-semibold uppercase tracking-wide text-text-muted">
          Módulos
        </h2>
        {modulesLoading ? (
          <SectionSkeleton lines={4} />
        ) : modulesError ? (
          <SectionError message={modulesError} onRetry={() => void loadModules()} />
        ) : modules.length === 0 ? (
          <div className="rounded-xl border border-border-light bg-bg-primary px-4 py-8 text-center">
            <p className="text-sm text-text-secondary">Nenhum módulo disponível para esta empresa.</p>
          </div>
        ) : (
          <div className="divide-y divide-border-light rounded-xl border border-border-light bg-bg-primary">
            {modules.map((m) => (
              <ModuleToggle
                key={m.key}
                module={m}
                busy={togglingKey === m.key}
                onToggle={(key, enabled) => void toggleModule(key, enabled)}
              />
            ))}
          </div>
        )}
        {toggleErr && <p role="alert" className="form-error mt-2">{toggleErr}</p>}
      </section>

      {/* ── Métricas de uso (Fase 2) ──────────────────────────────────────── */}
      <section aria-labelledby="usage-title" className="mt-8">
        <h2 id="usage-title" className="mb-3 inline-flex items-center gap-1.5 text-sm font-semibold uppercase tracking-wide text-text-muted">
          <BarChart3 className="h-4 w-4" aria-hidden="true" />
          Métricas de uso
        </h2>
        {usageLoading ? (
          <div aria-busy="true" className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="h-20 animate-pulse rounded-xl bg-bg-tertiary" />
            ))}
          </div>
        ) : usageError || !usage ? (
          // erro silencioso: nota discreta, sem alert — uso não bloqueia a página
          <p className="rounded-xl border border-border-light bg-bg-primary px-4 py-3 text-sm text-text-muted">
            Métricas de uso indisponíveis no momento.
          </p>
        ) : (
          <dl className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <div className="rounded-xl border border-border-light bg-bg-primary p-4">
              <dt className="text-xs font-medium text-text-muted">Pedidos este mês</dt>
              <dd className="mt-1 text-xl font-bold text-text-primary">
                {usage.ordersThisMonth.toLocaleString('pt-BR')}
              </dd>
            </div>
            <div className="rounded-xl border border-border-light bg-bg-primary p-4">
              <dt className="text-xs font-medium text-text-muted">Tamanho do banco</dt>
              <dd className="mt-1 text-xl font-bold text-text-primary">
                {usage.dbSizeMb.toLocaleString('pt-BR')} <span className="text-sm font-medium text-text-secondary">MB</span>
              </dd>
            </div>
            <div className="rounded-xl border border-border-light bg-bg-primary p-4">
              <dt className="text-xs font-medium text-text-muted">Último login</dt>
              <dd className="mt-1 text-sm font-semibold text-text-primary">
                {formatDateTime(usage.lastLoginAt)}
              </dd>
            </div>
            <div className="rounded-xl border border-border-light bg-bg-primary p-4">
              <dt className="text-xs font-medium text-text-muted">Data do snapshot</dt>
              <dd className="mt-1 text-sm font-semibold text-text-primary">
                {formatDate(usage.snapshotDate)}
              </dd>
            </div>
          </dl>
        )}
      </section>

      {/* ── Migrations ────────────────────────────────────────────────────── */}
      <section aria-labelledby="migrations-title" className="mt-8">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
          <h2 id="migrations-title" className="text-sm font-semibold uppercase tracking-wide text-text-muted">
            Migrations do banco
          </h2>
          <button
            type="button"
            className="btn-outline inline-flex items-center gap-2"
            onClick={() => void applyMigrations()}
            disabled={migrating || migrationsLoading}
          >
            <DatabaseZap className="h-4 w-4" aria-hidden="true" />
            {migrating ? 'Aplicando...' : 'Aplicar Migrations'}
          </button>
        </div>
        {migrateMsg && (
          <p role="status" className="mb-2 inline-flex items-center gap-1.5 text-sm font-medium text-success-dark">
            <CheckCircle className="h-4 w-4" aria-hidden="true" /> {migrateMsg}
          </p>
        )}
        {migrateErr && <p role="alert" className="form-error mb-2">{migrateErr}</p>}

        {migrationsLoading ? (
          <SectionSkeleton lines={2} />
        ) : migrationsError ? (
          <SectionError message={migrationsError} onRetry={() => void loadMigrations()} />
        ) : migrations.length === 0 ? (
          <div className="rounded-xl border border-border-light bg-bg-primary px-4 py-8 text-center">
            <p className="text-sm text-text-secondary">Nenhum registro de migration para esta empresa.</p>
          </div>
        ) : (
          <div className="overflow-hidden rounded-xl border border-border-light bg-bg-primary">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-border-light bg-bg-secondary">
                <tr>
                  <th scope="col" className="px-4 py-2.5 font-semibold text-text-secondary">Versão atual</th>
                  <th scope="col" className="px-4 py-2.5 font-semibold text-text-secondary">Versão esperada</th>
                  <th scope="col" className="px-4 py-2.5 font-semibold text-text-secondary">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-light">
                {migrations.map((m, i) => {
                  const upToDate = !m.drift
                  return (
                    <tr key={i}>
                      <td className="px-4 py-3 font-mono text-xs text-text-secondary">{m.appliedVersion ?? '—'}</td>
                      <td className="px-4 py-3 font-mono text-xs text-text-secondary">{m.latestVersion}</td>
                      <td className="px-4 py-3">
                        {upToDate ? (
                          <span className="inline-flex items-center gap-1 text-xs font-medium text-success-dark">
                            <CheckCircle className="h-3.5 w-3.5" aria-hidden="true" /> Atualizado
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1 text-xs font-medium text-warning-dark">
                            <AlertTriangle className="h-3.5 w-3.5" aria-hidden="true" /> Pendente
                          </span>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
        {!migrationsLoading && !migrationsError && pendingCount > 0 && (
          <p className="mt-1.5 text-xs text-warning-dark">
            {pendingCount} migration{pendingCount > 1 ? 's' : ''} pendente{pendingCount > 1 ? 's' : ''}.
          </p>
        )}
      </section>

      {/* ── Danger Zone / Reativação ──────────────────────────────────────── */}
      {tenant && (
        <section aria-labelledby="danger-title" className="mt-8">
          {tenant.isActive ? (
            <div className="rounded-xl border border-error bg-error/5 p-4">
              <h2 id="danger-title" className="inline-flex items-center gap-2 text-sm font-bold text-error-dark">
                <ShieldAlert className="h-4 w-4" aria-hidden="true" />
                Zona de perigo
              </h2>
              <p className="mt-2 text-sm text-text-secondary">
                Desativar empresa bloqueia todos os acessos imediatamente. O banco de dados NÃO é removido.
              </p>
              <div className="mt-4">
                <label htmlFor={confirmId} className="form-label">
                  Digite o slug da empresa para confirmar: <span className="font-mono font-bold">{slug}</span>
                </label>
                <div className="flex flex-wrap items-center gap-2">
                  <input
                    id={confirmId}
                    type="text"
                    className="input-field max-w-[280px] font-mono"
                    value={confirmSlug}
                    onChange={(e) => setConfirmSlug(e.target.value)}
                    placeholder={slug}
                    autoComplete="off"
                    disabled={deactivating}
                  />
                  <button
                    type="button"
                    onClick={() => void deactivate()}
                    disabled={confirmSlug !== slug || deactivating}
                    className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-error px-4 py-2 font-medium text-white transition-colors duration-200 hover:bg-error-dark focus:ring-2 focus:ring-error focus:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    <XCircle className="h-4 w-4" aria-hidden="true" />
                    {deactivating ? 'Desativando...' : 'Desativar Empresa'}
                  </button>
                </div>
              </div>
              {deactivateErr && <p role="alert" className="form-error mt-2">{deactivateErr}</p>}
            </div>
          ) : (
            <div className="rounded-xl border border-border-light bg-bg-primary p-4">
              <h2 id="danger-title" className="inline-flex items-center gap-2 text-sm font-bold text-text-primary">
                <ShieldAlert className="h-4 w-4 text-text-muted" aria-hidden="true" />
                Empresa desativada
              </h2>
              <p className="mt-2 text-sm text-text-secondary">
                Os acessos estão bloqueados. O banco de dados foi preservado — reativar restaura o acesso imediatamente.
              </p>
              <button
                type="button"
                className="btn-primary mt-4 inline-flex items-center gap-2"
                onClick={() => void reactivate()}
                disabled={reactivating}
              >
                <CheckCircle className="h-4 w-4" aria-hidden="true" />
                {reactivating ? 'Reativando...' : 'Reativar Empresa'}
              </button>
              {deactivateErr && <p role="alert" className="form-error mt-2">{deactivateErr}</p>}
            </div>
          )}
        </section>
      )}
    </main>
  )
}
