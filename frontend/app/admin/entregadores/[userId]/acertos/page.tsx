'use client'

import { FormEvent, useCallback, useEffect, useId, useRef, useState } from 'react'
import { useParams, useRouter } from 'next/navigation'
import {
  ArrowLeft,
  PackageOpen,
  PackageCheck,
  XCircle,
  Plus,
} from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { getToken } from '@/lib/auth'
import { useModalA11y } from '@/lib/use-modal-a11y'

// ── Tipos ─────────────────────────────────────────────────────────────────────

type DeliveryDriverResponse = {
  id: string
  name: string
  phone: string | null
  isActive: boolean
}

type Page<T> = {
  content: T[]
  totalElements: number
}

type DriverConfigResponse = {
  driverId: string
  dailyRateCents: number
  perDeliveryCents: number
  perKmCents: number
  notes: string | null
}

type SettlementType = 'FROTA' | 'FREELANCER'

type DriverSettlementResponse = {
  id: string
  driverId: string
  periodStart: string
  periodEnd: string
  deliveriesCount: number
  workingDays: number
  dailyTotalCents: number
  deliveryTotalCents: number
  kmTotalCents: number
  /** Metros que originaram o eixo km da FROTA (sistema ou override); null no FREELANCER. */
  kmTotalMeters: number | null
  /** Repasse total do FREELANCER (soma dos payouts das corridas DELIVERED); 0 na FROTA. */
  payoutTotalCents: number
  grossTotalCents: number
  /** Tipo de remuneracao congelado no fechamento: FROTA | FREELANCER. */
  settlementType: SettlementType
  /** Corridas aceitas do periodo sem payout_cents definido (contam como R$0). */
  offersWithoutPayout: number
  status: 'OPEN' | 'CLOSED'
  closedAt: string | null
  notes: string | null
}

// ── Helpers monetários ────────────────────────────────────────────────────────

