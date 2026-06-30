'use client'

import { useCallback, useEffect, useId, useState } from 'react'
import { Bot, Check, PhoneCall } from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import type { BotConfig, BotHandoffPage, BotHandoffResponse } from '@/types/bot'

// ── Helpers ───────────────────────────────────────────────────────────────────

function timeAgo(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(diff / 60_000)
  if (mins < 1) return 'Agora'
  if (mins < 60) return `Ha ${mins} min`
  const hrs = Math.floor(mins / 60)
  if (hrs < 24) return `Ha ${hrs} h`
  return `Ha ${Math.floor(hrs / 24)} d`
}

function parseHours(val: string | null): { enabled: boolean; open: string; close: string } {
  if (!val) return { enabled: false, open: '08:00', close: '22:00' }
  const [open, close] = val.split('-')
  return { enabled: true, open: open ?? '08:00', close: close ?? '22:00' }
}

function buildHours(state: { enabled: boolean; open: string; close: string }): string | null {
  if (!state.enabled) return null
  return `${state.open}-${state.close}`
}

// ── Tipos internos ─────────────────────────────────────────────────────────────

type LoadState = 'loading' | 'error' | 'empty' | 'ok'
type HandoffFilter = 'pending' | 'resolved'

const DAY_KEYS: Array<keyof BotConfig> = [
  'openingHoursMonday',
  'openingHoursTuesday',
  'openingHoursWednesday',
  'openingHoursThursday',
  'openingHoursFriday',
  'openingHoursSaturday',
  'openingHoursSunday',
]

const DAY_LABELS: Record<string, string> = {
  openingHoursMonday:    'Segunda',
  openingHoursTuesday:   'Terca',
  openingHoursWednesday: 'Quarta',
  openingHoursThursday:  'Quinta',
  openingHoursFriday:    'Sexta',
  openingHoursSaturday:  'Sabado',
  openingHoursSunday:    'Domingo',
}

interface DayState {
  enabled: boolean
  open: string
  close: string
}

// ── Toggle padrao ──────────────────────────────────────────────────────────────

interface ToggleProps {
  id: string
  checked: boolean
  onChange: (val: boolean) => void
  label: string
}

function Toggle({ id, checked, onChange, label }: ToggleProps) {
  return (
    <div className="flex items-center justify-between gap-3">
      <label
        htmlFor={id}
        className="cursor-pointer select-none text-sm font-medium text-text-primary"
      >
        {label}
      </label>
      <button
        id={id}
        type="button"
        role="switch"
        aria-checked={checked}
        onClick={() => onChange(!checked)}
        className={[
          'relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors',
          'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-700',
          checked ? 'bg-primary-700' : 'bg-bg-tertiary',
        ].join(' ')}
      >
        <span
          className={[
            'inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform',
            checked ? 'translate-x-6' : 'translate-x-1',
          ].join(' ')}
          aria-hidden="true"
        />
      </button>
    </div>
  )
}

// ── Skeleton de handoffs ───────────────────────────────────────────────────────

function HandoffSkeleton() {
  return (
    <div
      className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3"
      aria-busy="true"
      aria-label="Carregando handoffs..."
    >
      {Array.from({ length: 3 }).map((_, i) => (
        <div
          key={i}
          className="animate-pulse rounded-2xl bg-bg-primary shadow-card p-4"
          aria-hidden="true"
        >
          <div className="mb-2 h-4 w-32 rounded bg-bg-tertiary" />
          <div className="mb-3 h-3 w-20 rounded bg-bg-tertiary" />
          <div className="h-8 w-full rounded bg-bg-tertiary" />
        </div>
      ))}
    </div>
  )
}

// ── Card de handoff ────────────────────────────────────────────────────────────

interface HandoffCardProps {
  handoff: BotHandoffResponse
  resolving: boolean
  onResolve: (id: string) => void
}

function HandoffCard({ handoff, resolving, onResolve }: HandoffCardProps) {
  return (
    <div className="flex flex-col gap-3 rounded-2xl bg-bg-primary shadow-card p-4">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="font-mono text-sm font-semibold text-text-primary">{handoff.customerPhone}</p>
          <p className="mt-0.5 text-xs text-text-muted">{timeAgo(handoff.createdAt)}</p>
        </div>
        {handoff.resolved ? (
          <span className="inline-flex shrink-0 items-center gap-1 rounded-full bg-green-100 px-2.5 py-0.5 text-xs font-medium text-green-700">
            <Check className="h-3 w-3" aria-hidden="true" />
            Resolvido
          </span>
        ) : (
          <button
            onClick={() => onResolve(handoff.id)}
            disabled={resolving}
            aria-label={`Resolver handoff de ${handoff.customerPhone}`}
            className="btn-primary shrink-0 px-3 py-1.5 text-xs disabled:opacity-50"
          >
            {resolving ? (
              <span
                className="inline-block h-3 w-3 animate-spin rounded-full border border-white border-t-transparent"
                aria-hidden="true"
              />
            ) : (
              'Resolver'
            )}
          </button>
        )}
      </div>
      {handoff.lastBotMessage && (
        <p className="line-clamp-3 text-sm text-text-secondary leading-relaxed">
          &ldquo;{handoff.lastBotMessage}&rdquo;
        </p>
      )}
    </div>
  )
}

