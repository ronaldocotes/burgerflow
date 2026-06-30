'use client'

import { useState, useEffect, useCallback, useRef } from 'react'
import { useRouter } from 'next/navigation'
import {
  PieChart,
  Pie,
  Cell,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts'
import type { PieLabelRenderProps } from 'recharts'
import {
  TrendingUp,
  TrendingDown,
  ShoppingBag,
  Receipt,
  Plus,
  Pencil,
  Trash2,
  X,
  ChevronLeft,
  ChevronRight,
} from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { getToken } from '@/lib/auth'
import type {
  DreResponse,
  DrePeriod,
  OperatingExpenseRequest,
  OperatingExpenseResponse,
  ExpenseCategory,
} from '@/types/dre'

// ── Formatacao monetaria PT-BR ────────────────────────────────────────────────

const formatCents = (cents: number) =>
  (cents / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })

function parseBRLInput(value: string): number | null {
  const normalized = value.replace(/\./g, '').replace(',', '.')
  const num = Number.parseFloat(normalized)
  return Number.isFinite(num) && num >= 0 ? Math.round(num * 100) : null
}

// ── Labels PT-BR ──────────────────────────────────────────────────────────────

const CHANNEL_LABELS: Record<string, string> = {
  COUNTER: 'Balcao',
  DINE_IN: 'Mesa',
  DELIVERY: 'Delivery',
  ONLINE: 'Online',
}

const PAYMENT_LABELS: Record<string, string> = {
  CASH: 'Dinheiro',
  CREDIT_CARD: 'Cartao Credito',
  DEBIT_CARD: 'Debito',
  PIX: 'PIX',
  OTHER: 'Outro',
}

const CATEGORY_LABELS: Record<string, string> = {
  RENT: 'Aluguel',
  UTILITIES: 'Agua/Luz/Internet',
  PAYROLL: 'Folha',
  MARKETING: 'Marketing',
  OTHER: 'Outros',
}

const CHART_COLORS = ['#047857', '#F59E0B', '#3B82F6', '#EF4444', '#8B5CF6', '#EC4899']

// ── Toast ─────────────────────────────────────────────────────────────────────

type ToastType = 'success' | 'error'
interface ToastState { id: number; message: string; type: ToastType }

interface OperatingExpensePage {
  content: OperatingExpenseResponse[]
  totalElements: number
  totalPages: number
  number: number
}

function useToast() {
  const [toasts, setToasts] = useState<ToastState[]>([])
  const counter = useRef(0)
  const show = useCallback((message: string, type: ToastType = 'success') => {
    const id = ++counter.current
    setToasts(prev => [...prev, { id, message, type }])
    setTimeout(() => setToasts(prev => prev.filter(t => t.id !== id)), 3500)
  }, [])
  return { toasts, show }
}

function ToastContainer({ toasts }: { toasts: ToastState[] }) {
  if (toasts.length === 0) return null
  return (
    <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2" aria-live="polite">
      {toasts.map(t => (
        <div
          key={t.id}
          role="status"
          className={[
            'rounded-xl px-4 py-3 text-sm font-medium shadow-dropdown',
            t.type === 'success' ? 'bg-primary-700 text-white' : 'bg-red-600 text-white',
          ].join(' ')}
        >
          {t.message}
        </div>
      ))}
    </div>
  )
}

// ── Skeleton ──────────────────────────────────────────────────────────────────

function SkeletonCard() {
  return (
    <div className="animate-pulse rounded-2xl bg-bg-primary p-6 shadow-card" aria-busy="true">
      <div className="mb-2 h-3 w-1/3 rounded bg-bg-tertiary" />
      <div className="h-7 w-2/3 rounded bg-bg-tertiary" />
    </div>
  )
}

// ── Seletor de periodo ────────────────────────────────────────────────────────

const PERIOD_OPTS: { label: string; value: DrePeriod }[] = [
  { label: 'Hoje', value: 'today' },
  { label: 'Esta semana', value: 'week' },
  { label: 'Este mes', value: 'month' },
  { label: 'Personalizado', value: 'custom' },
]

