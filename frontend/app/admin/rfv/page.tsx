'use client'

import { useCallback, useEffect, useState } from 'react'
import { Plus, Users2 } from 'lucide-react'
import { useRouter } from 'next/navigation'
import { api } from '@/lib/api'
import type { RfvScoreResponse, RfvSegment } from '@/types/campaign'

// ── Helpers ───────────────────────────────────────────────────────────────────

const formatCents = (cents: number) =>
  (cents / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })

type FilterOption = {
  value: RfvSegment | 'ALL'
  label: string
  color: string
  activeClass: string
}

const FILTER_OPTIONS: FilterOption[] = [
  { value: 'ALL',      label: 'Todos',       color: 'text-text-secondary', activeClass: 'bg-primary-700 text-white'   },
  { value: 'LOYAL',    label: 'Fieis',        color: 'text-green-700',      activeClass: 'bg-green-600 text-white'     },
  { value: 'AT_RISK',  label: 'Em risco',     color: 'text-yellow-700',     activeClass: 'bg-yellow-500 text-white'    },
  { value: 'INACTIVE', label: 'Inativos',     color: 'text-red-700',        activeClass: 'bg-red-600 text-white'       },
  { value: 'NEW',      label: 'Novos',        color: 'text-blue-700',       activeClass: 'bg-blue-600 text-white'      },
]

const SEGMENT_BADGE: Record<string, string> = {
  LOYAL:    'bg-green-100 text-green-700',
  AT_RISK:  'bg-yellow-100 text-yellow-700',
  INACTIVE: 'bg-red-100 text-red-700',
  NEW:      'bg-blue-100 text-blue-700',
}

const SEGMENT_LABEL: Record<string, string> = {
  LOYAL:    'Fiel',
  AT_RISK:  'Em risco',
  INACTIVE: 'Inativo',
  NEW:      'Novo',
}

const SEGMENT_TO_CAMPAIGN: Record<string, string> = {
  LOYAL:    'RFV_LOYAL',
  AT_RISK:  'RFV_AT_RISK',
  INACTIVE: 'RFV_INACTIVE',
}

// ── Skeleton ──────────────────────────────────────────────────────────────────

