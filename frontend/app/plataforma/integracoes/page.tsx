'use client'

// Integrações — saúde de iFood, OpenDelivery, WAHA e LiteLLM (Fase 2).
// Polling a cada 60s com AbortController; semáforo sempre com texto + ícone
// (nunca só cor — daltonismo e luz forte).

import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Bike,
  Network,
  MessageCircle,
  Cpu,
  Plug,
  CheckCircle,
  AlertTriangle,
  XCircle,
  RefreshCw,
  Clock,
  type LucideIcon,
} from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { useSuperAdminGuard } from '@/lib/use-super-admin-guard'
import {
  type IntegrationStatus,
  type IntegrationsHealthResponse,
  INTEGRATION_STATUS_LABELS,
  formatDateTime,
} from '@/lib/platform'

const POLL_MS = 60_000

// Ícone por integração conhecida; Plug para desconhecidas
const INTEGRATION_ICONS: Record<string, LucideIcon> = {
  iFood: Bike,
  OpenDelivery: Network,
  WAHA: MessageCircle,
  LiteLLM: Cpu,
}

const INTEGRATION_DESCRIPTIONS: Record<string, string> = {
  iFood: 'Pedidos e status do marketplace',
  OpenDelivery: '99Food, Rappi e outros via padrão ABRASEL',
  WAHA: 'Bot e notificações de WhatsApp',
  LiteLLM: 'Gateway de IA (copiloto e bot)',
}

// Semáforo: texto + ícone + cor (nunca só cor)
const STATUS_META: Record<IntegrationStatus, { icon: LucideIcon; badge: string }> = {
  OK: { icon: CheckCircle, badge: 'bg-success-light text-success-dark' },
  DEGRADED: { icon: AlertTriangle, badge: 'bg-warning-light text-warning-dark' },
  DOWN: { icon: XCircle, badge: 'bg-error-light text-error-dark' },
}

function StatusBadge({ status }: { status: IntegrationStatus }) {
  const meta = STATUS_META[status] ?? STATUS_META.DOWN
  const label = INTEGRATION_STATUS_LABELS[status] ?? status
  const Icon = meta.icon
  return (
    <span
      role="status"
      aria-label={`Status: ${label}`}
      className={`inline-flex shrink-0 items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold ${meta.badge}`}
    >
      <Icon className="h-3.5 w-3.5" aria-hidden="true" />
      {label}
    </span>
  )
}

function CardsSkeleton() {
  return (
    <div aria-busy="true" aria-label="Carregando integrações" className="grid grid-cols-1 gap-4 sm:grid-cols-2">
      {Array.from({ length: 4 }).map((_, i) => (
        <div key={i} className="animate-pulse rounded-xl border border-border-light bg-bg-primary p-4">
          <div className="flex items-center gap-3">
            <div className="h-10 w-10 rounded-lg bg-bg-tertiary" />
            <div className="flex-1 space-y-2">
              <div className="h-4 w-24 rounded bg-bg-tertiary" />
              <div className="h-3 w-40 rounded bg-bg-tertiary" />
            </div>
            <div className="h-5 w-20 rounded-full bg-bg-tertiary" />
          </div>
        </div>
      ))}
    </div>
  )
}