interface PeriodSelectorProps {
  period: DrePeriod
  customStart: string
  customEnd: string
  onPeriodChange: (p: DrePeriod) => void
  onCustomStartChange: (v: string) => void
  onCustomEndChange: (v: string) => void
  onSearch: () => void
  loading: boolean
}

function PeriodSelector({
  period,
  customStart,
  customEnd,
  onPeriodChange,
  onCustomStartChange,
  onCustomEndChange,
  onSearch,
  loading,
}: PeriodSelectorProps) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      {PERIOD_OPTS.map(opt => (
        <button
          key={opt.value}
          type="button"
          onClick={() => onPeriodChange(opt.value)}
          aria-pressed={period === opt.value}
          className={[
            'inline-flex min-h-11 items-center justify-center rounded-full px-4 text-sm font-medium transition-colors',
            period === opt.value
              ? 'bg-primary-700 text-white'
              : 'border border-border-medium bg-bg-primary text-text-secondary hover:bg-bg-tertiary',
          ].join(' ')}
        >
          {opt.label}
        </button>
      ))}

      {period === 'custom' && (
        <div className="mt-2 flex w-full flex-wrap items-center gap-2 sm:mt-0 sm:w-auto">
          <input
            type="date"
            value={customStart}
            onChange={e => onCustomStartChange(e.target.value)}
            aria-label="Data inicial"
            className="input-field text-sm"
          />
          <span className="text-sm text-text-muted">ate</span>
          <input
            type="date"
            value={customEnd}
            onChange={e => onCustomEndChange(e.target.value)}
            aria-label="Data final"
            className="input-field text-sm"
          />
          <button
            type="button"
            onClick={onSearch}
            disabled={loading || !customStart || !customEnd}
            className="btn-primary text-sm disabled:opacity-50"
          >
            {loading ? 'Buscando...' : 'Buscar'}
          </button>
        </div>
      )}
    </div>
  )
}

// ── Cards de KPI ──────────────────────────────────────────────────────────────

function MarginBadge({ pct }: { pct: number }) {
  return (
    <span
      className={[
        'ml-2 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold',
        pct >= 0 ? 'bg-emerald-50 text-emerald-700' : 'bg-red-50 text-red-700',
      ].join(' ')}
    >
      {pct >= 0 ? (
        <TrendingUp className="h-3 w-3" aria-hidden="true" />
      ) : (
        <TrendingDown className="h-3 w-3" aria-hidden="true" />
      )}
      {pct.toFixed(1)}%
    </span>
  )
}

interface KpiCardProps {
  label: string
  value: string
  sub?: React.ReactNode
  accent?: boolean
}

function KpiCard({ label, value, sub, accent }: KpiCardProps) {
  return (
    <div
      className={[
        'rounded-2xl p-5 shadow-card',
        accent ? 'bg-primary-700 text-white' : 'bg-bg-primary',
      ].join(' ')}
    >
      <p
        className={[
          'text-xs font-semibold uppercase tracking-wider',
          accent ? 'text-white/70' : 'text-text-muted',
        ].join(' ')}
      >
        {label}
      </p>
      <p
        className={[
          'mt-2 text-2xl font-bold',
          accent ? 'text-white' : 'text-text-primary',
        ].join(' ')}
      >
        {value}
      </p>
      {sub && <div className="mt-1">{sub}</div>}
    </div>
  )
}

// ── Tabela DRE waterfall ──────────────────────────────────────────────────────

interface DreLineProps {
  label: string
  cents: number
  indent?: boolean
  highlight?: boolean
  negative?: boolean
  big?: boolean
}

