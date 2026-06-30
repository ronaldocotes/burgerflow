'use client'

import {
  useCallback, useEffect, useId, useRef, useState,
} from 'react'
import {
  XCircle, AlertTriangle, Clock, Star, Search,
  RefreshCw, Gift, SlidersHorizontal, CheckCircle,
} from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { useModalA11y } from '@/lib/use-modal-a11y'
import type { LoyaltyStatusResponse, LoyaltyConfig } from '@/types/loyalty'

// ─── Helpers ─────────────────────────────────────────────────────────────────

function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(diff / 60_000)
  if (mins < 1) return 'agora'
  if (mins < 60) return mins + 'min atras'
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return hrs + 'h atras'
  return new Date(iso).toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' })
}

function signedDelta(n: number): string {
  return n > 0 ? '+' + n : String(n)
}

// ─── Toast ────────────────────────────────────────────────────────────────────

interface ToastState { id: number; message: string; type: 'success' | 'error' }

function useToast() {
  const [toasts, setToasts] = useState<ToastState[]>([])
  const counter = useRef(0)
  const show = useCallback((message: string, type: 'success' | 'error' = 'success') => {
    const id = ++counter.current
    setToasts(p => [...p, { id, message, type }])
    setTimeout(() => setToasts(p => p.filter(t => t.id !== id)), 3500)
  }, [])
  return { toasts, show }
}

function ToastContainer({ toasts }: { toasts: ToastState[] }) {
  if (!toasts.length) return null
  return (
    <div className="fixed bottom-4 right-4 z-50 flex flex-col gap-2" aria-live="polite" aria-atomic="false">
      {toasts.map(t => (
        <div key={t.id} role="status"
          className={[
            'rounded-xl px-4 py-3 text-sm font-medium shadow-dropdown',
            t.type === 'success' ? 'bg-success text-white' : 'bg-error text-white',
          ].join(' ')}>
          {t.message}
        </div>
      ))}
    </div>
  )
}

// ─── Skeleton ─────────────────────────────────────────────────────────────────

function PanelSkeleton() {
  return (
    <div className="animate-pulse space-y-4" aria-busy="true" aria-label="Carregando fidelidade...">
      <div className="h-48 rounded-2xl bg-bg-tertiary" aria-hidden="true" />
      <div className="h-32 rounded-2xl bg-bg-tertiary" aria-hidden="true" />
    </div>
  )
}

// ─── Toggle ───────────────────────────────────────────────────────────────────

function Toggle({ id, checked, onChange, disabled }: {
  id: string; checked: boolean; onChange: (v: boolean) => void; disabled?: boolean
}) {
  return (
    <button
      id={id}
      role="switch"
      aria-checked={checked}
      disabled={disabled}
      onClick={() => onChange(!checked)}
      className={[
        'relative inline-flex h-11 w-12 shrink-0 cursor-pointer items-center rounded-full transition-colors',
        'focus:outline-none focus-visible:ring-2 focus-visible:ring-primary-700',
        checked ? 'bg-primary-700' : 'bg-gray-300',
        disabled ? 'opacity-50 cursor-not-allowed' : '',
      ].join(' ')}
    >
      <span
        aria-hidden="true"
        className={[
          'inline-block h-5 w-5 rounded-full bg-white shadow-card transition-transform duration-200',
          checked ? 'translate-x-6' : 'translate-x-1',
        ].join(' ')}
      />
    </button>
  )
}

// ─── PunchCard ────────────────────────────────────────────────────────────────

function PunchCard({ progress, threshold }: { progress: number; threshold: number }) {
  const dots = Math.max(threshold, 1)
  return (
    <div
      className="flex flex-wrap gap-2"
      role="img"
      aria-label={progress + ' de ' + dots + ' pontos para o proximo punch'}
    >
      {Array.from({ length: dots }).map((_, i) => (
        <span
          key={i}
          aria-hidden="true"
          className={[
            'w-8 h-8 rounded-full',
            i < progress ? 'bg-primary-700' : 'bg-gray-200 border-2 border-gray-300',
          ].join(' ')}
        />
      ))}
    </div>
  )
}

// ─── TransactionIcon ──────────────────────────────────────────────────────────

function TransactionIcon({ reason }: { reason: string }) {
  switch (reason) {
    case 'ORDER_PAID': return <CheckCircle className="h-4 w-4 text-success" aria-hidden="true" />
    case 'REWARD_REDEEMED': return <Gift className="h-4 w-4 text-orange-500" aria-hidden="true" />
    case 'MANUAL_ADJUST': return <SlidersHorizontal className="h-4 w-4 text-blue-500" aria-hidden="true" />
    default: return <Clock className="h-4 w-4 text-text-muted" aria-hidden="true" />
  }
}

