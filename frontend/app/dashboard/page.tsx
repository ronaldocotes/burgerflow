'use client'

import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  Cell,
} from 'recharts'
import {
  AlertTriangle,
  Bot,
  CalendarDays,
  CheckCircle2,
  CircleDollarSign,
  Clock,
  Link2,
  Megaphone,
  Receipt,
  RefreshCcw,
  ShoppingBag,
  TrendingUp,
  Users2,
  Wallet,
} from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import type { DreResponse, DrePeriod } from '@/types/dre'
import type { CashSessionResponse } from '@/types/cash'
import type { TrackingSummaryResponse } from '@/types/tracking'
import type { CampaignPage, CampaignResponse, RfvScoreResponse } from '@/types/campaign'
import type { CartSessionPage } from '@/types/cart-session'
import type { ConversionDispatchPage } from '@/types/conversion'

type DashboardTab = 'overview' | 'orders' | 'customers' | 'marketing'
type LoadState = 'loading' | 'error' | 'ready'

interface DashboardData {
  dre: DreResponse | null
  cash: CashSessionResponse | null
  tracking: TrackingSummaryResponse[]
  campaigns: CampaignResponse[]
  campaignTotal: number
  cartsTotal: number
  conversionsTotal: number
  rfv: RfvScoreResponse[]
}

interface ChartDatum {
  name: string
  value: number
}

const EMPTY_DATA: DashboardData = {
  dre: null,
  cash: null,
  tracking: [],
  campaigns: [],
  campaignTotal: 0,
  cartsTotal: 0,
  conversionsTotal: 0,
  rfv: [],
}

const PERIOD_OPTIONS: { label: string; value: Exclude<DrePeriod, 'custom'> }[] = [
  { label: 'Hoje', value: 'today' },
  { label: 'Semana', value: 'week' },
  { label: 'Mês', value: 'month' },
]

const TABS: { label: string; value: DashboardTab }[] = [
  { label: 'Visão Geral', value: 'overview' },
  { label: 'Pedidos', value: 'orders' },
  { label: 'Clientes', value: 'customers' },
  { label: 'Marketing', value: 'marketing' },
]

const CHANNEL_LABELS: Record<string, string> = {
  COUNTER: 'Balcão',
  DINE_IN: 'Mesa',
  DELIVERY: 'Delivery',
  ONLINE: 'Online',
  OWN: 'Próprio',
  IFOOD: 'iFood',
  RAPPI: 'Rappi',
  NINETY_NINE: '99Food',
}

const PAYMENT_LABELS: Record<string, string> = {
  CASH: 'Dinheiro',
  CREDIT_CARD: 'Crédito',
  DEBIT_CARD: 'Débito',
  PIX: 'Pix',
  OTHER: 'Outro',
  UNKNOWN: 'Sem forma',
}

const RFV_LABELS: Record<string, string> = {
  LOYAL: 'Fiéis',
  AT_RISK: 'Em risco',
  INACTIVE: 'Inativos',
  NEW: 'Novos',
}

const CHART_COLORS = ['#047857', '#2563eb', '#b45309', '#dc2626', '#7c3aed', '#0891b2']