function centsToDisplay(cents: number): string {
  return (cents / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
}

// G2: km agora e um override OPCIONAL de distancia (metros) no fechamento — nunca
// mais um valor em R$. O gestor digita em km; convertemos para metros no submit.
function kmDisplayToMeters(raw: string): number | null {
  const clean = raw.replace(/\./g, '').replace(',', '.').replace(/[^0-9.]/g, '')
  if (clean === '') return null
  const km = parseFloat(clean)
  return isNaN(km) ? null : Math.round(km * 1000)
}

function metersToKmDisplay(meters: number | null): string {
  if (meters === null) return '—'
  return (meters / 1000).toLocaleString('pt-BR', { maximumFractionDigits: 1 }) + ' km'
}

function settlementTypeLabel(type: SettlementType): string {
  return type === 'FREELANCER' ? 'Freelancer' : 'Frota'
}

function BadgeSettlementType({ type }: { type: SettlementType }) {
  const cls =
    type === 'FREELANCER'
      ? 'bg-blue-100 text-blue-700'
      : 'bg-primary-700/10 text-primary-700'
  return (
    <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-semibold ${cls}`}>
      {settlementTypeLabel(type)}
    </span>
  )
}

// ── Helpers de data ───────────────────────────────────────────────────────────

function formatDate(iso: string): string {
  return new Date(iso + 'T00:00:00').toLocaleDateString('pt-BR', {
    day: '2-digit', month: '2-digit', year: 'numeric',
  })
}

function formatDateTime(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('pt-BR', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  })
}

// ── Modal Abrir Acerto ────────────────────────────────────────────────────────

function ModalAbrirAcerto({
  userId,
  onClose,
  onOpened,
}: {
  userId: string
  onClose: () => void
  onOpened: () => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  useModalA11y(ref as React.RefObject<HTMLElement>, onClose)

  const titleId = useId()

  const [periodStart, setPeriodStart] = useState('')
  const [periodEnd,   setPeriodEnd]   = useState('')
  const [notes,       setNotes]       = useState('')
  const [saving,      setSaving]      = useState(false)
  const [error,       setError]       = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!periodStart || !periodEnd) {
      setError('Informe as datas de inicio e fim.')
      return
    }
    if (periodEnd < periodStart) {
      setError('A data fim deve ser igual ou posterior a data inicio.')
      return
    }
    setSaving(true)
    setError(null)
    try {
      await api.post<DriverSettlementResponse>('/drivers/settlements/open', {
        userId,
        periodStart,
        periodEnd,
        notes: notes.trim() || null,
      })
      onOpened()
      onClose()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao abrir acerto.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="w-full max-w-md rounded-2xl bg-bg-primary p-6 shadow-xl"
      >
        <h2 id={titleId} className="mb-5 text-lg font-bold text-text-primary">
          Abrir novo acerto
        </h2>

        <form onSubmit={(e) => void submit(e)} className="space-y-4" noValidate>
          <div>
            <label htmlFor="open-start" className="form-label">Data inicio</label>
            <input
              id="open-start"
              type="date"
              className="input-field w-full"
              value={periodStart}
              onChange={(e) => setPeriodStart(e.target.value)}
              required
              disabled={saving}
              aria-required="true"
            />
          </div>

          <div>
            <label htmlFor="open-end" className="form-label">Data fim</label>
            <input
              id="open-end"
              type="date"
              className="input-field w-full"
              value={periodEnd}
              min={periodStart}
              onChange={(e) => setPeriodEnd(e.target.value)}
              required
              disabled={saving}
              aria-required="true"
            />
          </div>

          <div>
            <label htmlFor="open-notes" className="form-label">Observacoes</label>
            <textarea
              id="open-notes"
              className="input-field w-full resize-none"
              rows={3}
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Informacoes adicionais..."
              disabled={saving}
            />
          </div>

          {error && (
            <p role="alert" className="form-error">{error}</p>
          )}

          <div className="flex gap-3 pt-1">
            <button type="button" className="btn-outline flex-1" onClick={onClose} disabled={saving}>
              Cancelar
            </button>
            <button type="submit" className="btn-primary flex-1" disabled={saving}>
              {saving ? 'Abrindo...' : 'Abrir acerto'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

// ── Modal Fechar Acerto ───────────────────────────────────────────────────────

function ModalFecharAcerto({
  settlement,
  config,
  onClose,
  onClosed,
}: {
  settlement: DriverSettlementResponse
  config: DriverConfigResponse | null
  onClose: () => void
  onClosed: () => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  useModalA11y(ref as React.RefObject<HTMLElement>, onClose)

  const titleId = useId()

  // A config de tarifas so existe para entregadores FROTA (o back nao expoe o
  // driverType diretamente aqui). Sem config, o acerto so pode ser FREELANCER
  // (repasse das corridas, calculado 100% no servidor) ou uma FROTA mal configurada
  // — nos dois casos os 3 eixos manuais nao fazem sentido na tela.
  const hasConfig = config !== null

  const [workingDays, setWorkingDays] = useState('')
  const [kmOverride,  setKmOverride]  = useState('')
  const [notes,       setNotes]       = useState(settlement.notes ?? '')
  const [saving,      setSaving]      = useState(false)
  const [error,       setError]       = useState<string | null>(null)
  const [result,      setResult]      = useState<DriverSettlementResponse | null>(null)

  // Preview ao vivo (so se aplica ao eixo FROTA; km so e recalculado aqui quando ha
  // override manual — sem override, o total real vem do fechamento no servidor).
  const days           = parseInt(workingDays, 10) || 0
  const kmOverrideMeters = kmDisplayToMeters(kmOverride)
  const dailyRate       = config?.dailyRateCents  ?? 0
  const perDeliv        = config?.perDeliveryCents ?? 0
  const perKm           = config?.perKmCents       ?? 0

  const dailyTotal    = days * dailyRate
  const deliveryTotal = settlement.deliveriesCount * perDeliv
  const kmTotalCalc    = kmOverrideMeters !== null
    ? Math.round((kmOverrideMeters / 1000) * perKm)
    : null
  const grossTotalKnown = dailyTotal + deliveryTotal + (kmTotalCalc ?? 0)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (hasConfig && (!workingDays || days <= 0)) {
      setError('Informe os dias trabalhados.')
      return
    }
    setSaving(true)
    setError(null)
    try {
      const res = await api.post<DriverSettlementResponse>(`/drivers/settlements/${settlement.id}/close`, {
        workingDays: hasConfig ? days : 0,
        kmOverrideMeters: hasConfig ? kmOverrideMeters : null,
        notes: notes.trim() || null,
      })
      onClosed()
      if (res.offersWithoutPayout > 0) {
        // D-C: nao bloqueia, mas o gestor precisa ver isso antes de sair da tela.
        setResult(res)
      } else {
        onClose()
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao fechar acerto.')
    } finally {
      setSaving(false)
    }
  }

  if (result) {
    return (
      <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
        <div
          ref={ref}
          role="dialog"
          aria-modal="true"
          aria-labelledby={titleId}
          className="w-full max-w-lg rounded-2xl bg-bg-primary p-6 shadow-xl"
        >
          <h2 id={titleId} className="mb-1 text-lg font-bold text-text-primary">
            Acerto fechado
          </h2>
          <div className="mb-4 flex items-center gap-2">
            <BadgeSettlementType type={result.settlementType} />
            <span className="text-sm text-text-secondary">
              Total: {centsToDisplay(result.grossTotalCents)}
            </span>
          </div>
          <div
            role="alert"
            className="rounded-lg bg-warning/10 px-4 py-3 text-sm font-medium text-warning"
          >
            ⚠️ {result.offersWithoutPayout} corrida(s) sem valor de repasse definido —
            contam como R$0 neste acerto.
          </div>
          <div className="mt-5 flex justify-end">
            <button type="button" className="btn-primary" onClick={onClose}>
              Entendi
            </button>
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4">
      <div
        ref={ref}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="w-full max-w-lg rounded-2xl bg-bg-primary p-6 shadow-xl"
      >
        <h2 id={titleId} className="mb-1 text-lg font-bold text-text-primary">
          Fechar acerto
        </h2>
        <p className="mb-5 text-sm text-text-secondary">
          Periodo: {formatDate(settlement.periodStart)} → {formatDate(settlement.periodEnd)}
        </p>

        <form onSubmit={(e) => void submit(e)} className="space-y-4" noValidate>
          {/* Info de entregas */}
          <div className="rounded-lg bg-bg-secondary px-4 py-3 text-sm">
            <span className="text-text-muted">Entregas contadas: </span>
            <span className="font-semibold text-text-primary">{settlement.deliveriesCount}</span>
          </div>

          {hasConfig ? (
            <>
              <div>
                <label htmlFor="close-days" className="form-label">Dias trabalhados</label>
                <input
                  id="close-days"
                  type="number"
                  min="0"
                  step="1"
                  className="input-field w-full"
                  value={workingDays}
                  onChange={(e) => setWorkingDays(e.target.value)}
                  placeholder="Ex: 22"
                  required
                  disabled={saving}
                  aria-required="true"
                />
              </div>

              <div>
                <label htmlFor="close-km" className="form-label">Override de distancia (opcional)</label>
                <div className="relative">
                  <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-sm text-text-muted">km</span>
                  <input
                    id="close-km"
                    type="text"
                    inputMode="decimal"
                    className="input-field w-full pl-10"
                    value={kmOverride}
                    onChange={(e) => setKmOverride(e.target.value)}
                    placeholder="0,0"
                    disabled={saving}
                  />
                </div>
                <p className="mt-1 text-xs text-text-muted">
                  Deixe em branco para usar a distancia registrada dos pedidos do periodo.
                  Preencha so para um ajuste manual excepcional (fica auditado).
                </p>
              </div>
            </>
          ) : (
            <div className="rounded-lg bg-bg-secondary px-4 py-3 text-sm text-text-secondary">
              Este entregador nao tem tarifas de frota configuradas. Se for freelancer, o
              repasse das corridas entregues no periodo sera somado automaticamente ao
              fechar. Se for frota, configure a remuneracao antes de fechar.
            </div>
          )}

          <div>
            <label htmlFor="close-notes" className="form-label">Observacoes</label>
            <textarea
              id="close-notes"
              className="input-field w-full resize-none"
              rows={2}
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Informacoes adicionais..."
              disabled={saving}
            />
          </div>

          {/* Preview ao vivo (so o eixo FROTA; repasse do freelancer so sai no fechamento) */}
          {hasConfig && (
            <div className="rounded-lg border border-border-light bg-bg-secondary p-4 text-sm">
              <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">
                Preview do calculo (frota)
              </p>
              <div className="space-y-1">
                <div className="flex justify-between text-text-secondary">
                  <span>Diarias: {days} dias × {centsToDisplay(dailyRate)}</span>
                  <span>{centsToDisplay(dailyTotal)}</span>
                </div>
                <div className="flex justify-between text-text-secondary">
                  <span>Entregas: {settlement.deliveriesCount} × {centsToDisplay(perDeliv)}</span>
                  <span>{centsToDisplay(deliveryTotal)}</span>
                </div>
                <div className="flex justify-between text-text-secondary">
                  <span>Km ({perKm > 0 ? centsToDisplay(perKm) + '/km' : 'sem tarifa'})</span>
                  <span>{kmTotalCalc !== null ? centsToDisplay(kmTotalCalc) : 'calculado no fechamento'}</span>
                </div>
                <div className="mt-2 flex justify-between border-t border-border-light pt-2 font-bold">
                  <span className="text-text-primary">Total bruto</span>
                  <span className="text-primary-700">
                    {kmTotalCalc !== null ? centsToDisplay(grossTotalKnown) : 'inclui km calculado no fechamento'}
                  </span>
                </div>
              </div>
            </div>
          )}

          {error && (
            <p role="alert" className="form-error">{error}</p>
          )}

          <div className="flex gap-3 pt-1">
            <button type="button" className="btn-outline flex-1" onClick={onClose} disabled={saving}>
              Cancelar
            </button>
            <button type="submit" className="btn-primary flex-1" disabled={saving}>
              {saving ? 'Fechando...' : 'Fechar acerto'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

// ── Skeleton tabela de historico ──────────────────────────────────────────────

function SkeletonHistorico() {
  return (
    <tbody className="divide-y divide-border-light">
      {Array.from({ length: 3 }).map((_, i) => (
        <tr key={i} className="animate-pulse">
          <td className="px-4 py-3"><div className="h-4 w-28 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-16 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-10 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-10 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-20 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-20 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-20 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-20 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-24 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-28 rounded bg-bg-tertiary" /></td>
        </tr>
      ))}
    </tbody>
  )
}

// ── Pagina de Acertos ─────────────────────────────────────────────────────────

type Notice = { type: 'success' | 'error'; message: string } | null

export default function AcertosPage() {
  const params = useParams()
  const router = useRouter()
  const driverId = params.userId as string

  const [driver,      setDriver]      = useState<DeliveryDriverResponse | null>(null)
  const [config,      setConfig]      = useState<DriverConfigResponse | null>(null)
  const [openSettl,   setOpenSettl]   = useState<DriverSettlementResponse | null>(null)
  const [history,     setHistory]     = useState<DriverSettlementResponse[]>([])
  const [loadingPage, setLoadingPage] = useState(true)
  const [loadingHist, setLoadingHist] = useState(true)
  const [pageError,   setPageError]   = useState<string | null>(null)
  const [histError,   setHistError]   = useState<string | null>(null)
  const [notice,      setNotice]      = useState<Notice>(null)
  const [showOpen,    setShowOpen]    = useState(false)
  const [showClose,   setShowClose]   = useState(false)

  const loadHeader = useCallback(async () => {
    setLoadingPage(true)
    setPageError(null)
    try {
      // Config de tarifas (dias/entrega/km) so existe para entregadores FROTA — um
      // FREELANCER (sem tarifas cadastradas) responde 404 aqui, o que e esperado e
      // NAO deve derrubar a pagina inteira.
      const configPromise = api
        .get<DriverConfigResponse>(`/drivers/${driverId}/config`)
        .catch((err) => {
          if (err instanceof ApiError && err.status === 404) return null
          throw err
        })
      const [driversRes, cfgRes, openRes] = await Promise.all([
        api.get<DeliveryDriverResponse[]>('/drivers'),
        configPromise,
        api.get<Page<DriverSettlementResponse>>(
          `/drivers/settlements?driverId=${driverId}&status=OPEN`,
        ),
      ])
      const found = driversRes.find((d) => d.id === driverId) ?? null
      setDriver(found)
      setConfig(cfgRes)
      setOpenSettl(openRes.content[0] ?? null)
    } catch (err) {
      setPageError(err instanceof ApiError ? err.message : 'Erro ao carregar dados do entregador.')
    } finally {
      setLoadingPage(false)
    }
  }, [driverId])

  const loadHistory = useCallback(async () => {
    setLoadingHist(true)
    setHistError(null)
    try {
      const res = await api.get<Page<DriverSettlementResponse>>(
        `/drivers/settlements?driverId=${driverId}&status=CLOSED`,
      )
      setHistory(res.content)
    } catch (err) {
      setHistError(err instanceof ApiError ? err.message : 'Erro ao carregar historico.')
    } finally {
      setLoadingHist(false)
    }
  }, [driverId])

  useEffect(() => {
    if (!getToken()) { router.replace('/login'); return }
    void loadHeader()
    void loadHistory()
  }, [loadHeader, loadHistory, router])

  function handleOpened() {
    setNotice({ type: 'success', message: 'Acerto aberto com sucesso.' })
    void loadHeader()
    void loadHistory()
  }

  function handleClosed() {
    setNotice({ type: 'success', message: 'Acerto fechado com sucesso.' })
    void loadHeader()
    void loadHistory()
  }

  return (
    <div className="min-h-screen bg-bg-secondary">
      <main className="mx-auto flex w-full max-w-5xl flex-col gap-5 px-4 py-6">

        {/* Botao voltar */}
        <button
          className="inline-flex w-fit items-center gap-1.5 text-sm text-text-secondary hover:text-text-primary"
          onClick={() => router.push('/admin/entregadores')}
        >
          <ArrowLeft className="h-4 w-4" aria-hidden="true" />
          Voltar para entregadores
        </button>

        {/* Banner de aviso */}
        {notice && (
          <div
            role={notice.type === 'error' ? 'alert' : 'status'}
            className={[
              'rounded-lg px-4 py-3 text-sm font-medium',
              notice.type === 'success' ? 'bg-success/10 text-success' : 'bg-error/10 text-error',
            ].join(' ')}
          >
            {notice.message}
          </div>
        )}

        {/* Erro de carregamento */}
        {pageError && (
          <div role="alert" className="flex flex-col items-center gap-2 rounded-lg bg-bg-primary px-4 py-10 text-center shadow-card">
            <XCircle className="h-8 w-8 text-error" aria-hidden="true" />
            <p className="text-sm text-error">{pageError}</p>
            <button className="btn-outline mt-2" onClick={() => void loadHeader()}>
              Tentar novamente
            </button>
          </div>
        )}

        {/* Cabecalho do entregador */}
        {!pageError && (
          <header className="rounded-lg bg-bg-primary p-5 shadow-card">
            {loadingPage ? (
              <div className="space-y-2 animate-pulse">
                <div className="h-6 w-48 rounded bg-bg-tertiary" />
                <div className="h-4 w-64 rounded bg-bg-tertiary" />
                <div className="h-4 w-80 rounded bg-bg-tertiary" />
              </div>
            ) : driver ? (
              <div className="flex flex-col gap-1 sm:flex-row sm:items-start sm:justify-between">
                <div>
                  <h1 className="text-2xl font-bold text-text-primary">{driver.name}</h1>
                  <p className="mt-0.5 text-sm text-text-secondary">{driver.phone ?? '—'}</p>
                  {config && (
                    <p className="mt-2 text-sm text-text-muted">
                      Remuneracao: {centsToDisplay(config.dailyRateCents)}/dia
                      {config.perDeliveryCents > 0 && (
                        <> &middot; {centsToDisplay(config.perDeliveryCents)}/entrega</>
                      )}
                      {config.perKmCents > 0 && (
                        <> &middot; {centsToDisplay(config.perKmCents)}/km</>
                      )}
                    </p>
                  )}
                </div>
                {!openSettl && (
                  <button
                    className="btn-primary mt-3 inline-flex shrink-0 items-center gap-2 sm:mt-0"
                    onClick={() => setShowOpen(true)}
                  >
                    <Plus className="h-4 w-4" aria-hidden="true" />
                    Abrir novo acerto
                  </button>
                )}
              </div>
            ) : (
              <p className="text-sm text-text-muted">Entregador nao encontrado.</p>
            )}
          </header>
        )}

        {/* Acerto em aberto */}
        {!loadingPage && openSettl && (
          <section className="rounded-lg border-2 border-primary-700/30 bg-bg-primary p-5 shadow-card">
            <div className="mb-4 flex items-center justify-between">
              <div className="flex items-center gap-2">
                <PackageOpen className="h-5 w-5 text-primary-700" aria-hidden="true" />
                <h2 className="font-semibold text-text-primary">Acerto em aberto</h2>
              </div>
              <span className="inline-flex items-center rounded-full bg-amber-100 px-2.5 py-0.5 text-xs font-semibold text-amber-700">
                Em aberto
              </span>
            </div>

            <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-4">
              <div>
                <dt className="text-text-muted">Inicio</dt>
                <dd className="font-medium text-text-primary">{formatDate(openSettl.periodStart)}</dd>
              </div>
              <div>
                <dt className="text-text-muted">Fim previsto</dt>
                <dd className="font-medium text-text-primary">{formatDate(openSettl.periodEnd)}</dd>
              </div>
              <div>
                <dt className="text-text-muted">Entregas ate agora</dt>
                <dd className="font-medium text-text-primary">{openSettl.deliveriesCount}</dd>
              </div>
              <div>
                <dt className="text-text-muted">Total parcial</dt>
                <dd className="font-medium text-text-primary">{centsToDisplay(openSettl.grossTotalCents)}</dd>
              </div>
            </dl>

            {openSettl.notes && (
              <p className="mt-3 text-xs text-text-muted">{openSettl.notes}</p>
            )}

            <div className="mt-4 flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-end">
              <button
                className="btn-primary inline-flex items-center gap-2"
                onClick={() => setShowClose(true)}
              >
                <PackageCheck className="h-4 w-4" aria-hidden="true" />
                Fechar acerto
              </button>
            </div>
          </section>
        )}

        {/* Historico de acertos */}
        <section className="overflow-hidden rounded-lg bg-bg-primary shadow-card">
          <div className="flex items-center gap-2 border-b border-border-light px-4 py-3">
            <PackageCheck className="h-4 w-4 text-text-muted" aria-hidden="true" />
            <h2 className="text-sm font-semibold text-text-primary">Historico de acertos</h2>
          </div>

          {histError ? (
            <div role="alert" className="flex flex-col items-center gap-2 px-4 py-8 text-center">
              <XCircle className="h-7 w-7 text-error" aria-hidden="true" />
              <p className="text-sm text-error">{histError}</p>
              <button className="btn-outline mt-2" onClick={() => void loadHistory()}>
                Tentar novamente
              </button>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="min-w-full text-sm" aria-label="Historico de acertos">
                <thead className="bg-bg-secondary text-left text-xs uppercase text-text-muted">
                  <tr>
                    <th scope="col" className="px-4 py-3">Periodo</th>
                    <th scope="col" className="px-4 py-3">Tipo</th>
                    <th scope="col" className="px-4 py-3">Entregas</th>
                    <th scope="col" className="px-4 py-3">Dias</th>
                    <th scope="col" className="px-4 py-3">Diarias</th>
                    <th scope="col" className="px-4 py-3">Por entrega</th>
                    <th scope="col" className="px-4 py-3">Km</th>
                    <th scope="col" className="px-4 py-3">Repasse</th>
                    <th scope="col" className="px-4 py-3 font-bold">Total bruto</th>
                    <th scope="col" className="px-4 py-3">Fechado em</th>
                  </tr>
                </thead>

                {loadingHist ? (
                  <SkeletonHistorico />
                ) : history.length === 0 ? (
                  <tbody>
                    <tr>
                      <td colSpan={10} className="px-4 py-10 text-center text-sm text-text-muted">
                        Nenhum acerto fechado.
                      </td>
                    </tr>
                  </tbody>
                ) : (
                  <tbody className="divide-y divide-border-light">
                    {history.map((s) => {
                      const isFreelancer = s.settlementType === 'FREELANCER'
                      return (
                        <tr key={s.id} className="hover:bg-bg-secondary/70">
                          <td className="whitespace-nowrap px-4 py-3 text-text-secondary">
                            {formatDate(s.periodStart)} → {formatDate(s.periodEnd)}
                          </td>
                          <td className="px-4 py-3"><BadgeSettlementType type={s.settlementType} /></td>
                          <td className="px-4 py-3 text-text-secondary">{s.deliveriesCount}</td>
                          <td className="px-4 py-3 text-text-secondary">{isFreelancer ? '—' : s.workingDays}</td>
                          <td className="px-4 py-3 text-text-secondary">{isFreelancer ? '—' : centsToDisplay(s.dailyTotalCents)}</td>
                          <td className="px-4 py-3 text-text-secondary">{isFreelancer ? '—' : centsToDisplay(s.deliveryTotalCents)}</td>
                          <td className="px-4 py-3 text-text-secondary">{isFreelancer ? '—' : metersToKmDisplay(s.kmTotalMeters)}</td>
                          <td className="px-4 py-3 text-text-secondary">{isFreelancer ? centsToDisplay(s.payoutTotalCents) : '—'}</td>
                          <td className="px-4 py-3 font-bold text-primary-700">
                            {centsToDisplay(s.grossTotalCents)}
                          </td>
                          <td className="whitespace-nowrap px-4 py-3 text-text-muted">
                            {formatDateTime(s.closedAt)}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                )}
              </table>
            </div>
          )}
        </section>
      </main>

      {/* Modais */}
      {showOpen && (
        <ModalAbrirAcerto
          userId={driverId}
          onClose={() => setShowOpen(false)}
          onOpened={handleOpened}
        />
      )}

      {showClose && openSettl && (
        <ModalFecharAcerto
          settlement={openSettl}
          config={config}
          onClose={() => setShowClose(false)}
          onClosed={handleClosed}
        />
      )}
    </div>
  )
}