function RfvSkeleton() {
  return (
    <div className="animate-pulse space-y-4" aria-busy="true" aria-label="Carregando dados RFV...">
      {/* Cards */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {Array.from({ length: 4 }).map((_, i) => (
          <div key={i} className="rounded-2xl bg-bg-primary p-4 shadow-card">
            <div className="mb-2 h-4 w-1/2 rounded bg-bg-tertiary" aria-hidden="true" />
            <div className="h-7 w-1/3 rounded bg-bg-tertiary" aria-hidden="true" />
          </div>
        ))}
      </div>
      {/* Tabela */}
      <div className="rounded-2xl bg-bg-primary shadow-card overflow-hidden">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border-light">
              {['Cliente', 'Ultima compra', 'Pedidos 90d', 'Ticket medio', 'Segmento'].map((h) => (
                <th key={h} className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-text-muted">
                  {h}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {Array.from({ length: 5 }).map((_, i) => (
              <tr key={i} className="border-b border-border-light">
                {Array.from({ length: 5 }).map((__, j) => (
                  <td key={j} className="px-4 py-3">
                    <div className="h-4 rounded bg-bg-tertiary" aria-hidden="true" />
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}

// ── Cards de resumo ───────────────────────────────────────────────────────────

interface SummaryCardsProps {
  customers: RfvScoreResponse[]
}

function SummaryCards({ customers }: SummaryCardsProps) {
  const counts = {
    LOYAL:    customers.filter((c) => c.segment === 'LOYAL').length,
    AT_RISK:  customers.filter((c) => c.segment === 'AT_RISK').length,
    INACTIVE: customers.filter((c) => c.segment === 'INACTIVE').length,
    NEW:      customers.filter((c) => c.segment === 'NEW').length,
  }
  const cards = [
    { label: 'Fieis',    count: counts.LOYAL,    bg: 'bg-green-50',  text: 'text-green-700',  sub: 'text-green-600'  },
    { label: 'Em risco', count: counts.AT_RISK,  bg: 'bg-yellow-50', text: 'text-yellow-700', sub: 'text-yellow-600' },
    { label: 'Inativos', count: counts.INACTIVE, bg: 'bg-red-50',    text: 'text-red-700',    sub: 'text-red-600'    },
    { label: 'Novos',    count: counts.NEW,       bg: 'bg-blue-50',   text: 'text-blue-700',   sub: 'text-blue-600'   },
  ]
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
      {cards.map((c) => (
        <div key={c.label} className={['rounded-2xl px-4 py-4 shadow-card', c.bg].join(' ')}>
          <p className={['text-xs font-semibold uppercase tracking-wider', c.sub].join(' ')}>{c.label}</p>
          <p className={['mt-1 text-2xl font-bold', c.text].join(' ')}>{c.count}</p>
        </div>
      ))}
    </div>
  )
}

// ── Pagina principal ──────────────────────────────────────────────────────────

export default function RfvPage() {
  const router = useRouter()
  const [filter, setFilter] = useState<RfvSegment | 'ALL'>('ALL')
  const [allCustomers, setAllCustomers] = useState<RfvScoreResponse[]>([])
  const [displayed, setDisplayed] = useState<RfvScoreResponse[]>([])
  const [loadState, setLoadState] = useState<'loading' | 'error' | 'ok' | 'empty'>('loading')

  const load = useCallback(async (seg: RfvSegment | 'ALL') => {
    setLoadState('loading')
    try {
      const url = seg === 'ALL' ? '/rfv' : `/rfv?segment=${seg}`
      const data = await api.get<RfvScoreResponse[]>(url)
      if (seg === 'ALL') setAllCustomers(data)
      setDisplayed(data)
      setLoadState(data.length === 0 ? 'empty' : 'ok')
    } catch {
      setLoadState('error')
    }
  }, [])

  useEffect(() => {
    void load('ALL')
  }, [load])

  function handleFilter(seg: RfvSegment | 'ALL') {
    setFilter(seg)
    void load(seg)
  }

  function handleCreateCampaign() {
    const campaignSeg = filter !== 'ALL' ? SEGMENT_TO_CAMPAIGN[filter] : undefined
    const qs = campaignSeg ? `?segment=${campaignSeg}` : ''
    router.push(`/admin/campanhas${qs}`)
  }

  return (
    <div className="flex min-h-screen flex-col bg-bg-secondary">
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8">
        {/* Cabecalho */}
        <div className="mb-6 flex items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <Users2 className="h-6 w-6 text-primary-700" aria-hidden="true" />
            <h2 className="text-2xl font-bold text-text-primary">Analise RFV</h2>
          </div>
          <button onClick={handleCreateCampaign} className="btn-primary flex items-center gap-2">
            <Plus className="h-4 w-4" aria-hidden="true" />
            Criar campanha
          </button>
        </div>

        {/* Filtros por segmento */}
        <div className="mb-4 flex flex-wrap gap-2" role="group" aria-label="Filtrar por segmento">
          {FILTER_OPTIONS.map((opt) => {
            const isActive = filter === opt.value
            return (
              <button
                key={opt.value}
                onClick={() => handleFilter(opt.value)}
                aria-pressed={isActive}
                className={[
                  'inline-flex min-h-11 items-center justify-center rounded-full border px-4 text-sm font-medium transition-colors',
                  isActive
                    ? opt.activeClass + ' border-transparent'
                    : 'border-border-medium bg-bg-primary ' + opt.color + ' hover:bg-bg-tertiary',
                ].join(' ')}
              >
                {opt.label}
              </button>
            )
          })}
        </div>

        {/* 1. Estado: carregando */}
        {loadState === 'loading' && <RfvSkeleton />}

        {/* 2. Estado: erro */}
        {loadState === 'error' && (
          <div
            role="alert"
            className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-12 text-center shadow-card"
          >
            <p className="text-base font-medium text-text-primary">
              Nao foi possivel carregar os dados RFV.
            </p>
            <button className="btn-primary" onClick={() => void load(filter)}>
              Tentar novamente
            </button>
          </div>
        )}

        {/* 3. Estado: vazio */}
        {loadState === 'empty' && (
          <div className="flex flex-col items-center gap-3 rounded-2xl bg-bg-primary p-16 text-center shadow-card">
            <Users2 className="h-12 w-12 text-text-muted" aria-hidden="true" />
            <p className="text-base font-medium text-text-primary">
              Nenhum cliente encontrado para este segmento
            </p>
          </div>
        )}

        {/* 4. Estado: ok */}
        {loadState === 'ok' && (
          <div className="space-y-4">
            {/* Cards de resumo — sempre baseados no total */}
            <SummaryCards customers={filter === 'ALL' ? allCustomers : allCustomers} />

            {/* Tabela */}
            <div className="rounded-2xl bg-bg-primary shadow-card overflow-hidden">
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border-light">
                      {['Cliente', 'Ultima compra', 'Pedidos 90d', 'Ticket medio', 'Segmento'].map((h) => (
                        <th
                          key={h}
                          className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-text-muted whitespace-nowrap"
                        >
                          {h}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {displayed.map((c) => (
                      <tr key={c.customerId} className="border-b border-border-light hover:bg-bg-secondary">
                        <td className="px-4 py-3 font-medium text-text-primary">{c.customerName}</td>
                        <td className="px-4 py-3 text-text-secondary">
                          {c.recencyDays === 0 ? 'Hoje' : `${c.recencyDays} dias atras`}
                        </td>
                        <td className="px-4 py-3 text-right text-text-secondary">{c.frequency}</td>
                        <td className="px-4 py-3 text-right text-text-secondary">
                          {formatCents(c.frequency > 0 ? Math.round(c.monetaryValue / c.frequency) : 0)}
                        </td>
                        <td className="px-4 py-3">
                          <span
                            className={[
                              'inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium',
                              SEGMENT_BADGE[c.segment] ?? 'bg-bg-tertiary text-text-secondary',
                            ].join(' ')}
                          >
                            {SEGMENT_LABEL[c.segment] ?? c.segment}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        )}
      </main>
    </div>
  )
}
