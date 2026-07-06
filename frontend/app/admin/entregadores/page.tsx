'use client'

import { FormEvent, useCallback, useEffect, useId, useRef, useState } from 'react'
import { useRouter } from 'next/navigation'
import {
  Truck,
  Settings2,
  ArrowRight,
  XCircle,
  Smartphone,
  Unlink,
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
  userId: string | null
}

type Page<T> = {
  content: T[]
  totalElements: number
}

type DriverConfigResponse = {
  userId: string
  dailyRateCents: number
  perDeliveryCents: number
  perKmCents: number
  notes: string | null
}

type DriverSettlementResponse = {
  id: string
  userId: string
  status: 'OPEN' | 'CLOSED'
}

type UserResponse = {
  id: string
  firstName: string | null
  lastName: string | null
  email: string
  role: string
  isActive: boolean
}

type PageOrList<T> = Page<T> | T[]

// ── Helpers monetários ────────────────────────────────────────────────────────

/** Converte centavos → "R$ 1.234,56" */
function centsToDisplay(cents: number): string {
  return (cents / 100).toLocaleString('pt-BR', {
    style: 'currency',
    currency: 'BRL',
  })
}

/** Converte string mascarada "1.234,56" → centavos inteiros */
function displayToCents(masked: string): number {
  const clean = masked.replace(/\./g, '').replace(',', '.').replace(/[^0-9.]/g, '')
  const value = parseFloat(clean)
  return isNaN(value) ? 0 : Math.round(value * 100)
}