function DreLine({ label, cents, indent, highlight, negative, big }: DreLineProps) {
  const isNeg = negative || cents < 0
  const displayCents = Math.abs(cents)
  const valueStr = isNeg && cents !== 0
    ? `(${formatCents(displayCents)})`
    : formatCents(displayCents)

  return (
    <div
      className={[
        'flex items-center justify-between py-2',
        indent ? 'pl-6' : '',
        highlight ? 'border-t border-border-light' : '',
      ].join(' ')}
    >
      <span
        className={[
          big
            ? 'text-base font-bold text-text-primary'
            : highlight
            ? 'text-sm font-semibold text-text-primary'
            : 'text-sm text-text-secondary',
        ].join(' ')}
      >
        {label}
      </span>
      <span
        className={[
          'font-mono tabular-nums',
          big ? 'text-lg font-bold' : 'text-sm font-medium',
          isNeg && cents !== 0
            ? 'text-red-600'
            : highlight || big
            ? 'text-emerald-700'
            : 'text-text-primary',
        ].join(' ')}
      >
        {valueStr}
      </span>
    </div>
  )
}

function DreTable({ data }: { data: DreResponse }) {
  return (
    <div className="rounded-2xl bg-bg-primary p-6 shadow-card">
      <h3 className="mb-4 text-sm font-semibold uppercase tracking-wider text-text-secondary">
        Demonstrativo de Resultado
      </h3>
      <div className="divide-y divide-border-light">
        <DreLine label="Receita Bruta" cents={data.grossRevenueCents} />
        <DreLine label="(-) Taxas marketplace" cents={data.marketplaceFeesCents} indent negative />
        <DreLine label="(-) Taxas cartao" cents={data.cardFeesCents} indent negative />
        <DreLine label="(-) Impostos" cents={data.taxCents} indent negative />
        <DreLine label="= Receita Liquida" cents={data.netRevenueCents} highlight />
        <DreLine label="(-) CMV (custo de mercadoria)" cents={data.cogsCents} indent negative />
        <DreLine label="= Lucro Bruto" cents={data.grossProfitCents} highlight />
        <DreLine label="(-) Despesas operacionais" cents={data.operatingExpensesCents} indent negative />
        <DreLine label="= Lucro Liquido" cents={data.netProfitCents} highlight big />
      </div>
    </div>
  )
}

// ── Graficos Recharts ─────────────────────────────────────────────────────────

interface ChartData { name: string; value: number }

function EmptyChart() {
  return (
    <div className="flex h-48 items-center justify-center rounded-xl bg-bg-tertiary">
      <p className="text-sm text-text-muted">Nenhum pedido no periodo</p>
    </div>
  )
}

const RADIAN = Math.PI / 180

function renderCustomLabel(props: PieLabelRenderProps) {
  const cx = (props.cx as number) ?? 0
  const cy = (props.cy as number) ?? 0
  const midAngle = (props.midAngle as number) ?? 0
  const innerRadius = (props.innerRadius as number) ?? 0
  const outerRadius = (props.outerRadius as number) ?? 0
  const percent = (props.percent as number) ?? 0
  if (percent < 0.05) return null
  const radius = innerRadius + (outerRadius - innerRadius) * 0.5
  const x = cx + radius * Math.cos(-midAngle * RADIAN)
  const y = cy + radius * Math.sin(-midAngle * RADIAN)
  return (
    <text
      x={x}
      y={y}
      fill="white"
      textAnchor="middle"
      dominantBaseline="central"
      fontSize={11}
      fontWeight={600}
    >
      {`${(percent * 100).toFixed(0)}%`}
    </text>
  )
}

