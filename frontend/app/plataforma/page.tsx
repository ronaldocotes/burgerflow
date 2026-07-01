'use client'

// Visão Geral da Plataforma — KPIs + empresas recentes + semáforo de saúde (placeholder F2)

import { useCallback, useEffect, useState } from 'react'
import Link from 'next/link'
import {
  Building2,
  CheckCircle,
  XCircle,
  DatabaseZap,
  Activity,
  AlertTriangle,
  RefreshCw,
  ArrowRight,
  type LucideIcon,
} from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { useSuperAdminGuard } from '@/lib/use-super-admin-guard'
import {
  type TenantSummary,
  type MigrationStatus,
  PLAN_BADGE,
  PLAN_LABELS,
} from '@/lib/platform'

// ── KPI card ─────────────────────────────────────────────────────────────────

function KpiCard({
  icon: Icon,
  label,
  value,
  accent,
}: {
  icon: LucideIcon
  label: string
  value: string
  accent: string
}) {
  return (
    <div className="rounded-xl border border-border-light bg-bg-primary p-4">
      <div className="flex items-center gap-3">
        <div className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-lg ${accent}`}>
          <Icon className="h-5 w-5" aria-hidden="true" />
        </div>
        <div className="min-w-0">
          <p className="truncate text-xs font-medium text-text-muted">{label}</p>
          <p className="text-xl font-bold text-text-primary">{value}</p>
        </div>
      </div>
    </div>
  )
}

function KpiSkeleton() {
  return (
    <div className="animate-pulse rounded-xl border border-border-light bg-bg-primary p-4">
      <div className="flex items-center gap-3">
        <div className="h-10 w-10 rounded-lg bg-bg-tertiary" />
        <div className="flex-1 space-y-2">
          <div className="h-3 w-24 rounded bg-bg-tertiary" />
          <div className="h-5 w-12 rounded bg-bg-tertiary" />
        </div>
      </div>
    </div>
  )
}

// ── Página ────────────────────────────────────────────────────────────────────

export default function PlataformaPage() {
  useSuperAdminGuard()

  const [tenants, setTenants] = useState<TenantSummary[]>([])
  const [migrations, setMigrations] = useState<MigrationStatus[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      // migrations não bloqueia o painel: falha vira lista vazia
      const [tenantList, migrationList] = await Promise.all([
        api.get<TenantSummary[]>('/admin/tenants'),
        api.get<MigrationStatus[]>('/admin/tenants/migration-status').catch(() => [] as MigrationStatus[]),
      ])
      setTenants(tenantList)
      setMigrations(migrationList)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao carregar dados da plataforma.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const total = tenants.length
  const active = tenants.filter((t) => t.isActive).length
  const inactive = total - active
  const pendingMigrations = migrations.filter(
    (m) => m.currentVersion !== m.expectedVersion,
  ).length
  const recent = tenants.slice(-5).reverse()

  return (
    <main className="mx-auto max-w-6xl p-4 sm:p-6">
      <div className="mb-6">
        <h1 className="text-xl font-bold text-text-primary">Visão Geral</h1>
        <p className="mt-0.5 text-sm text-text-secondary">
          Panorama das empresas hospedadas na plataforma.
        </p>
      </div>

      {error && (
        <div
          role="alert"
          className="mb-6 flex flex-wrap items-center gap-3 rounded-xl border border-error/30 bg-error-light px-4 py-3"
        >
          <AlertTriangle className="h-5 w-5 shrink-0 text-error-dark" aria-hidden="true" />
          <p className="min-w-0 flex-1 text-sm font-medium text-error-dark">{error}</p>
          <button type="button" onClick={() => void load()} className="btn-outline inline-flex items-center gap-2">
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
            Tentar novamente
          </button>
        </div>
      )}

      {/* KPIs */}
      <section aria-label="Indicadores da plataforma" className="mb-8 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {loading ? (
          <>
            <KpiSkeleton /><KpiSkeleton /><KpiSkeleton /><KpiSkeleton />
          </>
        ) : (
          <>
            <KpiCard icon={Building2}   label="Total de empresas"     value={String(total)}             accent="bg-primary-700/10 text-primary-700" />
            <KpiCard icon={CheckCircle} label="Empresas ativas"       value={String(active)}            accent="bg-success-light text-success-dark" />
            <KpiCard icon={XCircle}     label="Empresas inativas"     value={String(inactive)}          accent="bg-bg-tertiary text-text-secondary" />
            <KpiCard icon={DatabaseZap} label="Aguardando migrations" value={String(pendingMigrations)} accent={pendingMigrations > 0 ? 'bg-warning-light text-warning-dark' : 'bg-bg-tertiary text-text-secondary'} />
          </>
        )}
      </section>

      {/* Empresas recentes */}
      <section aria-labelledby="recent-title" className="mb-8">
        <div className="mb-3 flex items-center justify-between gap-2">
          <h2 id="recent-title" className="text-sm font-semibold uppercase tracking-wide text-text-muted">
            Empresas recentes
          </h2>
          <Link
            href="/plataforma/tenants"
            className="inline-flex min-h-11 items-center gap-1 rounded-lg px-2 text-sm font-medium text-primary-700 hover:bg-bg-tertiary"
          >
            Ver todas
            <ArrowRight className="h-4 w-4" aria-hidden="true" />
          </Link>
        </div>

        <div className="overflow-hidden rounded-xl border border-border-light bg-bg-primary">
          {loading ? (
            <div className="space-y-3 p-4">
              {Array.from({ length: 3 }).map((_, i) => (
                <div key={i} className="h-10 animate-pulse rounded-lg bg-bg-tertiary" />
              ))}
            </div>
          ) : recent.length === 0 ? (
            <div className="flex flex-col items-center gap-2 px-4 py-10 text-center">
              <Building2 className="h-8 w-8 text-text-muted" aria-hidden="true" />
              <p className="text-sm font-medium text-text-primary">Nenhuma empresa cadastrada</p>
              <p className="text-sm text-text-secondary">
                Crie a primeira empresa em <Link href="/plataforma/tenants" className="font-medium text-primary-700 underline">Empresas</Link>.
              </p>
            </div>
          ) : (
            <table className="w-full text-left text-sm">
              <thead className="border-b border-border-light bg-bg-secondary">
                <tr>
                  <th scope="col" className="px-4 py-2.5 font-semibold text-text-secondary">Empresa</th>
                  <th scope="col" className="px-4 py-2.5 font-semibold text-text-secondary">Slug</th>
                  <th scope="col" className="px-4 py-2.5 font-semibold text-text-secondary">Plano</th>
                  <th scope="col" className="px-4 py-2.5 font-semibold text-text-secondary">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-light">
                {recent.map((t) => (
                  <tr key={t.id}>
                    <td className="px-4 py-3">
                      <Link href={`/plataforma/tenants/${t.slug}`} className="font-medium text-primary-700 hover:underline">
                        {t.displayName}
                      </Link>
                    </td>
                    <td className="px-4 py-3 font-mono text-xs text-text-secondary">{t.slug}</td>
                    <td className="px-4 py-3">
                      <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${PLAN_BADGE[t.plan]}`}>
                        {PLAN_LABELS[t.plan] ?? t.plan}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      {t.isActive ? (
                        <span className="inline-flex items-center gap-1 text-xs font-medium text-success-dark">
                          <CheckCircle className="h-3.5 w-3.5" aria-hidden="true" /> Ativa
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 text-xs font-medium text-text-muted">
                          <XCircle className="h-3.5 w-3.5" aria-hidden="true" /> Inativa
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
        {!loading && recent.length > 0 && (
          <p className="mt-1.5 text-xs text-text-muted">
            Exibindo as últimas {recent.length} empresas da lista.
          </p>
        )}
      </section>

      {/* Semáforo de saúde — placeholder F2 */}
      <section aria-labelledby="health-title">
        <h2 id="health-title" className="mb-3 text-sm font-semibold uppercase tracking-wide text-text-muted">
          Saúde das integrações
        </h2>
        <div className="flex items-center gap-3 rounded-xl border border-dashed border-border-medium bg-bg-primary px-4 py-5">
          <Activity className="h-6 w-6 shrink-0 text-text-muted" aria-hidden="true" />
          <div>
            <p className="text-sm font-medium text-text-primary">Em breve</p>
            <p className="text-sm text-text-secondary">
              Semáforo de saúde de iFood, OpenDelivery, WAHA e LiteLLM chega na Fase 2.
            </p>
          </div>
        </div>
      </section>
    </main>
  )
}