// ── Secao de handoffs ──────────────────────────────────────────────────────────

function HandoffsSection({ pendingCount, onPendingCountChange }: {
  pendingCount: number | null
  onPendingCountChange: (n: number) => void
}) {
  const [filter, setFilter]       = useState<HandoffFilter>('pending')
  const [handoffs, setHandoffs]   = useState<BotHandoffResponse[]>([])
  const [loadState, setLoadState] = useState<LoadState>('loading')
  const [page, setPage]           = useState(0)
  const [totalPages, setTotalPages] = useState(1)
  const [resolving, setResolving] = useState<Set<string>>(new Set())

  const load = useCallback(async (p: number, resolved: boolean) => {
    setLoadState('loading')
    try {
      const data = await api.get<BotHandoffPage>(
        `/bot/handoffs?resolved=${resolved.toString()}&page=${p}&size=20`,
      )
      setHandoffs(data.content)
      setTotalPages(data.totalPages)
      setPage(data.number)
      setLoadState(data.totalElements === 0 ? 'empty' : 'ok')
    } catch {
      setLoadState('error')
    }
  }, [])

  const refreshPendingCount = useCallback(async () => {
    try {
      const data = await api.get<BotHandoffPage>('/bot/handoffs?resolved=false&page=0&size=1')
      onPendingCountChange(data.totalElements)
    } catch {
      // nao-fatal
    }
  }, [onPendingCountChange])

  useEffect(() => {
    void load(0, filter === 'resolved')
    void refreshPendingCount()
  }, [filter, load, refreshPendingCount])

  // auto-refresh 30s apenas na aba Pendentes
  useEffect(() => {
    if (filter !== 'pending') return
    const id = setInterval(() => {
      void load(page, false)
      void refreshPendingCount()
    }, 30_000)
    return () => clearInterval(id)
  }, [filter, page, load, refreshPendingCount])

  async function handleResolve(id: string) {
    setResolving((prev) => new Set(prev).add(id))
    try {
      await api.post<void>(`/bot/handoffs/${id}/resolve`, {})
      setHandoffs((prev) => prev.filter((h) => h.id !== id))
      onPendingCountChange(Math.max(0, (pendingCount ?? 1) - 1))
      if (handoffs.length === 1) setLoadState('empty')
    } catch {
      // silencioso — nao bloquear UX
    } finally {
      setResolving((prev) => {
        const next = new Set(prev)
        next.delete(id)
        return next
      })
    }
  }

  return (
    <section aria-labelledby="handoffs-title">
      {/* Cabecalho da secao */}
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <h3 id="handoffs-title" className="flex items-center gap-2 text-base font-semibold text-text-primary">
          Handoffs
          {pendingCount !== null && pendingCount > 0 && (
            <span className="inline-flex items-center rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-medium text-red-700">
              {pendingCount} pendente{pendingCount !== 1 ? 's' : ''}
            </span>
          )}
        </h3>

        {/* Pill toggle Pendentes / Resolvidos */}
        <div className="flex gap-2">
          {(['pending', 'resolved'] as const).map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              aria-pressed={filter === f}
              className={[
                'rounded-full px-4 py-1.5 text-sm font-medium transition-colors',
                filter === f
                  ? 'bg-primary-700 text-white'
                  : 'border border-border-light bg-bg-primary text-text-secondary hover:bg-bg-tertiary hover:text-text-primary',
              ].join(' ')}
            >
              {f === 'pending' ? 'Pendentes' : 'Resolvidos'}
            </button>
          ))}
        </div>
      </div>

      {/* Estado: carregando */}
      {loadState === 'loading' && <HandoffSkeleton />}

      {/* Estado: erro */}
      {loadState === 'error' && (
        <div
          role="alert"
          className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-12 text-center shadow-card"
        >
          <p className="text-base font-medium text-text-primary">
            Nao foi possivel carregar os handoffs.
          </p>
          <button
            className="btn-primary"
            onClick={() => void load(page, filter === 'resolved')}
          >
            Tentar novamente
          </button>
        </div>
      )}

      {/* Estado: vazio */}
      {loadState === 'empty' && (
        <div className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-16 text-center shadow-card">
          <PhoneCall className="h-12 w-12 text-text-muted" aria-hidden="true" />
          <p className="text-base font-medium text-text-primary">
            {filter === 'pending'
              ? 'Nenhum cliente aguardando atendimento'
              : 'Nenhum handoff resolvido'}
          </p>
          <p className="text-sm text-text-muted">
            {filter === 'pending'
              ? 'Quando um cliente solicitar atendente, aparecera aqui.'
              : 'Os handoffs resolvidos aparecerao aqui.'}
          </p>
        </div>
      )}

      {/* Estado: cards */}
      {loadState === 'ok' && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {handoffs.map((h) => (
              <HandoffCard
                key={h.id}
                handoff={h}
                resolving={resolving.has(h.id)}
                onResolve={(id) => void handleResolve(id)}
              />
            ))}
          </div>

          {/* Paginacao */}
          {totalPages > 1 && (
            <div className="mt-4 flex items-center justify-between">
              <button
                disabled={page === 0}
                onClick={() => void load(page - 1, filter === 'resolved')}
                className="btn-outline text-sm disabled:opacity-40"
              >
                Anterior
              </button>
              <span className="text-sm text-text-muted">
                Pagina {page + 1} de {totalPages}
              </span>
              <button
                disabled={page >= totalPages - 1}
                onClick={() => void load(page + 1, filter === 'resolved')}
                className="btn-outline text-sm disabled:opacity-40"
              >
                Proxima
              </button>
            </div>
          )}
        </>
      )}
    </section>
  )
}