function formatCents(cents: number): string {
  return (cents / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
}

function formatNumber(value: number): string {
  return value.toLocaleString('pt-BR')
}

function periodRange(period: Exclude<DrePeriod, 'custom'>): { from: string; to: string; label: string } {
  const now = new Date()
  const from = new Date(now)
  if (period === 'today') {
    from.setHours(0, 0, 0, 0)
    return { from: from.toISOString(), to: now.toISOString(), label: 'Hoje' }
  }
  if (period === 'week') {
    const day = from.getDay()
    const diffToMonday = day === 0 ? 6 : day - 1
    from.setDate(from.getDate() - diffToMonday)
    from.setHours(0, 0, 0, 0)
    return { from: from.toISOString(), to: now.toISOString(), label: 'Semana atual' }
  }
  from.setDate(1)
  from.setHours(0, 0, 0, 0)
  return { from: from.toISOString(), to: now.toISOString(), label: 'Mês atual' }
}

function mapBreakdown(source: Record<string, number> | undefined, labels: Record<string, string>): ChartDatum[] {
  return Object.entries(source ?? {})
    .map(([key, value]) => ({ name: labels[key] ?? key, value }))
    .filter((item) => item.value > 0)
}

function EmptyBlock({ title, text }: { title: string; text: string }) {
  return (
    <div className="flex min-h-[180px] flex-col items-center justify-center rounded-lg border border-dashed border-border-medium bg-bg-secondary p-6 text-center">
      <p className="text-sm font-semibold text-text-primary">{title}</p>
      <p className="mt-1 max-w-sm text-sm text-text-muted">{text}</p>
    </div>
  )
}

function SkeletonCard() {
  return (
    <div className="animate-pulse rounded-lg border border-border-light bg-bg-primary p-5 shadow-card" aria-busy="true">
      <div className="mb-3 h-3 w-28 rounded bg-bg-tertiary" />
      <div className="h-8 w-40 rounded bg-bg-tertiary" />
      <div className="mt-4 h-3 w-32 rounded bg-bg-tertiary" />
    </div>
  )
}

function KpiCard({
  label,
  value,
  helper,
  icon: Icon,
  tone = 'neutral',
}: {
  label: string
  value: string
  helper: string
  icon: typeof CircleDollarSign
  tone?: 'neutral' | 'good' | 'warn' | 'bad'
}) {
  const toneClass = {
    neutral: 'bg-bg-tertiary text-text-secondary',
    good: 'bg-primary-50 text-primary-700',
    warn: 'bg-secondary-50 text-secondary-800',
    bad: 'bg-red-50 text-red-700',
  }[tone]

  return (
    <div className="rounded-lg border border-border-light bg-bg-primary p-5 shadow-card">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="text-sm font-medium text-text-secondary">{label}</p>
          <p className="mt-2 truncate text-2xl font-bold text-text-primary">{value}</p>
        </div>
        <div className={['flex h-11 w-11 shrink-0 items-center justify-center rounded-lg', toneClass].join(' ')}>
          <Icon className="h-5 w-5" aria-hidden="true" />
        </div>
      </div>
      <p className="mt-3 text-sm text-text-muted">{helper}</p>
    </div>
  )
}

function ChartPanel({
  title,
  subtitle,
  data,
}: {
  title: string
  subtitle: string
  data: ChartDatum[]
}) {
  return (
    <section className="rounded-lg border border-border-light bg-bg-primary p-5 shadow-card">
      <div className="mb-4">
        <h2 className="text-base font-semibold text-text-primary">{title}</h2>
        <p className="mt-1 text-sm text-text-muted">{subtitle}</p>
      </div>
      {data.length === 0 ? (
        <EmptyBlock title="Sem dados no período" text="Quando houver movimento, este gráfico aparece automaticamente." />
      ) : (
        <div className="h-72">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={data} layout="vertical" margin={{ left: 8, right: 16, top: 8, bottom: 8 }}>
              <XAxis type="number" allowDecimals={false} />
              <YAxis type="category" width={92} dataKey="name" tickLine={false} axisLine={false} />
              <Tooltip
                formatter={(value) => [formatNumber(Number(value)), 'Quantidade']}
                contentStyle={{ borderRadius: 8, borderColor: '#e2e8f0' }}
              />
              <Bar dataKey="value" radius={[0, 6, 6, 0]}>
                {data.map((entry, index) => (
                  <Cell key={entry.name} fill={CHART_COLORS[index % CHART_COLORS.length]} />
                ))}
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </section>
  )
}

function FinancialSummary({ dre, cash }: { dre: DreResponse | null; cash: CashSessionResponse | null }) {
  const cashOpen = cash?.status === 'OPEN'
  const rows = [
    { label: 'Receita líquida', value: formatCents(dre?.netRevenueCents ?? 0) },
    { label: 'Taxas e impostos', value: formatCents((dre?.marketplaceFeesCents ?? 0) + (dre?.cardFeesCents ?? 0) + (dre?.taxCents ?? 0)) },
    { label: 'CMV', value: formatCents(dre?.cogsCents ?? 0) },
    { label: 'Lucro líquido', value: formatCents(dre?.netProfitCents ?? 0) },
  ]

  return (
    <section className="rounded-lg border border-border-light bg-bg-primary p-5 shadow-card">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="text-base font-semibold text-text-primary">Resumo financeiro</h2>
          <p className="mt-1 text-sm text-text-muted">Valores calculados pelo DRE do período.</p>
        </div>
        <span
          className={[
            'inline-flex min-h-8 items-center gap-1 rounded-full px-3 text-sm font-medium',
            cashOpen ? 'bg-primary-50 text-primary-700' : 'bg-red-50 text-red-700',
          ].join(' ')}
        >
          {cashOpen ? <CheckCircle2 className="h-4 w-4" aria-hidden="true" /> : <AlertTriangle className="h-4 w-4" aria-hidden="true" />}
          {cashOpen ? 'Caixa aberto' : 'Caixa fechado'}
        </span>
      </div>
      <dl className="mt-5 grid gap-3">
        {rows.map((row) => (
          <div key={row.label} className="flex items-center justify-between gap-4 rounded-lg bg-bg-secondary px-4 py-3">
            <dt className="text-sm text-text-secondary">{row.label}</dt>
            <dd className="text-sm font-semibold text-text-primary">{row.value}</dd>
          </div>
        ))}
      </dl>
    </section>
  )
}

function OperationalAlerts({ dre, cash }: { dre: DreResponse | null; cash: CashSessionResponse | null }) {
  const alerts = [
    {
      icon: cash?.status === 'OPEN' ? CheckCircle2 : AlertTriangle,
      title: cash?.status === 'OPEN' ? 'Caixa aberto' : 'Caixa fechado',
      text: cash?.status === 'OPEN'
        ? `Previsto no caixa: ${formatCents(cash.expectedCents)}`
        : 'Abra o caixa antes de iniciar a operação do dia.',
      tone: cash?.status === 'OPEN' ? 'good' : 'bad',
    },
    {
      icon: dre && dre.netProfitCents >= 0 ? TrendingUp : AlertTriangle,
      title: dre && dre.netProfitCents >= 0 ? 'Resultado positivo' : 'Margem em atenção',
      text: dre ? `Margem líquida: ${dre.netMarginPct.toLocaleString('pt-BR', { maximumFractionDigits: 1 })}%` : 'Sem DRE carregado para o período.',
      tone: dre && dre.netProfitCents >= 0 ? 'good' : 'warn',
    },
    {
      icon: Clock,
      title: 'Cozinha e mesas',
      text: 'A fase operacional vai trazer atrasos do KDS e mesas abertas aqui.',
      tone: 'neutral',
    },
  ] as const

  return (
    <section className="rounded-lg border border-border-light bg-bg-primary p-5 shadow-card">
      <h2 className="text-base font-semibold text-text-primary">Alertas operacionais</h2>
      <div className="mt-4 grid gap-3">
        {alerts.map(({ icon: Icon, title, text, tone }) => (
          <div key={title} className="flex gap-3 rounded-lg bg-bg-secondary p-3">
            <span
              className={[
                'mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-lg',
                tone === 'good' ? 'bg-primary-50 text-primary-700' : tone === 'bad' ? 'bg-red-50 text-red-700' : tone === 'warn' ? 'bg-secondary-50 text-secondary-800' : 'bg-bg-tertiary text-text-secondary',
              ].join(' ')}
            >
              <Icon className="h-5 w-5" aria-hidden="true" />
            </span>
            <div>
              <p className="text-sm font-semibold text-text-primary">{title}</p>
              <p className="mt-0.5 text-sm text-text-muted">{text}</p>
            </div>
          </div>
        ))}
      </div>
    </section>
  )
}

function MarketingPanel({ data }: { data: DashboardData }) {
  const trackingRevenue = data.tracking.reduce((sum, item) => sum + item.revenueCents, 0)
  const trackingClicks = data.tracking.reduce((sum, item) => sum + item.clicks, 0)
  const trackingConversions = data.tracking.reduce((sum, item) => sum + item.conversions, 0)
  const topTracking = [...data.tracking].sort((a, b) => b.clicks - a.clicks).slice(0, 5)

  return (
    <div className="grid gap-4 lg:grid-cols-3">
      <KpiCard label="Cliques rastreados" value={formatNumber(trackingClicks)} helper="Links de campanha no período" icon={Link2} tone="neutral" />
      <KpiCard label="Conversões" value={formatNumber(trackingConversions)} helper="Conversões atribuídas ao tracking" icon={Receipt} tone="good" />
      <KpiCard label="Receita atribuída" value={formatCents(trackingRevenue)} helper="Sem custo confiável, não calculo ROAS" icon={CircleDollarSign} tone="good" />

      <section className="rounded-lg border border-border-light bg-bg-primary p-5 shadow-card lg:col-span-2">
        <h2 className="text-base font-semibold text-text-primary">Links com mais cliques</h2>
        {topTracking.length === 0 ? (
          <div className="mt-4">
            <EmptyBlock title="Nenhum clique rastreado" text="Crie links em Rastreamento para enxergar clique, carrinho, pedido e pagamento confirmado." />
          </div>
        ) : (
          <div className="mt-4 grid gap-3">
            {topTracking.map((item) => (
              <div key={item.trackingLinkId} className="flex items-center justify-between gap-4 rounded-lg bg-bg-secondary px-4 py-3">
                <div className="min-w-0">
                  <p className="truncate text-sm font-semibold text-text-primary">{item.name}</p>
                  <p className="text-xs text-text-muted">{item.source} / {item.slug}</p>
                </div>
                <div className="shrink-0 text-right">
                  <p className="text-sm font-semibold text-text-primary">{formatNumber(item.clicks)} cliques</p>
                  <p className="text-xs text-text-muted">{formatCents(item.revenueCents)}</p>
                </div>
              </div>
            ))}
          </div>
        )}
      </section>

      <section className="rounded-lg border border-border-light bg-bg-primary p-5 shadow-card">
        <h2 className="text-base font-semibold text-text-primary">Campanhas e funil</h2>
        <dl className="mt-4 grid gap-3">
          <div className="rounded-lg bg-bg-secondary p-3">
            <dt className="text-sm text-text-secondary">Campanhas</dt>
            <dd className="mt-1 text-xl font-bold text-text-primary">{formatNumber(data.campaignTotal)}</dd>
          </div>
          <div className="rounded-lg bg-bg-secondary p-3">
            <dt className="text-sm text-text-secondary">Carrinhos</dt>
            <dd className="mt-1 text-xl font-bold text-text-primary">{formatNumber(data.cartsTotal)}</dd>
          </div>
          <div className="rounded-lg bg-bg-secondary p-3">
            <dt className="text-sm text-text-secondary">Despachos de conversão</dt>
            <dd className="mt-1 text-xl font-bold text-text-primary">{formatNumber(data.conversionsTotal)}</dd>
          </div>
        </dl>
      </section>
    </div>
  )
}

function CustomersPanel({ rfv }: { rfv: RfvScoreResponse[] }) {
  const totalCustomers = rfv.length
  const totalRevenue = rfv.reduce((sum, item) => sum + item.monetaryValue, 0)
  const recurring = rfv.filter((item) => item.frequency > 1).length
  const segmentRows = Object.entries(
    rfv.reduce<Record<string, number>>((acc, item) => {
      acc[item.segment] = (acc[item.segment] ?? 0) + 1
      return acc
    }, {}),
  )

  return (
    <div className="grid gap-4 lg:grid-cols-4">
      <KpiCard label="Clientes RFV" value={formatNumber(totalCustomers)} helper="Base classificada por compra" icon={Users2} tone="neutral" />
      <KpiCard label="Receita histórica" value={formatCents(totalRevenue)} helper="Somatório monetário RFV" icon={CircleDollarSign} tone="good" />
      <KpiCard label="Recorrentes" value={formatNumber(recurring)} helper="Compraram mais de uma vez" icon={RefreshCcw} tone="good" />
      <KpiCard label="Inativos" value={formatNumber(segmentRows.find(([key]) => key === 'INACTIVE')?.[1] ?? 0)} helper="Prioridade para reativação" icon={AlertTriangle} tone="warn" />

      <section className="rounded-lg border border-border-light bg-bg-primary p-5 shadow-card lg:col-span-2">
        <h2 className="text-base font-semibold text-text-primary">Segmentos RFV</h2>
        {segmentRows.length === 0 ? (
          <div className="mt-4">
            <EmptyBlock title="Sem segmentos ainda" text="Quando houver pedidos pagos por cliente, os segmentos de recompra aparecem aqui." />
          </div>
        ) : (
          <div className="mt-4 grid gap-3">
            {segmentRows.map(([segment, count]) => (
              <div key={segment} className="flex items-center justify-between rounded-lg bg-bg-secondary px-4 py-3">
                <span className="text-sm font-medium text-text-primary">{RFV_LABELS[segment] ?? segment}</span>
                <span className="text-sm font-semibold text-text-primary">{formatNumber(count)}</span>
              </div>
            ))}
          </div>
        )}
      </section>

      <section className="rounded-lg border border-border-light bg-bg-primary p-5 shadow-card lg:col-span-2">
        <h2 className="text-base font-semibold text-text-primary">Próximas leituras</h2>
        <div className="mt-4">
          <EmptyBlock title="LTV, bairro e fidelidade entram na próxima fase" text="O plano já prevê clientes novos, LTV, frequência, distribuição geográfica e recompensas." />
        </div>
      </section>
    </div>
  )
}

function OrdersPanel({ dre }: { dre: DreResponse | null }) {
  const channelData = mapBreakdown(dre?.ordersByChannel, CHANNEL_LABELS)
  const paymentData = mapBreakdown(dre?.ordersByPaymentMethod, PAYMENT_LABELS)

  return (
    <div className="grid gap-4 lg:grid-cols-3">
      <KpiCard label="Pedidos" value={formatNumber(dre?.orderCount ?? 0)} helper="Pedidos concluídos no DRE" icon={ShoppingBag} tone="neutral" />
      <KpiCard label="Faturamento" value={formatCents(dre?.grossRevenueCents ?? 0)} helper="Receita bruta do período" icon={CircleDollarSign} tone="good" />
      <KpiCard label="Ticket médio" value={formatCents(dre?.averageTicketCents ?? 0)} helper="Faturamento dividido por pedidos" icon={Receipt} tone="neutral" />
      <div className="lg:col-span-3 grid gap-4 xl:grid-cols-2">
        <ChartPanel title="Pedidos por canal" subtitle="Mesa, balcão, delivery e integrações." data={channelData} />
        <ChartPanel title="Pedidos por pagamento" subtitle="Distribuição por forma de pagamento." data={paymentData} />
      </div>
    </div>
  )
}

function OverviewPanel({ data }: { data: DashboardData }) {
  const dre = data.dre
  const channelData = mapBreakdown(dre?.ordersByChannel, CHANNEL_LABELS)

  return (
    <div className="grid gap-4">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <KpiCard label="Faturamento total" value={formatCents(dre?.grossRevenueCents ?? 0)} helper="Receita bruta confirmada" icon={CircleDollarSign} tone="good" />
        <KpiCard label="Número de pedidos" value={formatNumber(dre?.orderCount ?? 0)} helper="Pedidos concluídos" icon={ShoppingBag} tone="neutral" />
        <KpiCard label="Ticket médio" value={formatCents(dre?.averageTicketCents ?? 0)} helper="Média por pedido" icon={Receipt} tone="neutral" />
        <KpiCard label="Lucro líquido" value={formatCents(dre?.netProfitCents ?? 0)} helper={`Margem ${dre?.netMarginPct?.toLocaleString('pt-BR', { maximumFractionDigits: 1 }) ?? '0'}%`} icon={TrendingUp} tone={(dre?.netProfitCents ?? 0) >= 0 ? 'good' : 'bad'} />
      </div>

      <div className="grid gap-4 xl:grid-cols-[minmax(0,1.5fr)_minmax(340px,0.9fr)]">
        <ChartPanel title="Distribuição por canal" subtitle="Onde os pedidos estão acontecendo." data={channelData} />
        <OperationalAlerts dre={dre} cash={data.cash} />
      </div>

      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <FinancialSummary dre={dre} cash={data.cash} />
        <section className="rounded-lg border border-border-light bg-bg-primary p-5 shadow-card">
          <h2 className="text-base font-semibold text-text-primary">Top 5 produtos</h2>
          <div className="mt-4">
            <EmptyBlock title="Aguardando agregador de produtos" text="A Fase 2 do dashboard vai expor os produtos mais vendidos por receita e quantidade." />
          </div>
        </section>
      </div>
    </div>
  )
}

export default function DashboardPage() {
  const [period, setPeriod] = useState<Exclude<DrePeriod, 'custom'>>('today')
  const [activeTab, setActiveTab] = useState<DashboardTab>('overview')
  const [state, setState] = useState<LoadState>('loading')
  const [error, setError] = useState<string | null>(null)
  const [warning, setWarning] = useState<string | null>(null)
  const [data, setData] = useState<DashboardData>(EMPTY_DATA)

  const range = useMemo(() => periodRange(period), [period])

  const load = useCallback(async () => {
    setState('loading')
    setError(null)
    setWarning(null)
    const withTimeout = <T,>(promise: Promise<T>, label: string, ms = 5000): Promise<T> =>
      Promise.race([
        promise,
        new Promise<T>((_, reject) => {
          window.setTimeout(() => reject(new Error(`${label} demorou para responder.`)), ms)
        }),
      ])

    const [
      dreResult,
      cashResult,
      trackingResult,
      campaignResult,
      cartResult,
      conversionResult,
      rfvResult,
    ] = await Promise.allSettled([
      withTimeout(api.get<DreResponse>(`/dre/summary?period=${period}`), 'DRE'),
      withTimeout(api.get<CashSessionResponse | undefined>('/cash-sessions/current'), 'Caixa'),
      withTimeout(api.get<TrackingSummaryResponse[]>(`/tracking/summary?from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}`), 'Tracking'),
      withTimeout(api.get<CampaignPage<CampaignResponse>>('/campaigns?page=0&size=5'), 'Campanhas'),
      withTimeout(api.get<CartSessionPage>('/cart-sessions?page=0&size=1'), 'Carrinhos'),
      withTimeout(api.get<ConversionDispatchPage>('/conversions/dispatches?page=0&size=1'), 'Conversões'),
      withTimeout(api.get<RfvScoreResponse[]>('/rfv'), 'RFV'),
    ])

    if (dreResult.status === 'rejected') {
      if (dreResult.reason instanceof ApiError && [401, 403].includes(dreResult.reason.status)) {
        setState('error')
        setError(dreResult.reason.message)
        return
      }
      setWarning('Backend indisponível ou lento: mostrando o dashboard vazio para inspeção visual.')
    }

    setData({
      dre: dreResult.status === 'fulfilled' ? dreResult.value : null,
      cash: cashResult.status === 'fulfilled' ? cashResult.value ?? null : null,
      tracking: trackingResult.status === 'fulfilled' ? trackingResult.value : [],
      campaigns: campaignResult.status === 'fulfilled' ? campaignResult.value.content : [],
      campaignTotal: campaignResult.status === 'fulfilled' ? campaignResult.value.totalElements : 0,
      cartsTotal: cartResult.status === 'fulfilled' ? cartResult.value.totalElements : 0,
      conversionsTotal: conversionResult.status === 'fulfilled' ? conversionResult.value.totalElements : 0,
      rfv: rfvResult.status === 'fulfilled' ? rfvResult.value : [],
    })
    setState('ready')
  }, [period, range.from, range.to])

  useEffect(() => {
    void load()
  }, [load])

  return (
    <main className="min-h-full bg-bg-secondary p-4 sm:p-6">
      <div className="mx-auto flex max-w-7xl flex-col gap-5">
        <section className="rounded-lg border border-border-light bg-bg-primary p-4 shadow-card sm:p-5">
          <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
            <div>
              <div className="flex items-center gap-2 text-sm font-medium text-primary-700">
                <CalendarDays className="h-4 w-4" aria-hidden="true" />
                {range.label}
              </div>
              <h1 className="mt-2 text-2xl font-bold text-text-primary">Dashboard</h1>
              <p className="mt-1 max-w-2xl text-sm text-text-muted">
                Saúde do restaurante em uma tela: venda, pedido, caixa, cliente e marketing.
              </p>
            </div>

            <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
              <div className="flex rounded-lg border border-border-light bg-bg-secondary p-1" role="group" aria-label="Período do dashboard">
                {PERIOD_OPTIONS.map((option) => (
                  <button
                    key={option.value}
                    type="button"
                    onClick={() => setPeriod(option.value)}
                    aria-pressed={period === option.value}
                    className={[
                      'min-h-10 rounded-md px-3 text-sm font-medium transition-colors',
                      period === option.value
                        ? 'bg-primary-700 text-white'
                        : 'text-text-secondary hover:bg-bg-primary hover:text-text-primary',
                    ].join(' ')}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
              <button type="button" onClick={() => void load()} className="btn-outline gap-2">
                <RefreshCcw className="h-4 w-4" aria-hidden="true" />
                Atualizar
              </button>
              <button type="button" className="btn-primary gap-2">
                <Bot className="h-4 w-4" aria-hidden="true" />
                MenuFlow IA
              </button>
            </div>
          </div>
        </section>

        <div className="overflow-x-auto no-scrollbar">
          <div className="flex min-w-max gap-2" role="tablist" aria-label="Seções do dashboard">
            {TABS.map((tab) => (
              <button
                key={tab.value}
                type="button"
                role="tab"
                aria-selected={activeTab === tab.value}
                onClick={() => setActiveTab(tab.value)}
                className={[
                  'min-h-11 rounded-lg px-4 text-sm font-semibold transition-colors',
                  activeTab === tab.value
                    ? 'bg-primary-700 text-white'
                    : 'border border-border-light bg-bg-primary text-text-secondary hover:bg-bg-tertiary hover:text-text-primary',
                ].join(' ')}
              >
                {tab.label}
              </button>
            ))}
          </div>
        </div>

        {state === 'loading' && (
          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
            {Array.from({ length: 8 }).map((_, index) => (
              <SkeletonCard key={index} />
            ))}
          </div>
        )}

        {state === 'error' && (
          <section className="rounded-lg border border-red-200 bg-red-50 p-5 text-red-700" role="alert">
            <div className="flex items-start gap-3">
              <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
              <div>
                <h2 className="font-semibold">Dashboard indisponível</h2>
                <p className="mt-1 text-sm">{error}</p>
              </div>
            </div>
          </section>
        )}

        {state === 'ready' && (
          <>
            {warning && (
              <section className="rounded-lg border border-secondary-200 bg-secondary-50 p-4 text-secondary-900" role="status">
                <div className="flex items-start gap-3">
                  <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" aria-hidden="true" />
                  <p className="text-sm font-medium">{warning}</p>
                </div>
              </section>
            )}
            {activeTab === 'overview' && <OverviewPanel data={data} />}
            {activeTab === 'orders' && <OrdersPanel dre={data.dre} />}
            {activeTab === 'customers' && <CustomersPanel rfv={data.rfv} />}
            {activeTab === 'marketing' && <MarketingPanel data={data} />}
          </>
        )}

        {state === 'ready' && data.campaigns.length > 0 && activeTab === 'marketing' && (
          <section className="rounded-lg border border-border-light bg-bg-primary p-5 shadow-card">
            <div className="mb-4 flex items-center gap-2">
              <Megaphone className="h-5 w-5 text-primary-700" aria-hidden="true" />
              <h2 className="text-base font-semibold text-text-primary">Últimas campanhas</h2>
            </div>
            <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
              {data.campaigns.map((campaign) => (
                <article key={campaign.id} className="rounded-lg bg-bg-secondary p-4">
                  <p className="truncate text-sm font-semibold text-text-primary">{campaign.name}</p>
                  <p className="mt-1 text-xs text-text-muted">{campaign.status}</p>
                  <div className="mt-3 flex items-center justify-between text-sm">
                    <span className="text-text-secondary">Enviadas</span>
                    <span className="font-semibold text-text-primary">{formatNumber(campaign.sentCount)} / {formatNumber(campaign.totalRecipients)}</span>
                  </div>
                </article>
              ))}
            </div>
          </section>
        )}
      </div>
    </main>
  )
}
