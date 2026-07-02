'use client'

// Uso de IA — consumo e custo estimado por empresa (Fase 3).
// IMPORTANTE: todo custo exibido é ESTIMADO — calculado pela tabela de preços
// dos modelos, pode divergir da fatura real do provedor. Sempre rotular.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  BrainCircuit,
  RefreshCw,
  AlertTriangle,
  Coins,
  Hash,
  Sigma,
  Info,
} from 'lucide-react'
import {
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
} from 'recharts'
import { api, ApiError } from '@/lib/api'
import { useSuperAdminGuard } from '@/lib/use-super-admin-guard'
import { type AiUsageResponse, formatUsdMicros } from '@/lib/platform'

function currentMonth(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

function formatMonthLabel(month: string): string {
  const [y, m] = month.split('-').map(Number)
  if (!y || !m) return month
  return new Date(y, m - 1, 1).toLocaleDateString('pt-BR', {
    month: 'long',
    year: 'numeric',
  })
}

const nf = new Intl.NumberFormat('pt-BR')

// ── Skeleton ─────────────────────────────────────────────────────────────────

function UsageSkeleton() {
  return (
    <div aria-busy="true" aria-label="Carregando uso de IA" className="space-y-4">
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        {Array.from({ length: 3 }).map((_, i) => (
          <div key={i} className="animate-pulse rounded-xl border border-border-light bg-bg-primary p-4">
            <div className="h-3 w-24 rounded bg-bg-tertiary" />
            <div className="mt-3 h-6 w-32 rounded bg-bg-tertiary" />
          </div>
        ))}
      </div>
      <div className="animate-pulse rounded-xl border border-border-light bg-bg-primary p-4">
        <div className="h-4 w-40 rounded bg-bg-tertiary" />
        <div className="mt-4 space-y-3">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="h-4 w-full rounded bg-bg-tertiary" />
          ))}
        </div>
      </div>
    </div>
  )
}

// ── Cards de resumo ──────────────────────────────────────────────────────────

function SummaryCard({
  icon: Icon,
  label,
  value,
  hint,
}: {
  icon: typeof Coins
  label: string
  value: string
  hint?: string
}) {
  return (
    <div className="rounded-xl border border-border-light bg-bg-primary p-4">
      <div className="flex items-center gap-2 text-text-secondary">
        <Icon className="h-4 w-4 shrink-0" aria-hidden="true" />
        <p className="text-sm font-medium">{label}</p>
      </div>
      <p className="mt-2 text-2xl font-bold text-text-primary">{value}</p>
      {hint && <p className="mt-0.5 text-xs text-text-muted">{hint}</p>}
    </div>
  )
}

// ── Página ───────────────────────────────────────────────────────────────────

