'use client'

import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import {
  ResponsiveContainer,
  ComposedChart,
  Line,
  XAxis,
  YAxis,
  Tooltip,
  CartesianGrid,
  Legend,
  ReferenceLine,
} from 'recharts'
import {
  ExternalLink,
  Lock,
  Plug,
  Plus,
  RefreshCw,
  ShieldCheck,
  Trash2,
  TrendingUp,
} from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { useModalA11y } from '@/lib/use-modal-a11y'
import type { AdAccountResponse, AdMetricsResponse } from '@/types/ads'

// ── Helpers de formatacao ─────────────────────────────────────────────────────
// Dinheiro SEMPRE a partir de centavos inteiros — nunca float bruto.

function fmtMoney(cents: number, currency: string | null): string {
  const cur = currency ?? 'BRL'
  const locale = cur === 'BRL' ? 'pt-BR' : 'en-US'
  try {
    return (cents / 100).toLocaleString(locale, { style: 'currency', currency: cur })
  } catch {
    return `${(cents / 100).toFixed(2)} ${cur}`
  }
}

function fmtInt(n: number): string {
  return n.toLocaleString('pt-BR')
}

/** ctrMilli = CTR% x 1000 (1500 → "1,5%") */
function fmtCtrMilli(milli: number): string {
  return `${(milli / 1000).toLocaleString('pt-BR', {
    minimumFractionDigits: 1,
    maximumFractionDigits: 2,
  })}%`
}

function fmtDateTime(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
}

/** YYYY-MM-DD → DD/MM (rotulo do eixo X) */
function fmtDayLabel(date: string): string {
  return `${date.slice(8, 10)}/${date.slice(5, 7)}`
}

// ── Badge de status da conta ──────────────────────────────────────────────────

const STATUS_META: Record<string, { label: string; cls: string }> = {
  CONNECTED: { label: 'Conectada', cls: 'bg-green-100 text-green-700' },
  EXPIRED: { label: 'Expirada', cls: 'bg-yellow-100 text-yellow-800' },
  ERROR: { label: 'Erro', cls: 'bg-red-100 text-red-700' },
  DISCONNECTED: { label: 'Desconectada', cls: 'bg-bg-tertiary text-text-secondary' },
}

function AccountStatusBadge({ status }: { status: string }) {
  const meta = STATUS_META[status] ?? STATUS_META.DISCONNECTED
  return (
    <span className={['inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium', meta.cls].join(' ')}>
      {meta.label}
    </span>
  )
}

// ── Skeletons ─────────────────────────────────────────────────────────────────

function AccountsSkeleton() {
  return (
    <div
      className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3"
      aria-busy="true"
      aria-label="Carregando contas de anuncio..."
    >
      {Array.from({ length: 2 }).map((_, i) => (
        <div key={i} className="animate-pulse rounded-2xl bg-bg-primary p-5 shadow-card">
          <div className="mb-3 h-5 w-2/3 rounded bg-bg-tertiary" aria-hidden="true" />
          <div className="mb-2 h-4 w-1/2 rounded bg-bg-tertiary" aria-hidden="true" />
          <div className="h-4 w-1/3 rounded bg-bg-tertiary" aria-hidden="true" />
        </div>
      ))}
    </div>
  )
}

function MetricsSkeleton() {
  return (
    <div aria-busy="true" aria-label="Carregando metricas...">
      <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="animate-pulse rounded-2xl bg-bg-primary p-4 shadow-card">
            <div className="mb-2 h-3 w-2/3 rounded bg-bg-tertiary" aria-hidden="true" />
            <div className="h-6 w-1/2 rounded bg-bg-tertiary" aria-hidden="true" />
          </div>
        ))}
      </div>
      <div className="animate-pulse rounded-2xl bg-bg-primary p-5 shadow-card">
        <div className="h-72 rounded bg-bg-tertiary" aria-hidden="true" />
      </div>
    </div>
  )
}

// ── Modal: conectar conta (colar System User Token) ──────────────────────────

interface ConnectModalProps {
  onClose: () => void
  onConnected: () => void
}