function DreCharts({
  ordersByChannel,
  ordersByPaymentMethod,
  orderCount,
}: {
  ordersByChannel: Record<string, number>
  ordersByPaymentMethod: Record<string, number>
  orderCount: number
}) {
  const channelData: ChartData[] = Object.entries(ordersByChannel).map(([k, v]) => ({
    name: CHANNEL_LABELS[k] ?? k,
    value: v,
  }))

  const paymentData: ChartData[] = Object.entries(ordersByPaymentMethod).map(([k, v]) => ({
    name: PAYMENT_LABELS[k] ?? k,
    value: v,
  }))

  return (
    <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
      <div className="rounded-2xl bg-bg-primary p-6 shadow-card">
        <h3 className="mb-4 text-sm font-semibold uppercase tracking-wider text-text-secondary">
          Pedidos por canal
        </h3>
        {orderCount === 0 || channelData.length === 0 ? (
          <EmptyChart />
        ) : (
          <ResponsiveContainer width="100%" height={220}>
            <PieChart>
              <Pie
                data={channelData}
                cx="50%"
                cy="50%"
                outerRadius={80}
                dataKey="value"
                labelLine={false}
                label={renderCustomLabel}
              >
                {channelData.map((_entry, index) => (
                  <Cell key={`ch-${index}`} fill={CHART_COLORS[index % CHART_COLORS.length]} />
                ))}
              </Pie>
              <Tooltip formatter={(v) => [String(v) + ' pedidos', '']} />
              <Legend wrapperStyle={{ fontSize: 12 }} />
            </PieChart>
          </ResponsiveContainer>
        )}
      </div>

      <div className="rounded-2xl bg-bg-primary p-6 shadow-card">
        <h3 className="mb-4 text-sm font-semibold uppercase tracking-wider text-text-secondary">
          Pedidos por forma de pagamento
        </h3>
        {orderCount === 0 || paymentData.length === 0 ? (
          <EmptyChart />
        ) : (
          <ResponsiveContainer width="100%" height={220}>
            <PieChart>
              <Pie
                data={paymentData}
                cx="50%"
                cy="50%"
                outerRadius={80}
                dataKey="value"
                labelLine={false}
                label={renderCustomLabel}
              >
                {paymentData.map((_entry, index) => (
                  <Cell key={`pm-${index}`} fill={CHART_COLORS[index % CHART_COLORS.length]} />
                ))}
              </Pie>
              <Tooltip formatter={(v) => [String(v) + ' pedidos', '']} />
              <Legend wrapperStyle={{ fontSize: 12 }} />
            </PieChart>
          </ResponsiveContainer>
        )}
      </div>
    </div>
  )
}

// ── Modal de despesa ──────────────────────────────────────────────────────────

interface ExpenseModalProps {
  initial?: OperatingExpenseResponse | null
  onClose: () => void
  onSaved: () => void
  showToast: (msg: string, type: ToastType) => void
}

const CATEGORIES: { value: ExpenseCategory; label: string }[] = [
  { value: 'RENT',      label: 'Aluguel' },
  { value: 'UTILITIES', label: 'Agua/Luz/Internet' },
  { value: 'PAYROLL',   label: 'Folha' },
  { value: 'MARKETING', label: 'Marketing' },
  { value: 'OTHER',     label: 'Outros' },
]