export default function UsoIaPage() {
  useSuperAdminGuard()

  const [month, setMonth] = useState(currentMonth)
  const [data, setData] = useState<AiUsageResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)

  const load = useCallback(async (m: string) => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    setLoading(true)
    setError(null)
    try {
      const res = await api.get<AiUsageResponse>(
        `/admin/ai-usage?month=${encodeURIComponent(m)}`,
        controller.signal,
      )
      setData(res)
    } catch (err) {
      if (controller.signal.aborted) return
      setData(null)
      setError(err instanceof ApiError ? err.message : 'Erro ao consultar o uso de IA.')
    } finally {
      if (!controller.signal.aborted) setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load(month)
    return () => abortRef.current?.abort()
  }, [load, month])

  const totals = useMemo(() => {
    if (!data) return null
    const inputTokens = data.entries.reduce((acc, e) => acc + e.inputTokens, 0)
    const outputTokens = data.entries.reduce((acc, e) => acc + e.outputTokens, 0)
    return { inputTokens, outputTokens, tokens: inputTokens + outputTokens }
  }, [data])

  // custo agregado por tenant (em USD) para o gráfico
  const chartData = useMemo(() => {
    if (!data) return []
    const byTenant = new Map<string, number>()
    for (const e of data.entries) {
      byTenant.set(e.tenantSlug, (byTenant.get(e.tenantSlug) ?? 0) + e.estimatedCostUsdMicros)
    }
    return Array.from(byTenant.entries())
      .map(([tenantSlug, micros]) => ({
        tenantSlug,
        custoUsd: micros / 1_000_000,
      }))
      .sort((a, b) => b.custoUsd - a.custoUsd)
  }, [data])

  return (
    <main className="mx-auto max-w-5xl p-4 sm:p-6">
      <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-text-primary">Uso de IA</h1>
          <p className="mt-0.5 text-sm text-text-secondary">
            Consumo e custo estimado de IA por empresa (copiloto e bot).
          </p>
        </div>
        <div className="flex items-end gap-2">
          <div>
            <label htmlFor="ia-mes" className="mb-1 block text-xs font-medium text-text-secondary">
              Mês
            </label>
            <input
              id="ia-mes"
              type="month"
              value={month}
              max={currentMonth()}
              onChange={(e) => e.target.value && setMonth(e.target.value)}
              className="input-field min-h-11"
            />
          </div>
          <button
            type="button"
            className="btn-outline inline-flex min-h-11 items-center gap-2"
            onClick={() => void load(month)}
            disabled={loading}
          >
            <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} aria-hidden="true" />
            Atualizar
          </button>
        </div>
      </div>

      {/* Aviso permanente: custo é estimado */}
      <p className="mb-4 inline-flex items-start gap-1.5 text-xs text-text-muted">
        <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
        Custos calculados pela tabela de preços dos modelos — valores estimados, podem divergir da
        fatura real do provedor.
      </p>

      {loading ? (
        <UsageSkeleton />
      ) : error ? (
        <div
          role="alert"
          className="flex flex-wrap items-center gap-3 rounded-xl border border-error/30 bg-error-light px-4 py-3"
        >
          <AlertTriangle className="h-5 w-5 shrink-0 text-error-dark" aria-hidden="true" />
          <p className="min-w-0 flex-1 text-sm font-medium text-error-dark">{error}</p>
          <button
            type="button"
            onClick={() => void load(month)}
            className="btn-outline inline-flex items-center gap-2"
          >
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
            Tentar novamente
          </button>
        </div>
      ) : !data || data.entries.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-xl border border-border-light bg-bg-primary px-4 py-12 text-center">
          <BrainCircuit className="h-8 w-8 text-text-muted" aria-hidden="true" />
          <p className="text-sm font-medium text-text-primary">
            Nenhum uso de IA em {formatMonthLabel(month)}
          </p>
          <p className="text-sm text-text-secondary">
            Nenhuma chamada de IA foi registrada neste mês. Selecione outro mês acima.
          </p>
        </div>
      ) : (
        <div className="space-y-6">
          {/* Cards de resumo */}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <SummaryCard
              icon={Hash}
              label="Chamadas"
              value={nf.format(data.totalCalls)}
              hint={`em ${formatMonthLabel(month)}`}
            />
            <SummaryCard
              icon={Sigma}
              label="Tokens"
              value={nf.format(totals?.tokens ?? 0)}
              hint={`${nf.format(totals?.inputTokens ?? 0)} entrada · ${nf.format(totals?.outputTokens ?? 0)} saída`}
            />
            <SummaryCard
              icon={Coins}
              label="Custo total (estimado)"
              value={`$${(data.totalCostUsdMicros / 1_000_000).toFixed(2)} USD`}
              hint="estimado pela tabela de preços"
            />
          </div>

          {/* Gráfico de custo por empresa */}
          {chartData.length > 0 && (
            <figure
              aria-label="Gráfico de barras do custo estimado de IA por empresa; os mesmos dados estão na tabela abaixo"
              className="rounded-xl border border-border-light bg-bg-primary p-4"
            >
              <figcaption className="mb-3 text-sm font-semibold text-text-primary">
                Custo estimado por empresa (USD)
              </figcaption>
              <div className="h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={chartData} margin={{ top: 4, right: 8, left: 0, bottom: 4 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="#e5e7eb" />
                    <XAxis
                      dataKey="tenantSlug"
                      tick={{ fontSize: 12 }}
                      interval={0}
                      angle={chartData.length > 6 ? -30 : 0}
                      textAnchor={chartData.length > 6 ? 'end' : 'middle'}
                      height={chartData.length > 6 ? 60 : 30}
                    />
                    <YAxis
                      tick={{ fontSize: 12 }}
                      tickFormatter={(v: number) => `$${v.toFixed(2)}`}
                      width={64}
                    />
                    <Tooltip
                      formatter={(value) => [`$${Number(value).toFixed(4)} (estimado)`, 'Custo']}
                    />
                    <Bar dataKey="custoUsd" fill="#047857" radius={[4, 4, 0, 0]} maxBarSize={48} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </figure>
          )}

          {/* Tabela por tenant/modelo */}
          <div className="overflow-x-auto rounded-xl border border-border-light bg-bg-primary">
            <table className="w-full min-w-[640px] text-sm">
              <caption className="sr-only">
                Uso de IA por empresa e modelo em {formatMonthLabel(month)} — custos estimados
              </caption>
              <thead>
                <tr className="border-b border-border-light text-left text-xs font-semibold uppercase tracking-wide text-text-secondary">
                  <th scope="col" className="px-4 py-3">Empresa</th>
                  <th scope="col" className="px-4 py-3">Modelo</th>
                  <th scope="col" className="px-4 py-3 text-right">Chamadas</th>
                  <th scope="col" className="px-4 py-3 text-right">Tokens (entrada/saída)</th>
                  <th scope="col" className="px-4 py-3 text-right">Custo (estimado)</th>
                </tr>
              </thead>
              <tbody>
                {data.entries.map((e, i) => (
                  <tr
                    key={`${e.tenantSlug}-${e.model}-${i}`}
                    className="border-b border-border-light last:border-b-0"
                  >
                    <td className="px-4 py-3 font-medium text-text-primary">{e.tenantSlug}</td>
                    <td className="px-4 py-3 text-text-secondary">{e.model}</td>
                    <td className="px-4 py-3 text-right tabular-nums text-text-secondary">
                      {nf.format(e.callCount)}
                    </td>
                    <td className="px-4 py-3 text-right tabular-nums text-text-secondary">
                      {nf.format(e.inputTokens)} / {nf.format(e.outputTokens)}
                    </td>
                    <td className="px-4 py-3 text-right font-medium tabular-nums text-text-primary">
                      {formatUsdMicros(e.estimatedCostUsdMicros)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </main>
  )
}