/** Aplica máscara moeda BR ao digitar (ex: "1234" → "12,34") */
function maskCurrency(raw: string): string {
  const digits = raw.replace(/\D/g, '')
  if (digits === '') return ''
  const num = parseInt(digits, 10) / 100
  return num.toLocaleString('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

// ── Helpers de vinculo usuario <-> entregador ─────────────────────────────────

/** Nome exibivel de um usuario (nome completo + email) */
function userLabel(u: UserResponse): string {
  const name = [u.firstName, u.lastName].filter(Boolean).join(' ')
  return name ? `${name} (${u.email})` : u.email
}

/** Mensagens pt-BR para os erros conhecidos do vinculo (400/404/409) */
function linkErrorMessage(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    if (err.status === 400) return 'O usuario selecionado precisa ter o papel DRIVER.'
    if (err.status === 404) return 'Usuario ou entregador nao encontrado neste restaurante.'
    if (err.status === 409) return 'Este usuario ja esta vinculado a outro entregador.'
    return err.message
  }
  return fallback
}

// ── Skeleton ──────────────────────────────────────────────────────────────────

function SkeletonRows({ rows = 5 }: { rows?: number }) {
  return (
    <tbody className="divide-y divide-border-light">
      {Array.from({ length: rows }).map((_, i) => (
        <tr key={i} className="animate-pulse">
          <td className="px-4 py-3"><div className="h-4 w-36 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-44 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-16 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-28 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-20 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-32 rounded bg-bg-tertiary" /></td>
          <td className="px-4 py-3"><div className="h-4 w-24 rounded bg-bg-tertiary" /></td>
        </tr>
      ))}
    </tbody>
  )
}

// ── Modal Configurar Remuneracao ──────────────────────────────────────────────

function ModalConfigurarRemuneracao({
  driver,
  onClose,
  onSaved,
}: {
  driver: DeliveryDriverResponse
  onClose: () => void
  onSaved: () => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  useModalA11y(ref as React.RefObject<HTMLElement>, onClose)

  const titleId = useId()

  const [loading, setLoading]   = useState(true)
  const [saving,  setSaving]    = useState(false)
  const [error,   setError]     = useState<string | null>(null)

  const [daily,    setDaily]    = useState('')
  const [delivery, setDelivery] = useState('')
  const [km,       setKm]       = useState('')
  const [notes,    setNotes]    = useState('')

  useEffect(() => {
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const cfg = await api.get<DriverConfigResponse>(`/drivers/${driver.id}/config`)
        setDaily(maskCurrency(String(cfg.dailyRateCents)))
        setDelivery(maskCurrency(String(cfg.perDeliveryCents)))
        setKm(maskCurrency(String(cfg.perKmCents)))
        setNotes(cfg.notes ?? '')
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Erro ao carregar configuracao.')
      } finally {
        setLoading(false)
      }
    }
    void load()
  }, [driver.id])

  async function submit(e: FormEvent) {
    e.preventDefault()
    setSaving(true)
    setError(null)
    try {
      await api.put<DriverConfigResponse>(`/drivers/${driver.id}/config`, {
        dailyRateCents:   displayToCents(daily),
        perDeliveryCents: displayToCents(delivery),
        perKmCents:       displayToCents(km),
        notes: notes.trim() || null,
      })
      onSaved()
      onClose()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao salvar configuracao.')
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
        <h2 id={titleId} className="mb-1 text-lg font-bold text-text-primary">
          Configurar remuneracao
        </h2>
        <p className="mb-5 text-sm text-text-secondary">
          {driver.name} &middot; {driver.phone ?? '—'}
        </p>

        {loading ? (
          <div className="space-y-3">
            {Array.from({ length: 4 }).map((_, i) => (
              <div key={i} className="h-10 animate-pulse rounded bg-bg-tertiary" />
            ))}
          </div>
        ) : (
          <form onSubmit={(e) => void submit(e)} className="space-y-4" noValidate>
            <div>
              <label htmlFor="cfg-daily" className="form-label">Diaria (R$/dia)</label>
              <div className="relative">
                <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-sm text-text-muted">R$</span>
                <input
                  id="cfg-daily"
                  type="text"
                  inputMode="numeric"
                  className="input-field w-full pl-9"
                  value={daily}
                  onChange={(e) => setDaily(maskCurrency(e.target.value))}
                  placeholder="0,00"
                  disabled={saving}
                  aria-required="true"
                />
              </div>
            </div>

            <div>
              <label htmlFor="cfg-delivery" className="form-label">Por entrega (R$/entrega)</label>
              <div className="relative">
                <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-sm text-text-muted">R$</span>
                <input
                  id="cfg-delivery"
                  type="text"
                  inputMode="numeric"
                  className="input-field w-full pl-9"
                  value={delivery}
                  onChange={(e) => setDelivery(maskCurrency(e.target.value))}
                  placeholder="0,00"
                  disabled={saving}
                  aria-required="true"
                />
              </div>
            </div>

            <div>
              <label htmlFor="cfg-km" className="form-label">Por km (R$/km — opcional)</label>
              <div className="relative">
                <span className="pointer-events-none absolute inset-y-0 left-3 flex items-center text-sm text-text-muted">R$</span>
                <input
                  id="cfg-km"
                  type="text"
                  inputMode="numeric"
                  className="input-field w-full pl-9"
                  value={km}
                  onChange={(e) => setKm(maskCurrency(e.target.value))}
                  placeholder="0,00"
                  disabled={saving}
                />
              </div>
            </div>

            <div>
              <label htmlFor="cfg-notes" className="form-label">Observacoes</label>
              <textarea
                id="cfg-notes"
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
              <button
                type="button"
                className="btn-outline flex-1"
                onClick={onClose}
                disabled={saving}
              >
                Cancelar
              </button>
              <button
                type="submit"
                className="btn-primary flex-1"
                disabled={saving}
              >
                {saving ? 'Salvando...' : 'Salvar'}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  )
}

// ── Pagina principal ──────────────────────────────────────────────────────────

// ── Modal Vincular Usuario (acesso ao app) ────────────────────────────────────

function ModalVincularUsuario({
  driver,
  linkedUserIds,
  onClose,
  onLinked,
}: {
  driver: DeliveryDriverResponse
  linkedUserIds: string[]
  onClose: () => void
  onLinked: () => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  useModalA11y(ref as React.RefObject<HTMLElement>, onClose)

  const titleId = useId()
  const selectId = useId()

  const [loading,  setLoading]  = useState(true)
  const [saving,   setSaving]   = useState(false)
  const [error,    setError]    = useState<string | null>(null)
  const [users,    setUsers]    = useState<UserResponse[]>([])
  const [selected, setSelected] = useState('')

  useEffect(() => {
    async function load() {
      setLoading(true)
      setError(null)
      try {
        const res = await api.get<PageOrList<UserResponse>>('/users?size=200')
        const list = Array.isArray(res) ? res : res.content
        setUsers(list.filter((u) => u.role === 'DRIVER' && u.isActive))
      } catch (err) {
        if (err instanceof ApiError && err.status === 403) {
          setError('Voce nao tem permissao para listar usuarios. Peca a um administrador ou gerente.')
        } else {
          setError(err instanceof ApiError ? err.message : 'Erro ao carregar usuarios.')
        }
      } finally {
        setLoading(false)
      }
    }
    void load()
  }, [])

  // Exclui usuarios ja vinculados a algum entregador (o 409 do backend segue como rede de seguranca)
  const available = users.filter((u) => !linkedUserIds.includes(u.id))

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!selected) {
      setError('Selecione um usuario para vincular.')
      return
    }
    setSaving(true)
    setError(null)
    try {
      await api.put<unknown>(`/delivery/drivers/${driver.id}/user`, { userId: selected })
      onLinked()
      onClose()
    } catch (err) {
      setError(linkErrorMessage(err, 'Erro ao vincular usuario.'))
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
        <h2 id={titleId} className="mb-1 text-lg font-bold text-text-primary">
          Vincular usuario ao app
        </h2>
        <p className="mb-5 text-sm text-text-secondary">
          {driver.name} &middot; {driver.phone ?? '—'}
        </p>

        {loading ? (
          <div className="space-y-3">
            {Array.from({ length: 2 }).map((_, i) => (
              <div key={i} className="h-10 animate-pulse rounded bg-bg-tertiary" />
            ))}
          </div>
        ) : available.length === 0 && !error ? (
          <div>
            <p className="text-sm text-text-secondary">
              Nenhum usuario ativo com papel DRIVER disponivel para vinculo. Convide um usuario com o papel
              &quot;Entregador (app)&quot; na tela de Usuarios e tente novamente.
            </p>
            <div className="mt-5 flex gap-3">
              <button type="button" className="btn-outline flex-1" onClick={onClose}>
                Fechar
              </button>
            </div>
          </div>
        ) : (
          <form onSubmit={(e) => void submit(e)} className="space-y-4" noValidate>
            <div>
              <label htmlFor={selectId} className="form-label">Usuario (papel DRIVER)</label>
              <select
                id={selectId}
                className="input-field w-full"
                value={selected}
                onChange={(e) => setSelected(e.target.value)}
                disabled={saving}
                aria-required="true"
              >
                <option value="">Selecione um usuario...</option>
                {available.map((u) => (
                  <option key={u.id} value={u.id}>{userLabel(u)}</option>
                ))}
              </select>
              <p className="mt-2 text-xs text-text-muted">
                O usuario vinculado podera entrar no app do entregador com o proprio login.
                Usuarios ja vinculados a outro entregador nao aparecem na lista.
              </p>
            </div>

            {error && (
              <p role="alert" className="form-error">{error}</p>
            )}

            <div className="flex gap-3 pt-1">
              <button
                type="button"
                className="btn-outline flex-1"
                onClick={onClose}
                disabled={saving}
              >
                Cancelar
              </button>
              <button
                type="submit"
                className="min-h-11 flex-1 rounded-lg bg-primary-700 px-4 py-2.5 text-sm font-semibold text-white transition-colors hover:bg-primary-800 disabled:opacity-50"
                disabled={saving}
              >
                {saving ? 'Vinculando...' : 'Vincular'}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  )
}

// ── Acoes de acesso ao app (vincular / desvincular) ───────────────────────────

function AccessActions({
  driver,
  confirming,
  unlinking,
  onLink,
  onUnlinkRequest,
  onUnlinkConfirm,
  onUnlinkCancel,
}: {
  driver: DeliveryDriverResponse
  confirming: boolean
  unlinking: boolean
  onLink: () => void
  onUnlinkRequest: () => void
  onUnlinkConfirm: () => void
  onUnlinkCancel: () => void
}) {
  if (confirming) {
    return (
      <div className="inline-flex flex-wrap items-center gap-2">
        <span className="text-xs text-text-secondary">Desvincular acesso?</span>
        <button
          className="btn-outline min-h-11 px-3 text-sm text-error disabled:opacity-50"
          onClick={onUnlinkConfirm}
          disabled={unlinking}
        >
          {unlinking ? 'Aguarde...' : 'Sim'}
        </button>
        <button
          className="btn-outline min-h-11 px-3 text-sm"
          onClick={onUnlinkCancel}
          disabled={unlinking}
        >
          Nao
        </button>
      </div>
    )
  }
  if (driver.userId) {
    return (
      <div className="inline-flex flex-wrap items-center gap-2">
        <span className="inline-flex items-center rounded-full bg-success/10 px-2.5 py-0.5 text-xs font-semibold text-success">
          Vinculado
        </span>
        <button
          className="inline-flex min-h-11 items-center gap-1.5 rounded-lg border border-border-medium px-3 text-xs font-medium text-text-secondary transition-colors hover:bg-bg-tertiary hover:text-text-primary"
          aria-label={`Desvincular acesso ao app de ${driver.name}`}
          onClick={onUnlinkRequest}
        >
          <Unlink className="h-3.5 w-3.5" aria-hidden="true" />
          Desvincular
        </button>
      </div>
    )
  }
  return (
    <div className="inline-flex flex-wrap items-center gap-2">
      <span className="inline-flex items-center rounded-full bg-bg-tertiary px-2.5 py-0.5 text-xs font-semibold text-text-muted">
        Sem acesso
      </span>
      <button
        className="inline-flex min-h-11 items-center gap-1.5 rounded-lg bg-primary-700 px-3 text-xs font-medium text-white transition-colors hover:bg-primary-800"
        aria-label={`Vincular usuario do app a ${driver.name}`}
        onClick={onLink}
      >
        <Smartphone className="h-3.5 w-3.5" aria-hidden="true" />
        Vincular
      </button>
    </div>
  )
}

type Notice = { type: 'success' | 'error'; message: string } | null

function DriverMobileCard({
  driver,
  config,
  settlement,
  accessActions,
  onConfig,
  onSettlements,
}: {
  driver: DeliveryDriverResponse
  config?: DriverConfigResponse
  settlement?: DriverSettlementResponse
  accessActions: React.ReactNode
  onConfig: () => void
  onSettlements: () => void
}) {
  return (
    <article className="rounded-lg border border-border-light bg-bg-primary p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h3 className="truncate text-sm font-semibold text-text-primary">{driver.name}</h3>
          <p className="truncate text-xs text-text-muted">{driver.phone ?? 'Sem telefone'}</p>
        </div>
        {driver.isActive ? (
          <span className="shrink-0 rounded-full bg-success/10 px-2.5 py-0.5 text-xs font-semibold text-success">
            Ativo
          </span>
        ) : (
          <span className="shrink-0 rounded-full bg-bg-tertiary px-2.5 py-0.5 text-xs font-semibold text-text-muted">
            Inativo
          </span>
        )}
      </div>

      <dl className="mt-4 grid gap-3 text-sm">
        <div>
          <dt className="text-xs font-semibold uppercase text-text-muted">Remuneracao</dt>
          <dd className="mt-1 text-text-secondary">
            {config ? (
              <>
                {centsToDisplay(config.dailyRateCents)}/dia
                {config.perDeliveryCents > 0 && (
                  <> · {centsToDisplay(config.perDeliveryCents)}/entrega</>
                )}
              </>
            ) : (
              'Nao configurado'
            )}
          </dd>
        </div>
        <div>
          <dt className="text-xs font-semibold uppercase text-text-muted">Acerto atual</dt>
          <dd className="mt-1">
            {settlement ? (
              <span className="inline-flex rounded-full bg-amber-100 px-2.5 py-0.5 text-xs font-semibold text-amber-700">
                Em aberto
              </span>
            ) : (
              <span className="text-text-muted">Sem acerto aberto</span>
            )}
          </dd>
        </div>
      </dl>

      <div className="mt-4 grid grid-cols-2 gap-2">
        <button
          className="btn-outline inline-flex min-h-11 items-center justify-center gap-2 text-sm"
          onClick={onConfig}
        >
          <Settings2 className="h-4 w-4" aria-hidden="true" />
          Remuneracao
        </button>
        <button
          className="btn-secondary inline-flex min-h-11 items-center justify-center gap-2 text-sm"
          onClick={onSettlements}
        >
          Acertos
          <ArrowRight className="h-4 w-4" aria-hidden="true" />
        </button>
      </div>

      <div className="mt-3 border-t border-border-light pt-3">
        <p className="mb-2 text-xs font-semibold uppercase text-text-muted">Acesso ao app</p>
        {accessActions}
      </div>
    </article>
  )
}

export default function EntregadoresPage() {
  const router = useRouter()

  const [drivers,    setDrivers]    = useState<DeliveryDriverResponse[]>([])
  const [settlements, setSettlements] = useState<Record<string, DriverSettlementResponse>>({})
  const [configs,    setConfigs]    = useState<Record<string, DriverConfigResponse>>({})
  const [loading,    setLoading]    = useState(true)
  const [pageError,  setPageError]  = useState<string | null>(null)
  const [notice,     setNotice]     = useState<Notice>(null)
  const [configTarget, setConfigTarget] = useState<DeliveryDriverResponse | null>(null)
  const [linkTarget,      setLinkTarget]      = useState<DeliveryDriverResponse | null>(null)
  const [confirmUnlinkId, setConfirmUnlinkId] = useState<string | null>(null)
  const [unlinkingId,     setUnlinkingId]     = useState<string | null>(null)

  const loadDrivers = useCallback(async () => {
    setLoading(true)
    setPageError(null)
    try {
      const list = await api.get<DeliveryDriverResponse[]>('/drivers')
      setDrivers(list)

      // Carregar config e acerto aberto em paralelo para cada entregador
      const [cfgResults, settlResults] = await Promise.all([
        Promise.allSettled(
          list.map((d) =>
            api.get<DriverConfigResponse>(`/drivers/${d.id}/config`),
          ),
        ),
        Promise.allSettled(
          list.map((d) =>
            api.get<Page<DriverSettlementResponse>>(
              `/drivers/settlements?driverId=${d.id}&status=OPEN`,
            ),
          ),
        ),
      ])

      const newConfigs: Record<string, DriverConfigResponse> = {}
      cfgResults.forEach((r, i) => {
        if (r.status === 'fulfilled') newConfigs[list[i].id] = r.value
      })

      const newSettlements: Record<string, DriverSettlementResponse> = {}
      settlResults.forEach((r, i) => {
        if (r.status === 'fulfilled' && r.value.content.length > 0) {
          newSettlements[list[i].id] = r.value.content[0]
        }
      })

      setConfigs(newConfigs)
      setSettlements(newSettlements)
    } catch (err) {
      setPageError(err instanceof ApiError ? err.message : 'Erro ao carregar entregadores.')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (!getToken()) { router.replace('/login'); return }
    queueMicrotask(() => {
      void loadDrivers()
    })
  }, [loadDrivers, router])

  const handleUnlink = useCallback(async (driver: DeliveryDriverResponse) => {
    setUnlinkingId(driver.id)
    setNotice(null)
    try {
      await api.put<unknown>(`/delivery/drivers/${driver.id}/user`, { userId: null })
      setNotice({ type: 'success', message: `Acesso ao app de ${driver.name} desvinculado.` })
      void loadDrivers()
    } catch (err) {
      setNotice({ type: 'error', message: linkErrorMessage(err, 'Erro ao desvincular usuario.') })
    } finally {
      setUnlinkingId(null)
      setConfirmUnlinkId(null)
    }
  }, [loadDrivers])

  function accessActionsFor(driver: DeliveryDriverResponse) {
    return (
      <AccessActions
        driver={driver}
        confirming={confirmUnlinkId === driver.id}
        unlinking={unlinkingId === driver.id}
        onLink={() => setLinkTarget(driver)}
        onUnlinkRequest={() => setConfirmUnlinkId(driver.id)}
        onUnlinkConfirm={() => void handleUnlink(driver)}
        onUnlinkCancel={() => setConfirmUnlinkId(null)}
      />
    )
  }

  return (
    <div className="min-h-screen bg-bg-secondary">
      <main className="mx-auto flex w-full max-w-5xl flex-col gap-5 px-4 py-6">

        {/* Cabecalho */}
        <header className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <h1 className="text-2xl font-bold text-text-primary">Entregadores</h1>
            <p className="mt-1 text-sm text-text-secondary">
              Gerencie remuneracao, acertos e acesso ao app dos entregadores.
            </p>
          </div>
        </header>

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

        {/* Tabela */}
        <section className="overflow-hidden rounded-lg bg-bg-primary shadow-card">
          <div className="flex items-center gap-2 border-b border-border-light px-4 py-3">
            <Truck className="h-4 w-4 text-text-muted" aria-hidden="true" />
            <h2 className="text-sm font-semibold text-text-primary">Lista de entregadores</h2>
          </div>

          {pageError ? (
            <div role="alert" className="flex flex-col items-center gap-2 px-4 py-10 text-center">
              <XCircle className="h-8 w-8 text-error" aria-hidden="true" />
              <p className="text-sm text-error">{pageError}</p>
              <button className="btn-outline mt-2" onClick={() => void loadDrivers()}>
                Tentar novamente
              </button>
            </div>
          ) : (
            <>
              <div className="grid gap-3 p-4 md:hidden">
                {loading ? (
                  Array.from({ length: 3 }).map((_, i) => (
                    <div key={i} className="h-36 animate-pulse rounded-lg bg-bg-tertiary" />
                  ))
                ) : drivers.length === 0 ? (
                  <p className="py-8 text-center text-sm text-text-muted">
                    Nenhum entregador cadastrado.
                  </p>
                ) : (
                  drivers.map((driver) => (
                    <DriverMobileCard
                      key={driver.id}
                      driver={driver}
                      config={configs[driver.id]}
                      settlement={settlements[driver.id]}
                      accessActions={accessActionsFor(driver)}
                      onConfig={() => setConfigTarget(driver)}
                      onSettlements={() => router.push(`/admin/entregadores/${driver.id}/acertos`)}
                    />
                  ))
                )}
              </div>

              <div className="hidden overflow-x-auto md:block">
                <table className="min-w-full text-sm" aria-label="Lista de entregadores">
                <thead className="bg-bg-secondary text-left text-xs uppercase text-text-muted">
                  <tr>
                    <th scope="col" className="px-4 py-3">Nome</th>
                    <th scope="col" className="px-4 py-3">Telefone</th>
                    <th scope="col" className="px-4 py-3">Status</th>
                    <th scope="col" className="px-4 py-3">Remuneracao</th>
                    <th scope="col" className="px-4 py-3">Acerto atual</th>
                    <th scope="col" className="px-4 py-3">Acesso ao app</th>
                    <th scope="col" className="px-4 py-3 text-right">Acoes</th>
                  </tr>
                </thead>

                {loading ? (
                  <SkeletonRows rows={5} />
                ) : drivers.length === 0 ? (
                  <tbody>
                    <tr>
                      <td colSpan={7} className="px-4 py-10 text-center text-sm text-text-muted">
                        Nenhum entregador cadastrado.
                      </td>
                    </tr>
                  </tbody>
                ) : (
                  <tbody className="divide-y divide-border-light">
                    {drivers.map((driver) => {
                      const cfg  = configs[driver.id]
                      const open = settlements[driver.id]
                      return (
                        <tr key={driver.id} className="hover:bg-bg-secondary/70">
                          <td className="px-4 py-3 font-medium text-text-primary">
                            {driver.name}
                          </td>
                          <td className="px-4 py-3 text-text-secondary">{driver.phone ?? '—'}</td>
                          <td className="px-4 py-3">
                            {driver.isActive ? (
                              <span className="inline-flex items-center rounded-full bg-success/10 px-2.5 py-0.5 text-xs font-semibold text-success">
                                Ativo
                              </span>
                            ) : (
                              <span className="inline-flex items-center rounded-full bg-bg-tertiary px-2.5 py-0.5 text-xs font-semibold text-text-muted">
                                Inativo
                              </span>
                            )}
                          </td>
                          <td className="px-4 py-3 text-text-secondary">
                            {cfg ? (
                              <span className="text-xs">
                                {centsToDisplay(cfg.dailyRateCents)}/dia
                                {cfg.perDeliveryCents > 0 && (
                                  <> &middot; {centsToDisplay(cfg.perDeliveryCents)}/entrega</>
                                )}
                              </span>
                            ) : (
                              <span className="text-xs text-text-muted">Nao configurado</span>
                            )}
                          </td>
                          <td className="px-4 py-3">
                            {open ? (
                              <span className="inline-flex items-center rounded-full bg-amber-100 px-2.5 py-0.5 text-xs font-semibold text-amber-700">
                                Em aberto
                              </span>
                            ) : (
                              <span className="text-xs text-text-muted">—</span>
                            )}
                          </td>
                          <td className="px-4 py-3">
                            {accessActionsFor(driver)}
                          </td>
                          <td className="px-4 py-3 text-right">
                            <div className="inline-flex items-center gap-2">
                              <button
                                className="icon-button"
                                aria-label={`Configurar remuneracao de ${driver.name}`}
                                title="Configurar remuneracao"
                                onClick={() => setConfigTarget(driver)}
                              >
                                <Settings2 className="h-4 w-4" aria-hidden="true" />
                              </button>
                              <button
                                className="inline-flex items-center gap-1.5 rounded-lg border border-border-medium px-3 py-1.5 text-xs font-medium text-text-secondary transition-colors hover:bg-bg-tertiary hover:text-text-primary"
                                aria-label={`Ver acertos de ${driver.name}`}
                                onClick={() => router.push(`/admin/entregadores/${driver.id}/acertos`)}
                              >
                                Ver acertos
                                <ArrowRight className="h-3.5 w-3.5" aria-hidden="true" />
                              </button>
                            </div>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                )}
                </table>
              </div>
            </>
          )}
        </section>
      </main>

      {/* Modal Configurar Remuneracao */}
      {configTarget && (
        <ModalConfigurarRemuneracao
          driver={configTarget}
          onClose={() => setConfigTarget(null)}
          onSaved={() => {
            setNotice({ type: 'success', message: 'Remuneracao atualizada com sucesso.' })
            void loadDrivers()
          }}
        />
      )}

      {/* Modal Vincular Usuario (acesso ao app) */}
      {linkTarget && (
        <ModalVincularUsuario
          driver={linkTarget}
          linkedUserIds={drivers.map((d) => d.userId).filter((id): id is string => !!id)}
          onClose={() => setLinkTarget(null)}
          onLinked={() => {
            setNotice({
              type: 'success',
              message: `Usuario vinculado ao entregador ${linkTarget.name} com sucesso.`,
            })
            void loadDrivers()
          }}
        />
      )}
    </div>
  )
}