// ── Secao de configuracao ──────────────────────────────────────────────────────

const DEFAULT_CONFIG: BotConfig = {
  botEnabled: false,
  botSystemPrompt: null,
  botHandoffKeyword: 'atendente',
  botWelcomeMessage: null,
  botHandoffMessage: null,
  openingHoursMonday:    null,
  openingHoursTuesday:   null,
  openingHoursWednesday: null,
  openingHoursThursday:  null,
  openingHoursFriday:    null,
  openingHoursSaturday:  null,
  openingHoursSunday:    null,
}

function ConfigSection() {
  const enabledId    = useId()
  const keywordId    = useId()
  const welcomeId    = useId()
  const handoffMsgId = useId()
  const promptId     = useId()

  const [draft,   setDraft]   = useState<BotConfig>(DEFAULT_CONFIG)
  const [days,    setDays]    = useState<Record<string, DayState>>({})
  const [saving,  setSaving]  = useState(false)
  const [error,   setError]   = useState<string | null>(null)
  const [success, setSuccess] = useState(false)

  useEffect(() => {
    void (async () => {
      try {
        const data = await api.get<Partial<BotConfig>>('/config')
        const merged: BotConfig = { ...DEFAULT_CONFIG, ...data }
        setDraft(merged)
        // inicializar estado dos dias
        const d: Record<string, DayState> = {}
        for (const key of DAY_KEYS) {
          d[key as string] = parseHours(merged[key] as string | null)
        }
        setDays(d)
      } catch {
        // defaults permanecem
        const d: Record<string, DayState> = {}
        for (const key of DAY_KEYS) d[key as string] = { enabled: false, open: '08:00', close: '22:00' }
        setDays(d)
      }
    })()
  }, [])

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    setSaving(true)
    setError(null)
    setSuccess(false)
    try {
      // montar payload com os horarios dos dias
      const payload: BotConfig = { ...draft }
      for (const key of DAY_KEYS) {
        const state = days[key as string]
        if (state) {
          ;(payload as unknown as Record<string, string | null | boolean>)[key as string] = buildHours(state)
        }
      }
      await api.patch<BotConfig>('/config', payload)
      setSuccess(true)
      setTimeout(() => setSuccess(false), 3000)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao salvar configuracoes.')
    } finally {
      setSaving(false)
    }
  }

  function setDay(key: string, patch: Partial<DayState>) {
    setDays((prev) => ({ ...prev, [key]: { ...prev[key]!, ...patch } }))
  }

  return (
    <section aria-labelledby="config-title">
      <div className="mb-4">
        <h3 id="config-title" className="text-base font-semibold text-text-primary">
          Configuracao do Bot
        </h3>
      </div>

      <div className="rounded-2xl bg-bg-primary shadow-card p-5 lg:p-6">
        <form onSubmit={(e) => void handleSave(e)} className="space-y-6">
          {/* Toggle ativo */}
          <Toggle
            id={enabledId}
            checked={draft.botEnabled}
            onChange={(val) => setDraft((d) => ({ ...d, botEnabled: val }))}
            label="Ativar Bot WhatsApp"
          />

          {/* Palavra-chave de handoff */}
          <div>
            <label htmlFor={keywordId} className="mb-1 block text-sm font-medium text-text-primary">
              Palavra-chave de handoff
            </label>
            <input
              id={keywordId}
              type="text"
              value={draft.botHandoffKeyword}
              onChange={(e) => setDraft((d) => ({ ...d, botHandoffKeyword: e.target.value }))}
              className="input-field w-full sm:max-w-xs"
              placeholder="atendente"
            />
            <p className="mt-1 text-xs text-text-muted">
              Cliente digita esta palavra para falar com um humano
            </p>
          </div>

          {/* Mensagem de boas-vindas */}
          <div>
            <label htmlFor={welcomeId} className="mb-1 block text-sm font-medium text-text-primary">
              Mensagem de boas-vindas
            </label>
            <textarea
              id={welcomeId}
              rows={3}
              value={draft.botWelcomeMessage ?? ''}
              onChange={(e) =>
                setDraft((d) => ({ ...d, botWelcomeMessage: e.target.value || null }))
              }
              className="input-field w-full resize-none"
              placeholder="Ola! Como posso ajudar voce hoje?"
            />
          </div>

          {/* Mensagem de handoff */}
          <div>
            <label htmlFor={handoffMsgId} className="mb-1 block text-sm font-medium text-text-primary">
              Mensagem de handoff
            </label>
            <textarea
              id={handoffMsgId}
              rows={3}
              value={draft.botHandoffMessage ?? ''}
              onChange={(e) =>
                setDraft((d) => ({ ...d, botHandoffMessage: e.target.value || null }))
              }
              className="input-field w-full resize-none"
              placeholder="Aguarde, um atendente entrara em contato em breve."
            />
            <p className="mt-1 text-xs text-text-muted">
              Enviada automaticamente quando o cliente solicita atendimento humano
            </p>
          </div>

          {/* Instrucao personalizada */}
          <div>
            <label htmlFor={promptId} className="mb-1 block text-sm font-medium text-text-primary">
              Instrucao personalizada (system prompt)
            </label>
            <textarea
              id={promptId}
              rows={4}
              value={draft.botSystemPrompt ?? ''}
              onChange={(e) =>
                setDraft((d) => ({ ...d, botSystemPrompt: e.target.value || null }))
              }
              className="input-field w-full resize-none font-mono text-xs"
              placeholder="Voce e um assistente de atendimento do restaurante. Responda sempre em portugues..."
            />
          </div>

          {/* Grid de horarios */}
          <div>
            <p className="mb-3 text-sm font-medium text-text-primary">Horario de funcionamento</p>
            <div className="space-y-3 sm:hidden">
              {DAY_KEYS.map((key) => {
                const k = key as string
                const day = days[k] ?? { enabled: false, open: '08:00', close: '22:00' }
                return (
                  <div key={k} className="rounded-lg border border-border-light bg-bg-secondary p-3">
                    <div className="mb-3 flex items-center justify-between gap-3">
                      <span className="text-sm font-medium text-text-primary">
                        {DAY_LABELS[k] ?? k}
                      </span>
                      <button
                        type="button"
                        role="switch"
                        aria-checked={day.enabled}
                        aria-label={`${DAY_LABELS[k] ?? k} aberto`}
                        onClick={() => setDay(k, { enabled: !day.enabled })}
                        className={[
                          'relative inline-flex h-6 w-11 shrink-0 items-center rounded-full transition-colors',
                          'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-700',
                          day.enabled ? 'bg-primary-700' : 'bg-bg-tertiary',
                        ].join(' ')}
                      >
                        <span
                          className={[
                            'inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform',
                            day.enabled ? 'translate-x-6' : 'translate-x-1',
                          ].join(' ')}
                          aria-hidden="true"
                        />
                      </button>
                    </div>
                    <div className="grid grid-cols-2 gap-2">
                      <label className="grid gap-1 text-xs font-medium text-text-muted">
                        Abertura
                        <input
                          type="time"
                          value={day.open}
                          disabled={!day.enabled}
                          onChange={(e) => setDay(k, { open: e.target.value })}
                          aria-label={`Abertura ${DAY_LABELS[k] ?? k}`}
                          className="input-field disabled:opacity-40"
                        />
                      </label>
                      <label className="grid gap-1 text-xs font-medium text-text-muted">
                        Fechamento
                        <input
                          type="time"
                          value={day.close}
                          disabled={!day.enabled}
                          onChange={(e) => setDay(k, { close: e.target.value })}
                          aria-label={`Fechamento ${DAY_LABELS[k] ?? k}`}
                          className="input-field disabled:opacity-40"
                        />
                      </label>
                    </div>
                  </div>
                )
              })}
            </div>
            <div className="hidden overflow-x-auto sm:block">
              <table className="w-full min-w-[400px] text-sm">
                <thead>
                  <tr className="border-b border-border-light">
                    <th className="py-2 pr-4 text-left text-xs font-semibold uppercase tracking-wider text-text-muted">
                      Dia
                    </th>
                    <th className="py-2 pr-4 text-left text-xs font-semibold uppercase tracking-wider text-text-muted">
                      Aberto
                    </th>
                    <th className="py-2 pr-4 text-left text-xs font-semibold uppercase tracking-wider text-text-muted">
                      Abertura
                    </th>
                    <th className="py-2 text-left text-xs font-semibold uppercase tracking-wider text-text-muted">
                      Fechamento
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {DAY_KEYS.map((key) => {
                    const k = key as string
                    const day = days[k] ?? { enabled: false, open: '08:00', close: '22:00' }
                    return (
                      <tr key={k} className="border-b border-border-light last:border-0">
                        <td className="py-2.5 pr-4 font-medium text-text-primary">
                          {DAY_LABELS[k] ?? k}
                        </td>
                        <td className="py-2.5 pr-4">
                          <button
                            type="button"
                            role="switch"
                            aria-checked={day.enabled}
                            aria-label={`${DAY_LABELS[k] ?? k} aberto`}
                            onClick={() => setDay(k, { enabled: !day.enabled })}
                            className={[
                              'relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition-colors',
                              'focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary-700',
                              day.enabled ? 'bg-primary-700' : 'bg-bg-tertiary',
                            ].join(' ')}
                          >
                            <span
                              className={[
                                'inline-block h-3.5 w-3.5 transform rounded-full bg-white shadow transition-transform',
                                day.enabled ? 'translate-x-4' : 'translate-x-0.5',
                              ].join(' ')}
                              aria-hidden="true"
                            />
                          </button>
                        </td>
                        <td className="py-2.5 pr-4">
                          <input
                            type="time"
                            value={day.open}
                            disabled={!day.enabled}
                            onChange={(e) => setDay(k, { open: e.target.value })}
                            aria-label={`Abertura ${DAY_LABELS[k] ?? k}`}
                            className="input-field w-28 disabled:opacity-40"
                          />
                        </td>
                        <td className="py-2.5">
                          <input
                            type="time"
                            value={day.close}
                            disabled={!day.enabled}
                            onChange={(e) => setDay(k, { close: e.target.value })}
                            aria-label={`Fechamento ${DAY_LABELS[k] ?? k}`}
                            className="input-field w-28 disabled:opacity-40"
                          />
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          </div>

          {/* Feedback */}
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
            className="btn-primary flex items-center gap-2 disabled:opacity-50"
          >
            {saving && (
              <span
                className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent"
                aria-hidden="true"
              />
            )}
            {saving ? 'Salvando...' : 'Salvar configuracoes'}
          </button>
        </form>
      </div>
    </section>
  )
}

// ── Pagina principal ───────────────────────────────────────────────────────────

export default function BotPage() {
  const [pendingCount, setPendingCount] = useState<number | null>(null)

  return (
    <div className="flex min-h-screen flex-col bg-bg-secondary">
      <main className="mx-auto w-full max-w-7xl flex-1 px-4 py-8">
        {/* Cabecalho */}
        <div className="mb-8 flex items-center gap-3">
          <Bot className="h-6 w-6 text-primary-700" aria-hidden="true" />
          <h2 className="text-2xl font-bold text-text-primary">Bot WhatsApp</h2>
          {pendingCount !== null && pendingCount > 0 && (
            <span className="inline-flex items-center rounded-full bg-red-100 px-2.5 py-0.5 text-xs font-semibold text-red-700">
              {pendingCount} pendente{pendingCount !== 1 ? 's' : ''}
            </span>
          )}
        </div>

        <div className="space-y-10">
          <HandoffsSection
            pendingCount={pendingCount}
            onPendingCountChange={setPendingCount}
          />
          <ConfigSection />
        </div>
      </main>
    </div>
  )
}