function ExpenseModal({ initial, onClose, onSaved, showToast }: ExpenseModalProps) {
  const [description, setDescription] = useState(initial?.description ?? '')
  const [amountInput, setAmountInput] = useState(
    initial ? (initial.amountCents / 100).toFixed(2).replace('.', ',') : ''
  )
  const [category, setCategory] = useState<ExpenseCategory>(
    (initial?.category as ExpenseCategory) ?? 'OTHER'
  )
  const [expenseDate, setExpenseDate] = useState(
    initial?.expenseDate
      ? initial.expenseDate.substring(0, 10)
      : new Date().toISOString().substring(0, 10)
  )
  const [saving, setSaving] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    const amountCents = parseBRLInput(amountInput)
    if (!amountCents || amountCents <= 0) {
      showToast('Valor invalido', 'error')
      return
    }
    const body: OperatingExpenseRequest = { description, amountCents, category, expenseDate }
    setSaving(true)
    try {
      if (initial) {
        await api.put(`/operating-expenses/${initial.id}`, body)
        showToast('Despesa atualizada', 'success')
      } else {
        await api.post('/operating-expenses', body)
        showToast('Despesa registrada', 'success')
      }
      onSaved()
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : 'Erro ao salvar despesa'
      showToast(msg, 'error')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="modal-despesa-titulo"
    >
      <div className="modal-box w-full max-w-md">
        <div className="mb-4 flex items-center justify-between border-b border-border-light pb-4">
          <h2 id="modal-despesa-titulo" className="text-base font-semibold text-text-primary">
            {initial ? 'Editar despesa' : 'Nova despesa'}
          </h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Fechar"
            className="icon-button text-text-muted hover:bg-bg-tertiary"
          >
            <X className="h-5 w-5" aria-hidden="true" />
          </button>
        </div>

        <form onSubmit={e => void handleSubmit(e)} className="space-y-4">
          <div>
            <label className="form-label" htmlFor="exp-description">
              Descricao
            </label>
            <input
              id="exp-description"
              type="text"
              required
              maxLength={120}
              value={description}
              onChange={e => setDescription(e.target.value)}
              className="input-field"
              placeholder="Ex: Aluguel do espaco"
            />
          </div>

          <div>
            <label className="form-label" htmlFor="exp-amount">
              Valor (R$)
            </label>
            <input
              id="exp-amount"
              type="text"
              inputMode="decimal"
              required
              value={amountInput}
              onChange={e => setAmountInput(e.target.value)}
              className="input-field"
              placeholder="0,00"
            />
          </div>

          <div>
            <label className="form-label" htmlFor="exp-category">
              Categoria
            </label>
            <select
              id="exp-category"
              value={category}
              onChange={e => setCategory(e.target.value as ExpenseCategory)}
              className="input-field"
            >
              {CATEGORIES.map(c => (
                <option key={c.value} value={c.value}>
                  {c.label}
                </option>
              ))}
            </select>
          </div>

          <div>
            <label className="form-label" htmlFor="exp-date">
              Data
            </label>
            <input
              id="exp-date"
              type="date"
              required
              value={expenseDate}
              onChange={e => setExpenseDate(e.target.value)}
              className="input-field"
            />
          </div>

          <div className="flex justify-end gap-3 pt-2">
            <button type="button" onClick={onClose} className="btn-secondary">
              Cancelar
            </button>
            <button
              type="submit"
              disabled={saving}
              className="btn-primary disabled:opacity-50"
            >
              {saving ? 'Salvando...' : 'Salvar'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

// ── Secao de despesas operacionais ────────────────────────────────────────────

const PAGE_SIZE = 8

function ExpensesSection({ showToast }: { showToast: (msg: string, type: ToastType) => void }) {
  const [expenses, setExpenses] = useState<OperatingExpenseResponse[]>([])
  const [loadState, setLoadState] = useState<'loading' | 'error' | 'ok'>('loading')
  const [page, setPage] = useState(0)
  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<OperatingExpenseResponse | null>(null)

  const load = useCallback(async () => {
    setLoadState('loading')
    try {
      const data = await api.get<OperatingExpensePage>('/operating-expenses?size=500')
      setExpenses(data.content)
      setPage(0)
      setLoadState('ok')
    } catch {
      setLoadState('error')
    }
  }, [])

  useEffect(() => {
    queueMicrotask(() => {
      void load()
    })
  }, [load])

  async function handleDelete(id: string, label: string) {
    if (!confirm(`Confirma excluir "${label}"?`)) return
    try {
      await api.del(`/operating-expenses/${id}`)
      showToast('Despesa excluida', 'success')
      void load()
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : 'Erro ao excluir'
      showToast(msg, 'error')
    }
  }

  const totalPages = Math.max(1, Math.ceil(expenses.length / PAGE_SIZE))
  const paginated = expenses.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE)

  return (
    <div className="rounded-2xl bg-bg-primary p-6 shadow-card">
      <div className="mb-4 flex items-center justify-between">
        <h3 className="text-sm font-semibold uppercase tracking-wider text-text-secondary">
          Despesas Operacionais
        </h3>
        <button
          type="button"
          onClick={() => {
            setEditing(null)
            setModalOpen(true)
          }}
          className="btn-primary flex items-center gap-1.5 py-1.5 text-sm"
        >
          <Plus className="h-4 w-4" aria-hidden="true" />
          Nova despesa
        </button>
      </div>

      {loadState === 'loading' && (
        <div className="animate-pulse space-y-2">
          {[...Array(3)].map((_, i) => (
            <div key={i} className="h-10 rounded-lg bg-bg-tertiary" />
          ))}
        </div>
      )}

      {loadState === 'error' && (
        <div role="alert" className="flex flex-col items-center gap-3 py-8 text-center">
          <p className="text-sm text-text-secondary">Nao foi possivel carregar as despesas.</p>
          <button className="btn-primary text-sm" onClick={() => void load()}>
            Tentar novamente
          </button>
        </div>
      )}

      {loadState === 'ok' && expenses.length === 0 && (
        <div className="flex flex-col items-center gap-2 py-12 text-center">
          <Receipt className="h-10 w-10 text-text-muted" aria-hidden="true" />
          <p className="text-sm text-text-secondary">Nenhuma despesa registrada.</p>
        </div>
      )}

      {loadState === 'ok' && expenses.length > 0 && (
        <>
          <div className="grid gap-3 lg:hidden">
            {paginated.map(exp => (
              <article key={exp.id} className="rounded-xl border border-border-light bg-bg-primary p-4">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <p className="break-words text-base font-semibold text-text-primary">
                      {exp.description}
                    </p>
                    <p className="mt-1 text-sm text-text-secondary">
                      {CATEGORY_LABELS[exp.category] ?? exp.category}
                    </p>
                  </div>
                  <p className="shrink-0 font-mono text-base font-semibold text-text-primary">
                    {formatCents(exp.amountCents)}
                  </p>
                </div>

                <div className="mt-4 flex items-center justify-between gap-3 border-t border-border-light pt-3">
                  <span className="text-sm text-text-secondary">
                    {new Date(exp.expenseDate + 'T12:00:00').toLocaleDateString('pt-BR')}
                  </span>
                  <div className="flex items-center gap-1">
                    <button
                      type="button"
                      onClick={() => {
                        setEditing(exp)
                        setModalOpen(true)
                      }}
                      aria-label={`Editar ${exp.description}`}
                      className="icon-button text-text-muted hover:text-primary-700"
                    >
                      <Pencil className="h-4 w-4" aria-hidden="true" />
                    </button>
                    <button
                      type="button"
                      onClick={() => void handleDelete(exp.id, exp.description)}
                      aria-label={`Excluir ${exp.description}`}
                      className="icon-button text-text-muted hover:bg-red-50 hover:text-red-600"
                    >
                      <Trash2 className="h-4 w-4" aria-hidden="true" />
                    </button>
                  </div>
                </div>
              </article>
            ))}
          </div>

          <div className="hidden lg:block">
            <table className="w-full text-sm" role="table">
              <thead>
                <tr className="border-b border-border-light text-left">
                  <th className="pb-2 pr-4 text-xs font-semibold uppercase tracking-wider text-text-muted">
                    Descricao
                  </th>
                  <th className="pb-2 pr-4 text-xs font-semibold uppercase tracking-wider text-text-muted">
                    Categoria
                  </th>
                  <th className="pb-2 pr-4 text-xs font-semibold uppercase tracking-wider text-text-muted">
                    Data
                  </th>
                  <th className="pb-2 pr-4 text-right text-xs font-semibold uppercase tracking-wider text-text-muted">
                    Valor
                  </th>
                  <th className="pb-2 text-right text-xs font-semibold uppercase tracking-wider text-text-muted">
                    Acoes
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border-light">
                {paginated.map(exp => (
                  <tr key={exp.id} className="hover:bg-bg-tertiary/50">
                    <td className="py-2.5 pr-4 text-text-primary">{exp.description}</td>
                    <td className="py-2.5 pr-4 text-text-secondary">
                      {CATEGORY_LABELS[exp.category] ?? exp.category}
                    </td>
                    <td className="py-2.5 pr-4 text-text-secondary">
                      {new Date(exp.expenseDate + 'T12:00:00').toLocaleDateString('pt-BR')}
                    </td>
                    <td className="py-2.5 pr-4 text-right font-mono text-text-primary">
                      {formatCents(exp.amountCents)}
                    </td>
                    <td className="py-2.5 text-right">
                      <div className="flex justify-end gap-1">
                        <button
                          type="button"
                          onClick={() => {
                            setEditing(exp)
                            setModalOpen(true)
                          }}
                          aria-label={`Editar ${exp.description}`}
                          className="icon-button text-text-muted hover:text-primary-700"
                        >
                          <Pencil className="h-4 w-4" aria-hidden="true" />
                        </button>
                        <button
                          type="button"
                          onClick={() => void handleDelete(exp.id, exp.description)}
                          aria-label={`Excluir ${exp.description}`}
                          className="icon-button text-text-muted hover:bg-red-50 hover:text-red-600"
                        >
                          <Trash2 className="h-4 w-4" aria-hidden="true" />
                        </button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {totalPages > 1 && (
            <div className="mt-4 flex items-center justify-between text-sm text-text-secondary">
              <span>{expenses.length} registros</span>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  onClick={() => setPage(p => Math.max(0, p - 1))}
                  disabled={page === 0}
                  aria-label="Pagina anterior"
                  className="icon-button text-text-muted hover:bg-bg-tertiary disabled:opacity-40"
                >
                  <ChevronLeft className="h-4 w-4" aria-hidden="true" />
                </button>
                <span>
                  {page + 1} / {totalPages}
                </span>
                <button
                  type="button"
                  onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
                  disabled={page + 1 >= totalPages}
                  aria-label="Proxima pagina"
                  className="icon-button text-text-muted hover:bg-bg-tertiary disabled:opacity-40"
                >
                  <ChevronRight className="h-4 w-4" aria-hidden="true" />
                </button>
              </div>
            </div>
          )}
        </>
      )}

      {modalOpen && (
        <ExpenseModal
          initial={editing}
          onClose={() => {
            setModalOpen(false)
            setEditing(null)
          }}
          onSaved={() => {
            setModalOpen(false)
            setEditing(null)
            void load()
          }}
          showToast={showToast}
        />
      )}
    </div>
  )
}

// ── Pagina principal DRE ──────────────────────────────────────────────────────

export default function DrePage() {
  const router = useRouter()
  const { toasts, show: showToast } = useToast()

  const [period, setPeriod] = useState<DrePeriod>('month')
  const [customStart, setCustomStart] = useState('')
  const [customEnd, setCustomEnd] = useState('')
  const [dreData, setDreData] = useState<DreResponse | null>(null)
  const [loadState, setLoadState] = useState<'loading' | 'error' | 'ok' | 'idle'>('loading')

  const isAuthenticated = typeof window === 'undefined' || !!getToken()

  const loadDre = useCallback(async (p: DrePeriod, start?: string, end?: string) => {
    setLoadState('loading')
    try {
      let data: DreResponse
      if (p === 'custom' && start && end) {
        data = await api.get<DreResponse>(`/dre?start=${start}&end=${end}`)
      } else if (p !== 'custom') {
        data = await api.get<DreResponse>(`/dre/summary?period=${p}`)
      } else {
        setLoadState('idle')
        return
      }
      setDreData(data)
      setLoadState('ok')
    } catch {
      setLoadState('error')
    }
  }, [])

  useEffect(() => {
    if (!isAuthenticated) {
      router.replace('/login')
      return
    }
    if (period === 'custom') {
      queueMicrotask(() => setLoadState('idle'))
      return
    }
    queueMicrotask(() => {
      void loadDre(period)
    })
  }, [period, isAuthenticated, router, loadDre])

  function handlePeriodChange(p: DrePeriod) {
    setPeriod(p)
    if (p === 'custom') {
      setLoadState('idle')
    }
  }

  function handleCustomSearch() {
    if (customStart && customEnd) {
      void loadDre('custom', customStart, customEnd)
    }
  }

  if (!isAuthenticated) return null

  const isLoading = loadState === 'loading'

  return (
    <div className="flex min-h-screen flex-col bg-bg-secondary">
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8">
        {/* Cabecalho */}
        <div className="mb-6">
          <h1 className="text-2xl font-bold text-text-primary">
            DRE - Demonstrativo de Resultado
          </h1>
          {dreData && loadState === 'ok' && (
            <p className="mt-1 text-sm text-text-secondary">
              {new Date(dreData.periodStart + 'T12:00:00').toLocaleDateString('pt-BR')} ate{' '}
              {new Date(dreData.periodEnd + 'T12:00:00').toLocaleDateString('pt-BR')}
            </p>
          )}
        </div>

        {/* Seletor de periodo */}
        <div className="mb-6">
          <PeriodSelector
            period={period}
            customStart={customStart}
            customEnd={customEnd}
            onPeriodChange={handlePeriodChange}
            onCustomStartChange={setCustomStart}
            onCustomEndChange={setCustomEnd}
            onSearch={handleCustomSearch}
            loading={isLoading}
          />
        </div>

        {/* Estado: periodo custom sem busca ainda */}
        {loadState === 'idle' && (
          <div className="mb-6 flex items-center justify-center rounded-2xl bg-bg-primary p-12 shadow-card">
            <p className="text-sm text-text-muted">
              Selecione o intervalo e clique em Buscar.
            </p>
          </div>
        )}

        {/* Estado: erro */}
        {loadState === 'error' && (
          <div
            role="alert"
            className="mb-6 flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-8 text-center shadow-card"
          >
            <p className="text-base font-medium text-text-primary">
              Nao foi possivel carregar o DRE.
            </p>
            <button
              className="btn-primary"
              onClick={() => void loadDre(period, customStart, customEnd)}
            >
              Tentar novamente
            </button>
          </div>
        )}

        {/* Estado: carregando — skeletons de KPI */}
        {isLoading && (
          <div className="mb-6 grid grid-cols-2 gap-4 md:grid-cols-4">
            {[...Array(4)].map((_, i) => (
              <SkeletonCard key={i} />
            ))}
          </div>
        )}

        {/* Estado: ok */}
        {loadState === 'ok' && dreData && (
          <>
            {/* KPI cards */}
            <div className="mb-6 grid grid-cols-2 gap-4 md:grid-cols-4">
              <KpiCard
                label="Receita Bruta"
                value={formatCents(dreData.grossRevenueCents)}
                accent
              />
              <KpiCard
                label="Lucro Bruto"
                value={formatCents(dreData.grossProfitCents)}
                sub={<MarginBadge pct={dreData.grossMarginPct} />}
              />
              <KpiCard
                label="Lucro Liquido"
                value={formatCents(dreData.netProfitCents)}
                sub={<MarginBadge pct={dreData.netMarginPct} />}
              />
              <KpiCard
                label="Ticket Medio"
                value={formatCents(dreData.averageTicketCents)}
                sub={
                  <p className="mt-1 flex items-center gap-1 text-xs text-text-muted">
                    <ShoppingBag className="h-3 w-3" aria-hidden="true" />
                    {dreData.orderCount} pedidos
                  </p>
                }
              />
            </div>

            {/* DRE waterfall */}
            <div className="mb-6">
              <DreTable data={dreData} />
            </div>

            {/* Graficos */}
            <div className="mb-6">
              <DreCharts
                ordersByChannel={dreData.ordersByChannel}
                ordersByPaymentMethod={dreData.ordersByPaymentMethod}
                orderCount={dreData.orderCount}
              />
            </div>
          </>
        )}

        {/* Despesas operacionais — sempre visivel */}
        <ExpensesSection showToast={showToast} />
      </main>

      <ToastContainer toasts={toasts} />
    </div>
  )
}
