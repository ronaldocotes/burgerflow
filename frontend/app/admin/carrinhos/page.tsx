'use client'

import { useCallback, useEffect, useId, useState } from 'react'
import { ShoppingCart } from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import type {
  CartRecoveryConfig,
  CartSessionPage,
  CartSessionResponse,
  CartSessionStatus,
} from '@/types/cart-session'

// ── Helpers ───────────────────────────────────────────────────────────────────────────────

function fmtCents(cents: number): string {
  return (cents / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
}

function fmtDate(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
}

// ── Badge de status ──────────────────────────────────────────────────────────────────

const STATUS_MAP: Record<CartSessionStatus, { label: string; className: string }> = {
  ACTIVE:    { label: 'Aberto',     className: 'bg-yellow-100 text-yellow-700' },
  SENT:      { label: 'Enviado',    className: 'bg-blue-100 text-blue-700' },
  RECOVERED: { label: 'Recuperado', className: 'bg-green-100 text-green-700' },
  EXPIRED:   { label: 'Expirado',   className: 'bg-bg-tertiary text-text-secondary' },
}

function CartStatusBadge({ status }: { status: CartSessionStatus }) {
  const { label, className } = STATUS_MAP[status] ?? STATUS_MAP.EXPIRED
  return (
    <span
      className={[
        'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium',
        className,
      ].join(' ')}
    >
      {label}
    </span>
  )
}

// ── KPI Card ──────────────────────────────────────────────────────────────────────────────

interface KpiCardProps {
  label: string
  value: number | null
  bgClass: string
  textClass: string
}

function KpiCard({ label, value, bgClass, textClass }: KpiCardProps) {
  return (
    <div className={['rounded-2xl p-4 shadow-card', bgClass].join(' ')}>
      <p className={['text-2xl font-bold', textClass].join(' ')}>
        {value !== null ? value.toLocaleString('pt-BR') : '—'}
      </p>
      <p className={['mt-1 text-sm', textClass].join(' ')}>{label}</p>
    </div>
  )
}

// ── Skeleton ──────────────────────────────────────────────────────────────────────────────

const TABLE_HEADERS = [
  'Telefone',
  'Total',
  'Status',
  'Criado em',
  'Mensagem enviada em',
  'Recuperado em',
]

function TableSkeleton() {
  return (
    <div
      className="animate-pulse rounded-2xl bg-bg-primary shadow-card overflow-hidden"
      aria-busy="true"
      aria-label="Carregando carrinhos..."
    >
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border-light">
            {TABLE_HEADERS.map((h) => (
              <th
                key={h}
                className="px-4 py-3 text-left text-sm font-semibold uppercase tracking-wider text-text-muted whitespace-nowrap"
              >
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {Array.from({ length: 5 }).map((_, i) => (
            <tr key={i} className="border-b border-border-light">
              {Array.from({ length: 6 }).map((__, j) => (
                <td key={j} className="px-4 py-3">
                  <div className="h-4 rounded bg-bg-tertiary" aria-hidden="true" />
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// ── Painel de configuracao ────────────────────────────────────────────────────────────────────

const RECOVERY_VARS = ['{nome}', '{total}', '{link}']

const DEFAULT_CONFIG: CartRecoveryConfig = {
  cartRecoveryEnabled: false,
  cartRecoveryDelayMinutes: 30,
  cartRecoveryMessage: '',
  cartRecoveryExpiryHours: 24,
}

function ConfigPanel() {
  const enabledId = useId()
  const delayId   = useId()
  const expiryId  = useId()
  const messageId = useId()

  const [draft,   setDraft]   = useState<CartRecoveryConfig>(DEFAULT_CONFIG)
  const [saving,  setSaving]  = useState(false)
  const [error,   setError]   = useState<string | null>(null)
  const [success, setSuccess] = useState(false)

  useEffect(() => {
    void (async () => {
      try {
        const data = await api.get<Partial<CartRecoveryConfig>>('/config')
        setDraft({
          cartRecoveryEnabled:      data.cartRecoveryEnabled      ?? false,
          cartRecoveryDelayMinutes: data.cartRecoveryDelayMinutes ?? 30,
          cartRecoveryMessage:      data.cartRecoveryMessage       ?? '',
          cartRecoveryExpiryHours:  data.cartRecoveryExpiryHours  ?? 24,
        })
      } catch {
        // config load nao-fatal; defaults permanecem
      }
    })()
  }, [])

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    setSaving(true)
    setError(null)
    setSuccess(false)
    try {
      await api.patch<CartRecoveryConfig>('/config', draft)
      setSuccess(true)
      setTimeout(() => setSuccess(false), 3000)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao salvar configuracoes.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="rounded-2xl bg-bg-primary shadow-card p-5 lg:sticky lg:top-8">
      <h3 className="mb-4 text-sm font-semibold text-text-primary">
        Configuracao de Recuperacao
      </h3>

      <form onSubmit={(e) => void handleSave(e)} className="space-y-4">
        {/* Toggle ativo */}
        <div className="flex items-center justify-between gap-3">
          <label
            htmlFor={enabledId}
            className="cursor-pointer select-none text-sm font-medium text-text-primary"
          >
            Ativar recuperacao
          </label>
          <button
            id={enabledId}
            type="button"
            role="switch"
            aria-checked={draft.cartRecoveryEnabled}
            onClick={() =>
              setDraft((d) => ({ ...d, cartRecoveryEnabled: !d.cartRecoveryEnabled }))
            }
            className={[
              'relative inline-flex h-11 w-12 shrink-0 items-center rounded-full transition-colors',
              'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-700',
              draft.cartRecoveryEnabled ? 'bg-primary-700' : 'bg-bg-tertiary',
            ].join(' ')}
          >
            <span
              className={[
                'inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform',
                draft.cartRecoveryEnabled ? 'translate-x-6' : 'translate-x-1',
              ].join(' ')}
              aria-hidden="true"
            />
          </button>
        </div>

        {/* Atraso */}
        <div>
          <label
            htmlFor={delayId}
            className="mb-1 block text-sm font-medium text-text-primary"
          >
            Atraso para envio (minutos)
          </label>
          <input
            id={delayId}
            type="number"
            min={1}
            max={1440}
            value={draft.cartRecoveryDelayMinutes}
            onChange={(e) =>
              setDraft((d) => ({ ...d, cartRecoveryDelayMinutes: Number(e.target.value) }))
            }
            className="input-field w-full"
          />
        </div>

        {/* Expirar apos */}
        <div>
          <label
            htmlFor={expiryId}
            className="mb-1 block text-sm font-medium text-text-primary"
          >
            Expirar apos (horas)
          </label>
          <input
            id={expiryId}
            type="number"
            min={1}
            max={720}
            value={draft.cartRecoveryExpiryHours}
            onChange={(e) =>
              setDraft((d) => ({ ...d, cartRecoveryExpiryHours: Number(e.target.value) }))
            }
            className="input-field w-full"
          />
        </div>

        {/* Mensagem */}
        <div>
          <label
            htmlFor={messageId}
            className="mb-1 block text-sm font-medium text-text-primary"
          >
            Mensagem de recuperacao
          </label>
          <textarea
            id={messageId}
            rows={4}
            value={draft.cartRecoveryMessage}
            onChange={(e) =>
              setDraft((d) => ({ ...d, cartRecoveryMessage: e.target.value }))
            }
            className="input-field w-full resize-none"
            placeholder="Ola {nome}, voce deixou {total} no carrinho. Finalize: {link}"
          />
          <div className="mt-2 flex flex-wrap gap-1.5">
            {RECOVERY_VARS.map((v) => (
              <span
                key={v}
                className="rounded-full border border-border-medium bg-bg-secondary px-2 py-0.5 font-mono text-sm text-text-muted"
              >
                {v}
              </span>
            ))}
          </div>
          <p className="mt-1.5 text-sm text-text-muted">
            Variaveis: {RECOVERY_VARS.join(', ')}
          </p>
        </div>

        {error && (
          <p role="alert" className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
            {error}
          </p>
        )}
        {success && (
          <p role="status" className="rounded-lg bg-green-50 px-3 py-2 text-sm text-green-700">
            Configuracoes salvas com sucesso.
          </p>
        )}

        <button
          type="submit"
          disabled={saving}
          className="btn-primary flex w-full items-center justify-center gap-2 disabled:opacity-50"
        >
          {saving && (
            <span
              className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent"
              aria-hidden="true"
            />
          )}
          {saving ? 'Salvando...' : 'Salvar'}
        </button>
      </form>
    </div>
  )
}

// ── Pagina principal ─────────────────────────────────────────────────────────────────────────────

type LoadState = 'loading' | 'error' | 'ok' | 'empty'
type StatusFilter = 'ALL' | CartSessionStatus

const FILTER_OPTIONS: { value: StatusFilter; label: string }[] = [
  { value: 'ALL',       label: 'Todos' },
  { value: 'ACTIVE',    label: 'Abertos' },
  { value: 'SENT',      label: 'Enviados' },
  { value: 'RECOVERED', label: 'Recuperados' },
  { value: 'EXPIRED',   label: 'Expirados' },
]

export default function CarrinhosPage() {
  const [sessions,     setSessions]     = useState<CartSessionResponse[]>([])
  const [loadState,    setLoadState]    = useState<LoadState>('loading')
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL')
  const [page,         setPage]         = useState(0)
  const [totalPages,   setTotalPages]   = useState(1)
  const [kpis,         setKpis]         = useState<Record<CartSessionStatus, number> | null>(null)

  const loadSessions = useCallback(async (p: number, filter: StatusFilter) => {
    setLoadState('loading')
    try {
      const qs =
        filter === 'ALL'
          ? `page=${p}&size=20`
          : `status=${filter}&page=${p}&size=20`
      const data = await api.get<CartSessionPage>(`/cart-sessions?${qs}`)
      setSessions(data.content)
      setTotalPages(data.totalPages)
      setPage(data.number)
      setLoadState(data.totalElements === 0 ? 'empty' : 'ok')
    } catch {
      setLoadState('error')
    }
  }, [])

  const loadKpis = useCallback(async () => {
    try {
      const statuses: CartSessionStatus[] = ['ACTIVE', 'SENT', 'RECOVERED', 'EXPIRED']
      const results = await Promise.all(
        statuses.map((s) =>
          api.get<CartSessionPage>(`/cart-sessions?status=${s}&page=0&size=1`),
        ),
      )
      const next = {} as Record<CartSessionStatus, number>
      statuses.forEach((s, i) => {
        next[s] = results[i].totalElements
      })
      setKpis(next)
    } catch {
      // KPIs sao nao-fatais
    }
  }, [])

  useEffect(() => {
    void loadSessions(0, statusFilter)
  }, [loadSessions, statusFilter])

  useEffect(() => {
    void loadKpis()
  }, [loadKpis])

  return (
    <div className="flex min-h-screen flex-col bg-bg-secondary">
      <main className="mx-auto w-full max-w-7xl flex-1 px-4 py-8">
        {/* Cabecalho */}
        <div className="mb-6 flex items-center gap-3">
          <ShoppingCart className="h-6 w-6 text-primary-700" aria-hidden="true" />
          <h2 className="text-2xl font-bold text-text-primary">Carrinhos Abandonados</h2>
        </div>

        {/* KPI Cards */}
        <div className="mb-6 grid grid-cols-2 gap-3 sm:grid-cols-4">
          <KpiCard
            label="Abertos"
            value={kpis?.ACTIVE ?? null}
            bgClass="bg-yellow-50"
            textClass="text-yellow-700"
          />
          <KpiCard
            label="Mensagem enviada"
            value={kpis?.SENT ?? null}
            bgClass="bg-blue-50"
            textClass="text-blue-700"
          />
          <KpiCard
            label="Recuperados"
            value={kpis?.RECOVERED ?? null}
            bgClass="bg-green-50"
            textClass="text-green-700"
          />
          <KpiCard
            label="Expirados"
            value={kpis?.EXPIRED ?? null}
            bgClass="bg-bg-primary"
            textClass="text-text-secondary"
          />
        </div>

        {/* Layout principal: tabela + config */}
        <div className="lg:grid lg:grid-cols-[1fr_320px] lg:gap-6">
          {/* Coluna esquerda */}
          <div className="min-w-0">
            {/* Filtros por status */}
            <div className="mb-4 flex flex-wrap gap-2">
              {FILTER_OPTIONS.map(({ value, label }) => (
                <button
                  key={value}
                  onClick={() => setStatusFilter(value)}
                  aria-pressed={statusFilter === value}
                  className={[
                    'inline-flex min-h-11 items-center justify-center rounded-full px-4 text-sm font-medium transition-colors',
                    statusFilter === value
                      ? 'bg-primary-700 text-white'
                      : 'border border-border-light bg-bg-primary text-text-secondary hover:bg-bg-tertiary hover:text-text-primary',
                  ].join(' ')}
                >
                  {label}
                </button>
              ))}
            </div>

            {/* 1. Carregando */}
            {loadState === 'loading' && <TableSkeleton />}

            {/* 2. Erro */}
            {loadState === 'error' && (
              <div
                role="alert"
                className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-12 text-center shadow-card"
              >
                <p className="text-base font-medium text-text-primary">
                  Nao foi possivel carregar os carrinhos.
                </p>
                <button
                  className="btn-primary"
                  onClick={() => void loadSessions(page, statusFilter)}
                >
                  Tentar novamente
                </button>
              </div>
            )}

            {/* 3. Vazio */}
            {loadState === 'empty' && (
              <div className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-16 text-center shadow-card">
                <ShoppingCart className="h-12 w-12 text-text-muted" aria-hidden="true" />
                <p className="text-base font-medium text-text-primary">
                  Nenhum carrinho encontrado
                </p>
                <p className="text-sm text-text-muted">
                  {statusFilter === 'ALL'
                    ? 'Ainda nao ha carrinhos registrados.'
                    : 'Nenhum carrinho com este status.'}
                </p>
              </div>
            )}

            {/* 4. Tabela */}
            {loadState === 'ok' && (
              <div className="rounded-2xl bg-bg-primary shadow-card overflow-hidden">
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-border-light">
                        {TABLE_HEADERS.map((h) => (
                          <th
                            key={h}
                            className="px-4 py-3 text-left text-sm font-semibold uppercase tracking-wider text-text-muted whitespace-nowrap"
                          >
                            {h}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {sessions.map((s) => (
                        <tr
                          key={s.id}
                          className="border-b border-border-light hover:bg-bg-secondary"
                        >
                          <td className="px-4 py-3 font-mono text-xs text-text-primary whitespace-nowrap">
                            {s.customerPhone ?? '—'}
                          </td>
                          <td className="px-4 py-3 font-medium text-text-primary whitespace-nowrap">
                            {fmtCents(s.totalCents)}
                          </td>
                          <td className="px-4 py-3">
                            <CartStatusBadge status={s.status} />
                          </td>
                          <td className="px-4 py-3 text-text-secondary whitespace-nowrap">
                            {fmtDate(s.createdAt)}
                          </td>
                          <td className="px-4 py-3 text-text-secondary whitespace-nowrap">
                            {fmtDate(s.recoveryMessageSentAt)}
                          </td>
                          <td className="px-4 py-3 text-text-secondary whitespace-nowrap">
                            {fmtDate(s.recoveredAt)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>

                {/* Paginacao */}
                {totalPages > 1 && (
                  <div className="flex items-center justify-between border-t border-border-light px-6 py-3">
                    <button
                      disabled={page === 0}
                      onClick={() => void loadSessions(page - 1, statusFilter)}
                      className="btn-outline text-sm disabled:opacity-40"
                    >
                      Anterior
                    </button>
                    <span className="text-sm text-text-muted">
                      Pagina {page + 1} de {totalPages}
                    </span>
                    <button
                      disabled={page >= totalPages - 1}
                      onClick={() => void loadSessions(page + 1, statusFilter)}
                      className="btn-outline text-sm disabled:opacity-40"
                    >
                      Proxima
                    </button>
                  </div>
                )}
              </div>
            )}
          </div>

          {/* Coluna direita: configuracao */}
          <div className="mt-6 lg:mt-0">
            <ConfigPanel />
          </div>
        </div>
      </main>
    </div>
  )
}