function ConnectModal({ onClose, onConnected }: ConnectModalProps) {
  const ref = useRef<HTMLDivElement>(null)
  useModalA11y(ref as React.RefObject<HTMLElement | null>, onClose)
  const titleId = useId()
  const [token, setToken] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (saving) return
    setError(null)
    setSaving(true)
    try {
      await api.post<AdAccountResponse[]>('/ads/accounts', { token: token.trim() })
      // Seguranca: o token nunca fica em memoria alem do necessario.
      setToken('')
      onConnected()
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 503) {
          setError('A Meta esta indisponivel no momento. Tente novamente em alguns instantes.')
        } else {
          // 400 (token invalido/expirado) e 403 (modulo/permissao) ja vem com
          // mensagem clara do backend.
          setError(err.message)
        }
      } else {
        setError('Erro ao conectar a conta. Tente novamente.')
      }
      setSaving(false)
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
    >
      <div className="absolute inset-0 bg-black/50" aria-hidden="true" onClick={onClose} />
      <div
        ref={ref}
        className="relative z-10 w-full max-w-lg overflow-y-auto max-h-[90vh] rounded-2xl bg-bg-primary shadow-dropdown"
      >
        <div className="flex items-center justify-between border-b border-border-light px-6 py-4">
          <h2 id={titleId} className="text-base font-semibold text-text-primary">
            Conectar conta da Meta
          </h2>
          <button
            onClick={onClose}
            aria-label="Fechar"
            className="rounded-lg p-1 text-text-muted hover:bg-bg-tertiary"
          >
            <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        <form onSubmit={(e) => void handleSubmit(e)} className="space-y-5 px-6 py-5">
          {/* Pre-requisitos */}
          <div className="rounded-xl border border-border-light bg-bg-secondary p-3">
            <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-text-muted">
              Antes de comecar
            </p>
            <p className="text-sm text-text-secondary">
              Voce precisa de um <span className="font-medium text-text-primary">Business Manager</span>,
              uma <span className="font-medium text-text-primary">Pagina do Facebook</span> e uma{' '}
              <span className="font-medium text-text-primary">conta de anuncio</span> com forma de
              pagamento configurada.
            </p>
            <p className="mt-2 flex items-start gap-1.5 text-sm text-text-secondary">
              <ExternalLink className="mt-0.5 h-4 w-4 shrink-0 text-text-muted" aria-hidden="true" />
              <span>
                Gere o token em: Configuracoes do negocio &rarr; Usuarios do Sistema &rarr; Gerar
                Token (com permissao de leitura de anuncios).
              </span>
            </p>
          </div>

          {/* Token */}
          <div>
            <label htmlFor="ads-token" className="mb-1 block text-sm font-medium text-text-primary">
              System User Token
            </label>
            <input
              id="ads-token"
              type="password"
              required
              minLength={20}
              maxLength={500}
              autoComplete="off"
              value={token}
              onChange={(e) => setToken(e.target.value)}
              className="input-field w-full font-mono"
              placeholder="Cole aqui o token gerado no Business Manager"
            />
            <p className="mt-1.5 flex items-start gap-1.5 text-sm text-text-muted">
              <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
              <span>
                O token e enviado com seguranca e nunca e exibido de volta. Um mesmo token pode
                conectar varias contas de anuncio.
              </span>
            </p>
          </div>

          {error && (
            <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
              {error}
            </p>
          )}

          <div className="flex justify-end gap-3 pt-1">
            <button type="button" onClick={onClose} className="btn-outline">
              Cancelar
            </button>
            <button
              type="submit"
              disabled={saving}
              className="btn-primary flex items-center gap-2 disabled:opacity-50"
            >
              {saving && (
                <span
                  className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent"
                  aria-hidden="true"
                />
              )}
              {saving ? 'Conectando...' : 'Conectar'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

// ── Modal: confirmar desconexao ───────────────────────────────────────────────

interface DisconnectModalProps {
  account: AdAccountResponse
  onClose: () => void
  onDisconnected: () => void
}

function DisconnectModal({ account, onClose, onDisconnected }: DisconnectModalProps) {
  const ref = useRef<HTMLDivElement>(null)
  useModalA11y(ref as React.RefObject<HTMLElement | null>, onClose)
  const titleId = useId()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handle() {
    setLoading(true)
    setError(null)
    try {
      await api.del(`/ads/accounts/${account.id}`)
      onDisconnected()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao desconectar a conta.')
      setLoading(false)
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby={titleId}
    >
      <div className="absolute inset-0 bg-black/50" aria-hidden="true" onClick={onClose} />
      <div ref={ref} className="relative z-10 w-full max-w-sm rounded-2xl bg-bg-primary p-6 shadow-dropdown">
        <h2 id={titleId} className="mb-2 text-base font-semibold text-text-primary">
          Desconectar conta?
        </h2>
        <p className="mb-5 text-sm text-text-secondary">
          A conta{' '}
          <span className="font-semibold text-text-primary">
            {account.accountName ?? `••••${account.accountIdLast4}`}
          </span>{' '}
          deixara de coletar metricas. Voce pode reconecta-la depois com um novo token.
        </p>
        {error && (
          <p role="alert" className="mb-4 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
            {error}
          </p>
        )}
        <div className="flex justify-end gap-3">
          <button onClick={onClose} className="btn-outline">
            Cancelar
          </button>
          <button
            onClick={() => void handle()}
            disabled={loading}
            className="flex min-h-11 items-center gap-2 rounded-lg bg-red-600 px-4 py-2 font-medium text-white hover:bg-red-700 disabled:opacity-50"
          >
            {loading && (
              <span
                className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent"
                aria-hidden="true"
              />
            )}
            Desconectar
          </button>
        </div>
      </div>
    </div>
  )
}

// ── Dashboard de metricas (Fase 8.1) ─────────────────────────────────────────

const PERIODS = [7, 30] as const

function MetricsDashboard({ account }: { account: AdAccountResponse }) {
  const [days, setDays] = useState<(typeof PERIODS)[number]>(30)
  const [metrics, setMetrics] = useState<AdMetricsResponse[]>([])
  const [loadState, setLoadState] = useState<'loading' | 'error' | 'ok' | 'empty'>('loading')

  const load = useCallback(async () => {
    setLoadState('loading')
    try {
      const data = await api.get<AdMetricsResponse[]>(`/ads/accounts/${account.id}/metrics?days=${days}`)
      setMetrics(data)
      setLoadState(data.length === 0 ? 'empty' : 'ok')
    } catch {
      setLoadState('error')
    }
  }, [account.id, days])

  useEffect(() => {
    void load()
  }, [load])

  // KPIs agregados do periodo — sempre a partir dos centavos inteiros.
  const kpis = useMemo(() => {
    const spendCents = metrics.reduce((acc, m) => acc + m.spendCents, 0)
    const impressions = metrics.reduce((acc, m) => acc + m.impressions, 0)
    const reach = metrics.reduce((acc, m) => acc + m.reach, 0)
    const clicks = metrics.reduce((acc, m) => acc + m.clicks, 0)
    // CTR/CPC medios recalculados dos totais (mais fiel que media das medias).
    const ctrMilli = impressions > 0 ? Math.round((clicks / impressions) * 100_000) : 0
    const cpcCents = clicks > 0 ? Math.round(spendCents / clicks) : 0
    return { spendCents, impressions, reach, clicks, ctrMilli, cpcCents }
  }, [metrics])

  const chartData = useMemo(
    () =>
      metrics.map((m) => ({
        label: fmtDayLabel(m.date),
        // Divisao por 100 SOMENTE para escala do grafico (exibicao via formatter).
        gasto: m.spendCents / 100,
        cliques: m.clicks,
        isPartial: m.isPartial,
      })),
    [metrics],
  )

  const partialLabel = useMemo(
    () => chartData.find((d) => d.isPartial)?.label ?? null,
    [chartData],
  )

  const currency = account.currency

  const kpiCards: { label: string; value: string }[] = [
    { label: 'Gasto total', value: fmtMoney(kpis.spendCents, currency) },
    { label: 'Impressoes', value: fmtInt(kpis.impressions) },
    { label: 'Alcance', value: fmtInt(kpis.reach) },
    { label: 'Cliques', value: fmtInt(kpis.clicks) },
    { label: 'CTR medio', value: fmtCtrMilli(kpis.ctrMilli) },
    { label: 'CPC medio', value: fmtMoney(kpis.cpcCents, currency) },
  ]

  return (
    <section aria-label="Metricas da conta" className="mt-8">
      {/* Cabecalho + seletor de periodo */}
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h3 className="text-lg font-semibold text-text-primary">
          Metricas &mdash; {account.accountName ?? `••••${account.accountIdLast4}`}
        </h3>
        <div className="flex gap-2" role="group" aria-label="Periodo das metricas">
          {PERIODS.map((p) => (
            <button
              key={p}
              onClick={() => setDays(p)}
              aria-pressed={days === p}
              className={[
                'min-h-11 rounded-lg px-4 py-2 text-sm font-medium transition-colors',
                days === p
                  ? 'bg-primary-700 text-white'
                  : 'border border-border-medium text-text-secondary hover:bg-bg-tertiary hover:text-text-primary',
              ].join(' ')}
            >
              {p} dias
            </button>
          ))}
        </div>
      </div>

      {/* Estados */}
      {loadState === 'loading' && <MetricsSkeleton />}

      {loadState === 'error' && (
        <div
          role="alert"
          className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-12 text-center shadow-card"
        >
          <p className="text-base font-medium text-text-primary">
            Nao foi possivel carregar as metricas.
          </p>
          <button className="btn-primary" onClick={() => void load()}>
            Tentar novamente
          </button>
        </div>
      )}

      {loadState === 'empty' && (
        <div className="flex flex-col items-center gap-3 rounded-2xl bg-bg-primary p-16 text-center shadow-card">
          <TrendingUp className="h-12 w-12 text-text-muted" aria-hidden="true" />
          <p className="text-base font-medium text-text-primary">Sem dados ainda</p>
          <p className="text-sm text-text-muted">
            As metricas sao coletadas de hora em hora. Volte em instantes.
          </p>
        </div>
      )}

      {loadState === 'ok' && (
        <>
          {/* KPIs */}
          <div className="mb-4 grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
            {kpiCards.map((k) => (
              <div key={k.label} className="rounded-2xl bg-bg-primary p-4 shadow-card">
                <p className="text-xs font-semibold uppercase tracking-wider text-text-muted">
                  {k.label}
                </p>
                <p className="mt-1 text-lg font-bold text-text-primary" title={k.value}>
                  {k.value}
                </p>
              </div>
            ))}
          </div>

          {/* Grafico gasto x cliques por dia */}
          <div className="rounded-2xl bg-bg-primary p-5 shadow-card">
            <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
              <h4 className="text-sm font-semibold text-text-primary">Gasto e cliques por dia</h4>
              {partialLabel && (
                <p className="text-xs text-text-muted">
                  Linha tracejada em {partialLabel} = hoje (parcial, ainda consolidando)
                </p>
              )}
            </div>
            <div className="h-72">
              <ResponsiveContainer width="100%" height="100%">
                <ComposedChart data={chartData} margin={{ left: 8, right: 8, top: 8, bottom: 8 }}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--border-light)" />
                  <XAxis dataKey="label" tick={{ fontSize: 12 }} />
                  <YAxis
                    yAxisId="gasto"
                    tick={{ fontSize: 12 }}
                    width={56}
                    allowDecimals={false}
                  />
                  <YAxis
                    yAxisId="cliques"
                    orientation="right"
                    tick={{ fontSize: 12 }}
                    width={44}
                    allowDecimals={false}
                  />
                  <Tooltip
                    formatter={(value, name) => [
                      name === 'Gasto'
                        ? fmtMoney(Math.round(Number(value) * 100), currency)
                        : fmtInt(Number(value)),
                      String(name),
                    ]}
                    labelFormatter={(label) =>
                      label === partialLabel ? `${String(label)} — hoje (parcial)` : String(label)
                    }
                    contentStyle={{
                      borderRadius: 8,
                      borderColor: 'var(--border-light)',
                      backgroundColor: 'var(--bg-primary)',
                      color: 'var(--text-primary)',
                    }}
                  />
                  <Legend />
                  <Line
                    yAxisId="gasto"
                    type="monotone"
                    dataKey="gasto"
                    name="Gasto"
                    stroke="#047857"
                    strokeWidth={2}
                    dot={false}
                  />
                  <Line
                    yAxisId="cliques"
                    type="monotone"
                    dataKey="cliques"
                    name="Cliques"
                    stroke="#2563eb"
                    strokeWidth={2}
                    dot={false}
                  />
                  {partialLabel && (
                    <ReferenceLine
                      yAxisId="gasto"
                      x={partialLabel}
                      stroke="var(--text-muted)"
                      strokeDasharray="4 4"
                    />
                  )}
                </ComposedChart>
              </ResponsiveContainer>
            </div>
          </div>
        </>
      )}
    </section>
  )
}

// ── Pagina principal ──────────────────────────────────────────────────────────

export default function TrafegoPagoPage() {
  const [accounts, setAccounts] = useState<AdAccountResponse[]>([])
  const [loadState, setLoadState] = useState<'loading' | 'error' | 'ok' | 'empty' | 'forbidden'>(
    'loading',
  )
  const [forbiddenMsg, setForbiddenMsg] = useState<string | null>(null)
  const [connectOpen, setConnectOpen] = useState(false)
  const [disconnectTarget, setDisconnectTarget] = useState<AdAccountResponse | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoadState('loading')
    try {
      const data = await api.get<AdAccountResponse[]>('/ads/accounts')
      setAccounts(data)
      setLoadState(data.length === 0 ? 'empty' : 'ok')
      // Seleciona a primeira conta CONNECTED por padrao (mantem selecao valida).
      setSelectedId((prev) => {
        if (prev && data.some((a) => a.id === prev && a.status === 'CONNECTED')) return prev
        return data.find((a) => a.status === 'CONNECTED')?.id ?? null
      })
    } catch (err) {
      if (err instanceof ApiError && err.status === 403) {
        setForbiddenMsg(err.message)
        setLoadState('forbidden')
      } else {
        setLoadState('error')
      }
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  const selected = accounts.find((a) => a.id === selectedId) ?? null

  return (
    <div className="flex min-h-screen flex-col bg-bg-secondary">
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8">
        {/* Cabecalho */}
        <div className="mb-6 flex flex-wrap items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <TrendingUp className="h-6 w-6 text-primary-700" aria-hidden="true" />
            <h2 className="text-2xl font-bold text-text-primary">Trafego Pago</h2>
          </div>
          {loadState === 'ok' && (
            <button onClick={() => setConnectOpen(true)} className="btn-primary flex items-center gap-2">
              <Plus className="h-4 w-4" aria-hidden="true" />
              Conectar conta
            </button>
          )}
        </div>

        {/* 1. Carregando */}
        {loadState === 'loading' && <AccountsSkeleton />}

        {/* 2. Erro */}
        {loadState === 'error' && (
          <div
            role="alert"
            className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-12 text-center shadow-card"
          >
            <p className="text-base font-medium text-text-primary">
              Nao foi possivel carregar as contas de anuncio.
            </p>
            <button className="btn-primary" onClick={() => void load()}>
              Tentar novamente
            </button>
          </div>
        )}

        {/* 3. Modulo nao habilitado / sem permissao */}
        {loadState === 'forbidden' && (
          <div className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-16 text-center shadow-card">
            <Lock className="h-12 w-12 text-text-muted" aria-hidden="true" />
            <p className="text-base font-medium text-text-primary">Acesso indisponivel</p>
            <p className="max-w-md text-sm text-text-secondary">
              {forbiddenMsg ??
                "O modulo 'Trafego Pago' nao esta habilitado para o seu plano. Fale com o suporte para ativar."}
            </p>
          </div>
        )}

        {/* 4. Onboarding: nenhuma conta conectada */}
        {loadState === 'empty' && (
          <div className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-16 text-center shadow-card">
            <Plug className="h-12 w-12 text-text-muted" aria-hidden="true" />
            <p className="text-base font-medium text-text-primary">
              Nenhuma conta da Meta conectada
            </p>
            <p className="max-w-md text-sm text-text-muted">
              Conecte sua conta de anuncios da Meta para acompanhar gasto, alcance, cliques, CTR e
              CPC direto no MenuFlow. Voce vai precisar de um Business Manager, uma Pagina do
              Facebook e uma conta de anuncio com forma de pagamento.
            </p>
            <button onClick={() => setConnectOpen(true)} className="btn-primary flex items-center gap-2">
              <Plus className="h-4 w-4" aria-hidden="true" />
              Conectar conta da Meta
            </button>
            <p className="text-xs text-text-muted">
              O token e gerado em: Configuracoes do negocio &rarr; Usuarios do Sistema &rarr; Gerar
              Token.
            </p>
          </div>
        )}

        {/* 5. Contas conectadas */}
        {loadState === 'ok' && (
          <>
            <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3" role="list" aria-label="Contas de anuncio">
              {accounts.map((a) => {
                const isSelected = a.id === selectedId
                const selectable = a.status === 'CONNECTED'
                return (
                  <div
                    key={a.id}
                    role="listitem"
                    className={[
                      'rounded-2xl bg-bg-primary p-5 shadow-card transition-shadow',
                      isSelected ? 'ring-2 ring-primary-700' : '',
                    ].join(' ')}
                  >
                    <div className="mb-2 flex items-start justify-between gap-2">
                      <p className="font-semibold text-text-primary truncate" title={a.accountName ?? undefined}>
                        {a.accountName ?? 'Conta sem nome'}
                      </p>
                      <AccountStatusBadge status={a.status} />
                    </div>
                    <p className="font-mono text-sm text-text-secondary">
                      ••••{a.accountIdLast4}
                      {a.currency ? ` · ${a.currency}` : ''}
                    </p>
                    <p className="mt-1 text-xs text-text-muted">
                      Conectada em {fmtDateTime(a.connectedAt)}
                    </p>
                    <div className="mt-4 flex flex-wrap items-center gap-2">
                      {selectable && !isSelected && (
                        <button
                          onClick={() => setSelectedId(a.id)}
                          className="flex min-h-10 items-center gap-1 rounded-lg border border-primary-700 px-3 py-1.5 text-xs font-medium text-primary-700 hover:bg-primary-700 hover:text-white transition-colors"
                        >
                          <TrendingUp className="h-3.5 w-3.5" aria-hidden="true" />
                          Ver metricas
                        </button>
                      )}
                      {a.status === 'EXPIRED' && (
                        <button
                          onClick={() => setConnectOpen(true)}
                          className="flex min-h-10 items-center gap-1 rounded-lg border border-yellow-600 px-3 py-1.5 text-xs font-medium text-yellow-700 hover:bg-yellow-600 hover:text-white transition-colors"
                        >
                          <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
                          Reconectar
                        </button>
                      )}
                      <button
                        onClick={() => setDisconnectTarget(a)}
                        aria-label={`Desconectar conta ${a.accountName ?? a.accountIdLast4}`}
                        className="flex min-h-10 items-center gap-1 rounded-lg border border-border-medium px-3 py-1.5 text-xs font-medium text-text-secondary hover:bg-bg-tertiary transition-colors"
                      >
                        <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
                        Desconectar
                      </button>
                    </div>
                  </div>
                )
              })}
            </div>

            {/* Dashboard da conta selecionada */}
            {selected && selected.status === 'CONNECTED' && <MetricsDashboard account={selected} />}
            {!selected && (
              <div className="mt-8 rounded-2xl bg-bg-primary p-8 text-center shadow-card">
                <p className="text-sm text-text-secondary">
                  Nenhuma conta ativa para exibir metricas. Reconecte uma conta expirada ou conecte
                  uma nova.
                </p>
              </div>
            )}
          </>
        )}
      </main>

      {connectOpen && (
        <ConnectModal
          onClose={() => setConnectOpen(false)}
          onConnected={() => {
            setConnectOpen(false)
            void load()
          }}
        />
      )}
      {disconnectTarget && (
        <DisconnectModal
          account={disconnectTarget}
          onClose={() => setDisconnectTarget(null)}
          onDisconnected={() => {
            setDisconnectTarget(null)
            void load()
          }}
        />
      )}
    </div>
  )
}