function reasonLabel(reason: string): string {
  switch (reason) {
    case 'ORDER_PAID': return 'Pedido pago'
    case 'REWARD_REDEEMED': return 'Recompensa resgatada'
    case 'MANUAL_ADJUST': return 'Ajuste manual'
    case 'EXPIRY': return 'Expiracao'
    default: return reason
  }
}

// ─── AdjustModal ──────────────────────────────────────────────────────────────

interface AdjustModalProps {
  customerId: string
  onClose: () => void
  onDone: (updated: LoyaltyStatusResponse) => void
  showToast: (msg: string, type: 'success' | 'error') => void
}

function AdjustModal({ customerId, onClose, onDone, showToast }: AdjustModalProps) {
  const dialogRef = useRef<HTMLDivElement>(null)
  useModalA11y(dialogRef, onClose)
  const deltaId = useId()
  const descId = useId()
  const [delta, setDelta] = useState('')
  const [desc, setDesc] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    const d = parseInt(delta, 10)
    if (isNaN(d)) { setError('Informe um numero valido para o delta.'); return }
    if (!desc.trim()) { setError('A descricao e obrigatoria.'); return }
    setSaving(true); setError(null)
    try {
      const updated = await api.post<LoyaltyStatusResponse>(
        '/customers/' + customerId + '/loyalty/adjust',
        { delta: d, description: desc.trim() },
      )
      showToast('Pontos ajustados com sucesso.', 'success')
      onDone(updated)
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : 'Erro ao ajustar pontos.'
      setError(msg)
    } finally { setSaving(false) }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4" role="presentation">
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="adjust-title"
        className="w-full max-w-sm rounded-2xl bg-bg-primary p-6 shadow-dropdown"
      >
        <h2 id="adjust-title" className="mb-4 text-lg font-semibold text-text-primary">
          Ajuste manual de pontos
        </h2>
        <form onSubmit={(e) => { void handleSubmit(e) }} noValidate>
          <div className="mb-4">
            <label htmlFor={deltaId} className="mb-1 block text-sm font-medium text-text-secondary">
              Delta (positivo ou negativo)
            </label>
            <input id={deltaId} type="number" value={delta}
              onChange={e => setDelta(e.target.value)}
              className="input-field w-full" placeholder="Ex: 10 ou -5" required />
          </div>
          <div className="mb-4">
            <label htmlFor={descId} className="mb-1 block text-sm font-medium text-text-secondary">
              Descricao
            </label>
            <input id={descId} type="text" value={desc}
              onChange={e => setDesc(e.target.value)}
              className="input-field w-full" placeholder="Motivo do ajuste"
              maxLength={200} required />
          </div>
          {error && (
            <p role="alert" className="mb-3 flex items-center gap-1 text-sm text-error">
              <AlertTriangle className="h-4 w-4 shrink-0" aria-hidden="true" />
              {error}
            </p>
          )}
          <div className="flex gap-3">
            <button type="button" onClick={onClose} disabled={saving}
              className="btn-outline flex-1 min-h-[48px]">
              Cancelar
            </button>
            <button type="submit" disabled={saving}
              className="btn-primary flex-1 min-h-[48px] flex items-center justify-center gap-2">
              {saving && <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" aria-hidden="true" />}
              Salvar
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

// ─── CustomerPanel ────────────────────────────────────────────────────────────

interface CustomerPanelProps {
  customerId: string
  showToast: (msg: string, type: 'success' | 'error') => void
}

type PanelState =
  | { phase: 'loading' }
  | { phase: 'error'; message: string }
  | { phase: 'ok'; data: LoyaltyStatusResponse }

function CustomerPanel({ customerId, showToast }: CustomerPanelProps) {
  const [state, setState] = useState<PanelState>({ phase: 'loading' })
  const [redeeming, setRedeeming] = useState(false)
  const [showAdjust, setShowAdjust] = useState(false)

  const load = useCallback(async () => {
    setState({ phase: 'loading' })
    try {
      const data = await api.get<LoyaltyStatusResponse>('/customers/' + customerId + '/loyalty')
      setState({ phase: 'ok', data })
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : 'Erro ao carregar dados de fidelidade.'
      setState({ phase: 'error', message: msg })
    }
  }, [customerId])

  useEffect(() => { void load() }, [load])

  const handleRedeem = async () => {
    if (state.phase !== 'ok') return
    const { data } = state
    if (!data.pendingRewardId) { showToast('Nenhuma recompensa disponivel.', 'error'); return }
    setRedeeming(true)
    try {
      await api.post('/customers/' + customerId + '/loyalty/redeem/' + data.pendingRewardId, {})
      showToast('Recompensa resgatada com sucesso!', 'success')
      void load()
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : 'Erro ao resgatar recompensa.'
      showToast(msg, 'error')
    } finally { setRedeeming(false) }
  }

  if (state.phase === 'loading') return <PanelSkeleton />
  if (state.phase === 'error') {
    return (
      <div className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-8 text-center shadow-card">
        <XCircle className="h-10 w-10 text-error" aria-hidden="true" />
        <p role="alert" className="text-sm text-error">{state.message}</p>
        <button className="btn-primary" onClick={() => void load()}>Tentar novamente</button>
      </div>
    )
  }

  const { data } = state
  const dots = Math.max(data.rewardThreshold, 1)
  const clampedProgress = Math.min(data.progress, dots)

  return (
    <>
      <div className="rounded-2xl bg-bg-primary p-6 shadow-card space-y-4">
        <div className="flex items-start justify-between gap-4">
          <div>
            <p className="text-sm font-semibold uppercase tracking-wider text-text-muted">Saldo</p>
            <p className="text-3xl font-bold text-text-primary">{data.loyaltyPoints}</p>
            <p className="text-sm text-text-secondary">pontos acumulados</p>
          </div>
          <div className="text-right">
            {data.punches > 0 ? (
              <span className="inline-flex items-center gap-1 rounded-full bg-primary-700 px-3 py-1 text-sm font-semibold text-white">
                <Gift className="h-4 w-4" aria-hidden="true" />
                {data.punches} recompensa{data.punches > 1 ? 's' : ''} disponivel{data.punches > 1 ? 'is' : ''}
              </span>
            ) : (
              <span className="inline-flex items-center gap-1 rounded-full bg-bg-tertiary px-3 py-1 text-sm text-text-muted">
                <Star className="h-4 w-4" aria-hidden="true" />
                {data.progress}/{data.rewardThreshold} pontos
              </span>
            )}
          </div>
        </div>
        <div>
          <p className="mb-2 text-sm text-text-muted">
            Progresso para o proximo punch ({clampedProgress}/{dots})
          </p>
          <PunchCard progress={clampedProgress} threshold={dots} />
        </div>
        <div className="flex gap-3 pt-2">
          <button
            onClick={() => void handleRedeem()}
            disabled={data.punches === 0 || redeeming}
            className="btn-primary flex min-h-[48px] flex-1 items-center justify-center gap-2 disabled:opacity-50"
          >
            {redeeming && <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" aria-hidden="true" />}
            <Gift className="h-4 w-4" aria-hidden="true" />
            Resgatar recompensa
          </button>
          <button onClick={() => setShowAdjust(true)}
            className="btn-outline min-h-[48px] flex items-center gap-2 px-4"
            title="Ajuste manual de pontos">
            <SlidersHorizontal className="h-4 w-4" aria-hidden="true" />
            Ajustar
          </button>
          <button onClick={() => void load()}
            className="btn-outline min-h-[48px] flex items-center gap-2 px-4"
            aria-label="Atualizar dados de fidelidade">
            <RefreshCw className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
      </div>

      {data.transactions.length > 0 ? (
        <div className="rounded-2xl bg-bg-primary p-6 shadow-card">
          <h3 className="mb-3 text-sm font-semibold uppercase tracking-wider text-text-secondary">
            Ultimas transacoes
          </h3>
          <ul role="list" className="divide-y divide-border-light">
            {data.transactions.map(tx => (
              <li key={tx.id} className="flex items-center gap-3 py-3">
                <TransactionIcon reason={tx.reason} />
                <div className="flex-1 min-w-0">
                  <p className="truncate text-sm font-medium text-text-primary">
                    {tx.description ?? reasonLabel(tx.reason)}
                  </p>
                  <p className="text-sm text-text-muted">{timeAgo(tx.createdAt)}</p>
                </div>
                <span className={[
                  'text-sm font-semibold tabular-nums',
                  tx.pointsDelta > 0 ? 'text-success' : 'text-error',
                ].join(' ')}>
                  {signedDelta(tx.pointsDelta)} pts
                </span>
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <div className="rounded-2xl bg-bg-primary p-6 shadow-card text-center">
          <p className="text-sm text-text-muted">Nenhuma transacao registrada ainda.</p>
        </div>
      )}

      {showAdjust && (
        <AdjustModal
          customerId={customerId}
          onClose={() => setShowAdjust(false)}
          onDone={updated => {
            setState({ phase: 'ok', data: updated })
            setShowAdjust(false)
          }}
          showToast={showToast}
        />
      )}
    </>
  )
}

// ─── ConfigCard ───────────────────────────────────────────────────────────────

type ConfigState =
  | { phase: 'loading' }
  | { phase: 'error'; message: string }
  | { phase: 'ok'; config: LoyaltyConfig; saving: boolean }

function ConfigCard() {
  const toggleId = useId()
  const ptsId = useId()
  const threshId = useId()
  const descId = useId()
  const [state, setState] = useState<ConfigState>({ phase: 'loading' })
  const { toasts, show: showToast } = useToast()

  const load = useCallback(async () => {
    setState({ phase: 'loading' })
    try {
      const data = await api.get<Record<string, unknown>>('/config')
      setState({
        phase: 'ok', saving: false,
        config: {
          loyaltyEnabled: Boolean(data.loyaltyEnabled),
          loyaltyPointsPerReal: Number(data.loyaltyPointsPerReal ?? 1),
          loyaltyRewardThreshold: Number(data.loyaltyRewardThreshold ?? 100),
          loyaltyRewardDescription: typeof data.loyaltyRewardDescription === 'string'
            ? data.loyaltyRewardDescription : '',
        },
      })
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : 'Erro ao carregar configuracoes.'
      setState({ phase: 'error', message: msg })
    }
  }, [])

  useEffect(() => { void load() }, [load])

  const patch = (field: keyof LoyaltyConfig, value: unknown) => {
    if (state.phase !== 'ok') return
    setState({ ...state, config: { ...state.config, [field]: value } })
  }

  const handleSave = async () => {
    if (state.phase !== 'ok') return
    setState({ ...state, saving: true })
    try {
      const current = await api.get<Record<string, unknown>>('/config')
      await api.patch('/config', {
        ...current,
        loyaltyEnabled: state.config.loyaltyEnabled,
        loyaltyPointsPerReal: state.config.loyaltyPointsPerReal,
        loyaltyRewardThreshold: state.config.loyaltyRewardThreshold,
        loyaltyRewardDescription: state.config.loyaltyRewardDescription,
      })
      showToast('Configuracoes salvas.', 'success')
      setState({ ...state, saving: false })
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : 'Erro ao salvar.'
      showToast(msg, 'error')
      setState({ ...state, saving: false })
    }
  }

  if (state.phase === 'loading') {
    return (
      <div className="animate-pulse rounded-2xl bg-bg-primary p-6 shadow-card" aria-busy="true" aria-label="Carregando configuracoes...">
        <div className="mb-3 h-5 w-1/3 rounded bg-bg-tertiary" aria-hidden="true" />
        <div className="space-y-3">
          {Array.from({ length: 4 }).map((_, i) => (
            <div key={i} className="h-10 rounded bg-bg-tertiary" aria-hidden="true" />
          ))}
        </div>
      </div>
    )
  }
  if (state.phase === 'error') {
    return (
      <div className="flex flex-col items-center gap-3 rounded-2xl bg-bg-primary p-6 shadow-card text-center">
        <XCircle className="h-8 w-8 text-error" aria-hidden="true" />
        <p role="alert" className="text-sm text-error">{state.message}</p>
        <button className="btn-primary" onClick={() => void load()}>Tentar novamente</button>
      </div>
    )
  }

  const { config, saving } = state
  return (
    <>
      <ToastContainer toasts={toasts} />
      <div className="rounded-2xl bg-bg-primary p-6 shadow-card">
        <h2 className="mb-4 text-base font-semibold text-text-primary">Configuracao do programa</h2>
        <div className="space-y-4">
          <div className="flex items-center justify-between gap-4">
            <div>
              <label htmlFor={toggleId} className="cursor-pointer text-sm font-medium text-text-primary">
                Programa ativo
              </label>
              <p className="text-sm text-text-muted">Clientes acumulam pontos a cada pedido pago</p>
            </div>
            <Toggle id={toggleId} checked={config.loyaltyEnabled}
              onChange={v => patch('loyaltyEnabled', v)} disabled={saving} />
          </div>
          <div>
            <label htmlFor={ptsId} className="mb-1 block text-sm font-medium text-text-secondary">
              Pontos por R$ 1,00
            </label>
            <input id={ptsId} type="number" min={0} max={1000}
              value={config.loyaltyPointsPerReal}
              onChange={e => patch('loyaltyPointsPerReal', Math.max(0, parseInt(e.target.value) || 0))}
              className="input-field w-32" disabled={saving} />
          </div>
          <div>
            <label htmlFor={threshId} className="mb-1 block text-sm font-medium text-text-secondary">
              Pontos para 1 recompensa
            </label>
            <input id={threshId} type="number" min={1} max={100000}
              value={config.loyaltyRewardThreshold}
              onChange={e => patch('loyaltyRewardThreshold', Math.max(1, parseInt(e.target.value) || 1))}
              className="input-field w-32" disabled={saving} />
          </div>
          <div>
            <label htmlFor={descId} className="mb-1 block text-sm font-medium text-text-secondary">
              Descricao da recompensa
            </label>
            <input id={descId} type="text" maxLength={200}
              value={config.loyaltyRewardDescription ?? ''}
              onChange={e => patch('loyaltyRewardDescription', e.target.value)}
              className="input-field w-full" placeholder="Ex: Lanche gratis!" disabled={saving} />
          </div>
          <button onClick={() => void handleSave()} disabled={saving}
            className="btn-primary flex items-center gap-2 disabled:opacity-50">
            {saving && <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" aria-hidden="true" />}
            Salvar configuracoes
          </button>
        </div>
      </div>
    </>
  )
}

// ─── CustomerSearch ───────────────────────────────────────────────────────────

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function CustomerSearch({ onSelect }: { onSelect: (id: string) => void }) {
  const inputId = useId()
  const [value, setValue] = useState('')
  const [error, setError] = useState<string | null>(null)

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    const trimmed = value.trim()
    if (!trimmed) { setError('Informe o ID do cliente.'); return }
    if (!UUID_RE.test(trimmed)) {
      setError('Formato invalido. Informe um UUID (ex: 550e8400-e29b-41d4-a716-446655440000).')
      return
    }
    setError(null)
    onSelect(trimmed)
  }

  return (
    <div className="rounded-2xl bg-bg-primary p-6 shadow-card">
      <h2 className="mb-1 text-base font-semibold text-text-primary">Consultar cliente</h2>
      <p className="mb-4 text-sm text-text-muted">
        Cole o ID do cliente (UUID) para consultar o saldo e historico de fidelidade.
      </p>
      <form onSubmit={handleSubmit} className="flex items-start gap-3">
        <div className="flex-1">
          <label htmlFor={inputId} className="sr-only">ID do cliente</label>
          <input
            id={inputId}
            type="text"
            value={value}
            onChange={e => { setValue(e.target.value); setError(null) }}
            className="input-field w-full font-mono text-sm"
            placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
            autoComplete="off"
            spellCheck={false}
          />
          {error && (
            <p role="alert" className="mt-1 flex items-center gap-1 text-xs text-error">
              <AlertTriangle className="h-3 w-3 shrink-0" aria-hidden="true" />
              {error}
            </p>
          )}
        </div>
        <button type="submit" className="btn-primary min-h-[48px] flex items-center gap-2">
          <Search className="h-4 w-4" aria-hidden="true" />
          Buscar
        </button>
      </form>
    </div>
  )
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function FidelidadePage() {
  const [customerId, setCustomerId] = useState<string | null>(null)
  const { toasts, show: showToast } = useToast()

  return (
    <div className="mx-auto max-w-2xl space-y-6 p-6">
      <ToastContainer toasts={toasts} />
      <div>
        <h1 className="flex items-center gap-2 text-xl font-bold text-text-primary">
          <Star className="h-6 w-6 text-primary-700" aria-hidden="true" />
          Fidelidade
        </h1>
        <p className="mt-1 text-sm text-text-muted">
          Programa de pontos punch-card para clientes cadastrados.
        </p>
      </div>
      <ConfigCard />
      <CustomerSearch onSelect={id => setCustomerId(id)} />
      {customerId && (
        <section aria-label="Dados de fidelidade do cliente">
          <p className="mb-3 font-mono text-sm text-text-muted">Cliente: {customerId}</p>
          <div className="space-y-4">
            <CustomerPanel key={customerId} customerId={customerId} showToast={showToast} />
          </div>
        </section>
      )}
    </div>
  )
}