export default function IntegracoesPage() {
  useSuperAdminGuard()

  const [data, setData] = useState<IntegrationsHealthResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const abortRef = useRef<AbortController | null>(null)

  const load = useCallback(async (manual = false) => {
    // cancela fetch anterior ainda em voo (troca de página ou clique rápido)
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller

    if (manual) setRefreshing(true)
    try {
      const res = await api.get<IntegrationsHealthResponse>(
        '/admin/integrations/health',
        controller.signal,
      )
      setData(res)
      setError(null)
    } catch (err) {
      if (controller.signal.aborted) return // abort não é erro para o usuário
      setError(err instanceof ApiError ? err.message : 'Erro ao consultar a saúde das integrações.')
    } finally {
      if (!controller.signal.aborted) {
        setLoading(false)
        setRefreshing(false)
      }
    }
  }, [])

  useEffect(() => {
    void load()
    const timer = setInterval(() => void load(), POLL_MS)
    return () => {
      clearInterval(timer)
      abortRef.current?.abort()
    }
  }, [load])

  return (
    <main className="mx-auto max-w-4xl p-4 sm:p-6">
      <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-text-primary">Integrações</h1>
          <p className="mt-0.5 text-sm text-text-secondary">
            Saúde dos serviços externos da plataforma. Atualização automática a cada 60 segundos.
          </p>
        </div>
        <button
          type="button"
          className="btn-outline inline-flex items-center gap-2"
          onClick={() => void load(true)}
          disabled={loading || refreshing}
        >
          <RefreshCw className={`h-4 w-4 ${refreshing ? 'animate-spin' : ''}`} aria-hidden="true" />
          {refreshing ? 'Atualizando...' : 'Atualizar agora'}
        </button>
      </div>

      {loading ? (
        <CardsSkeleton />
      ) : error && !data ? (
        <div
          role="alert"
          className="flex flex-wrap items-center gap-3 rounded-xl border border-error/30 bg-error-light px-4 py-3"
        >
          <AlertTriangle className="h-5 w-5 shrink-0 text-error-dark" aria-hidden="true" />
          <p className="min-w-0 flex-1 text-sm font-medium text-error-dark">{error}</p>
          <button
            type="button"
            onClick={() => void load(true)}
            className="btn-outline inline-flex items-center gap-2"
          >
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
            Tentar novamente
          </button>
        </div>
      ) : data && (
        <>
          {/* falha de refresh com dados antigos na tela: avisa sem apagar os cards */}
          {error && (
            <p role="alert" className="mb-3 inline-flex items-center gap-1.5 text-sm font-medium text-warning-dark">
              <AlertTriangle className="h-4 w-4 shrink-0" aria-hidden="true" />
              Falha ao atualizar — exibindo dados da última consulta. {error}
            </p>
          )}

          {data.cards.length === 0 ? (
            <div className="flex flex-col items-center gap-2 rounded-xl border border-border-light bg-bg-primary px-4 py-10 text-center">
              <Plug className="h-8 w-8 text-text-muted" aria-hidden="true" />
              <p className="text-sm font-medium text-text-primary">Nenhuma integração configurada</p>
              <p className="text-sm text-text-secondary">
                As integrações aparecem aqui assim que forem configuradas no backend.
              </p>
            </div>
          ) : (
            <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
              {data.cards.map((card) => {
                const Icon = INTEGRATION_ICONS[card.name] ?? Plug
                return (
                  <div key={card.name} className="rounded-xl border border-border-light bg-bg-primary p-4">
                    <div className="flex items-start justify-between gap-2">
                      <div className="flex min-w-0 items-center gap-3">
                        <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-bg-tertiary text-text-secondary">
                          <Icon className="h-5 w-5" aria-hidden="true" />
                        </div>
                        <div className="min-w-0">
                          <p className="font-semibold text-text-primary">{card.name}</p>
                          <p className="text-sm text-text-secondary">
                            {INTEGRATION_DESCRIPTIONS[card.name] ?? 'Serviço externo'}
                          </p>
                        </div>
                      </div>
                      <StatusBadge status={card.status} />
                    </div>
                    {card.detail && (
                      <p className="mt-3 border-t border-border-light pt-3 text-sm text-text-secondary">
                        {card.detail}
                      </p>
                    )}
                  </div>
                )
              })}
            </div>
          )}

          <p className="mt-4 inline-flex items-center gap-1.5 text-xs text-text-muted">
            <Clock className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
            Última atualização: {formatDateTime(data.updatedAt)}
          </p>
        </>
      )}
    </main>
  )
}
