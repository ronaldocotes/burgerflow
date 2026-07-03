'use client'

import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
} from 'react'
import { Eye, Megaphone, Pause, Play, Plus } from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { useModalA11y } from '@/lib/use-modal-a11y'
import type {
  CampaignCreateRequest,
  CampaignPage,
  CampaignResponse,
  CampaignSegment,
  CampaignSendResponse,
} from '@/types/campaign'

// ── Helpers ───────────────────────────────────────────────────────────────────

const SEGMENT_LABELS: Record<string, string> = {
  RFV_INACTIVE: 'Inativos',
  RFV_AT_RISK: 'Em risco',
  RFV_LOYAL: 'Fieis',
  ALL_OPT_IN: 'Todos com opt-in',
  CUSTOM: 'Personalizado',
}

function fmtDate(iso: string) {
  return new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
}

function localToIso(local: string): string {
  if (!local) return ''
  return new Date(local).toISOString()
}

/** Agora no formato do input datetime-local (YYYY-MM-DDTHH:mm), em hora local. */
function nowLocalInput(): string {
  const d = new Date()
  d.setSeconds(0, 0)
  return new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}

// ── Badge de status da campanha ───────────────────────────────────────────────

function CampaignStatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    DRAFT: 'bg-bg-tertiary text-text-secondary',
    RUNNING: 'bg-blue-100 text-blue-700',
    PAUSED: 'bg-yellow-100 text-yellow-700',
    COMPLETED: 'bg-green-100 text-green-700',
    FAILED: 'bg-red-100 text-red-700',
  }
  const labels: Record<string, string> = {
    DRAFT: 'Rascunho',
    RUNNING: 'Executando',
    PAUSED: 'Pausado',
    COMPLETED: 'Concluido',
    FAILED: 'Falhou',
  }
  return (
    <span
      className={[
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-xs font-medium',
        map[status] ?? map.DRAFT,
      ].join(' ')}
    >
      {status === 'RUNNING' && (
        <span
          className="inline-block h-2 w-2 animate-spin rounded-full border border-blue-600 border-t-transparent"
          aria-hidden="true"
        />
      )}
      {labels[status] ?? status}
    </span>
  )
}

// ── Badge de status de envio ──────────────────────────────────────────────────

function SendStatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    QUEUED: 'bg-bg-tertiary text-text-secondary',
    SENT: 'bg-green-100 text-green-700',
    FAILED: 'bg-red-100 text-red-700',
    OPT_OUT: 'bg-orange-100 text-orange-700',
  }
  const labels: Record<string, string> = {
    QUEUED: 'Na fila',
    SENT: 'Enviado',
    FAILED: 'Falhou',
    OPT_OUT: 'Opt-out',
  }
  return (
    <span className={['inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium', map[status] ?? map.QUEUED].join(' ')}>
      {labels[status] ?? status}
    </span>
  )
}

// ── Skeleton ──────────────────────────────────────────────────────────────────

function TableSkeleton() {
  return (
    <div
      className="animate-pulse rounded-2xl bg-bg-primary shadow-card overflow-hidden"
      aria-busy="true"
      aria-label="Carregando campanhas..."
    >
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border-light">
            {['Nome', 'Segmento', 'Status', 'Envio', 'Destinatarios', 'Enviados', 'Falhas', 'Acoes'].map((h) => (
              <th
                key={h}
                className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-text-muted"
              >
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {Array.from({ length: 4 }).map((_, i) => (
            <tr key={i} className="border-b border-border-light">
              {Array.from({ length: 8 }).map((__, j) => (
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

// ── Modal criar campanha ──────────────────────────────────────────────────────

const SEGMENT_OPTIONS: { value: CampaignSegment; label: string }[] = [
  { value: 'RFV_INACTIVE', label: 'Inativos' },
  { value: 'RFV_AT_RISK', label: 'Em risco' },
  { value: 'RFV_LOYAL', label: 'Fieis' },
  { value: 'ALL_OPT_IN', label: 'Todos com opt-in' },
]

const TEMPLATE_VARS = ['{nome}', '{pontos}', '{dias}']

const PREVIEW_VALUES: Record<string, string> = {
  '{nome}': 'Joao Silva',
  '{pontos}': '80',
  '{dias}': '15',
}

function applyPreview(template: string): string {
  return template.replace(/\{(nome|pontos|dias)\}/g, (match) => PREVIEW_VALUES[match] ?? match)
}

interface CreateModalProps {
  initialSegment?: CampaignSegment
  onClose: () => void
  onCreated: () => void
}

function CreateCampaignModal({ initialSegment, onClose, onCreated }: CreateModalProps) {
  const ref = useRef<HTMLDivElement>(null)
  useModalA11y(ref as React.RefObject<HTMLElement | null>, onClose)
  const titleId = useId()
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  const [name, setName] = useState('')
  const [segment, setSegment] = useState<CampaignSegment>(initialSegment ?? 'RFV_LOYAL')
  const [template, setTemplate] = useState('')
  const [scheduledAt, setScheduledAt] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function insertVar(variable: string) {
    const ta = textareaRef.current
    if (!ta) return
    const start = ta.selectionStart ?? template.length
    const end = ta.selectionEnd ?? template.length
    const next = template.slice(0, start) + variable + template.slice(end)
    setTemplate(next)
    requestAnimationFrame(() => {
      ta.focus()
      ta.setSelectionRange(start + variable.length, start + variable.length)
    })
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (saving) return
    setError(null)
    const body: CampaignCreateRequest = {
      name: name.trim(),
      messageTemplate: template.trim(),
      segment,
      ...(scheduledAt ? { scheduledAt: localToIso(scheduledAt) } : {}),
    }
    setSaving(true)
    try {
      await api.post<CampaignResponse>('/campaigns', body)
      onCreated()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao criar campanha.')
    } finally {
      setSaving(false)
    }
  }

  const preview = applyPreview(template)

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
        className="relative z-10 w-full max-w-lg rounded-2xl bg-bg-primary shadow-dropdown overflow-y-auto max-h-[90vh]"
      >
        <div className="flex items-center justify-between border-b border-border-light px-6 py-4">
          <h2 id={titleId} className="text-base font-semibold text-text-primary">
            Nova campanha
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
          {/* Nome */}
          <div>
            <label htmlFor="camp-name" className="mb-1 block text-sm font-medium text-text-primary">
              Nome da campanha
            </label>
            <input
              id="camp-name"
              type="text"
              required
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="input-field w-full"
              placeholder="Ex: Reativar clientes inativos"
            />
          </div>

          {/* Segmento */}
          <div>
            <label htmlFor="camp-segment" className="mb-1 block text-sm font-medium text-text-primary">
              Segmento
            </label>
            <select
              id="camp-segment"
              value={segment}
              onChange={(e) => setSegment(e.target.value as CampaignSegment)}
              className="input-field w-full"
            >
              {SEGMENT_OPTIONS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </div>

          {/* Template */}
          <div>
            <label htmlFor="camp-template" className="mb-1 block text-sm font-medium text-text-primary">
              Mensagem
            </label>
            <textarea
              id="camp-template"
              ref={textareaRef}
              required
              value={template}
              onChange={(e) => setTemplate(e.target.value)}
              rows={4}
              className="input-field w-full resize-none"
              placeholder="Ola {nome}, voce tem {pontos} pontos e faz {dias} dias que nao nos visita..."
            />
            {/* Chips de variaveis */}
            <div className="mt-2 flex flex-wrap gap-2">
              {TEMPLATE_VARS.map((v) => (
                <button
                  key={v}
                  type="button"
                  onClick={() => insertVar(v)}
                  className="rounded-full border border-border-medium bg-bg-secondary px-3 py-0.5 text-xs font-mono text-text-secondary hover:bg-bg-tertiary hover:text-text-primary transition-colors"
                >
                  {v}
                </button>
              ))}
            </div>
            {/* Preview ao vivo */}
            {template && (
              <div className="mt-3 rounded-xl border border-border-light bg-bg-secondary p-3">
                <p className="mb-1 text-xs font-semibold uppercase tracking-wider text-text-muted">Preview</p>
                <p className="whitespace-pre-wrap text-sm text-text-primary">{preview}</p>
              </div>
            )}
          </div>

          {/* Agendamento */}
          <div>
            <label htmlFor="camp-scheduled" className="mb-1 block text-sm font-medium text-text-primary">
              Agendamento{' '}
              <span className="font-normal text-text-muted">(opcional)</span>
            </label>
            <input
              id="camp-scheduled"
              type="datetime-local"
              min={nowLocalInput()}
              value={scheduledAt}
              onChange={(e) => setScheduledAt(e.target.value)}
              className="input-field w-full"
            />
            <p className="mt-1.5 text-sm text-text-muted">
              O disparo automatico no horario agendado ainda nao esta ativo &mdash; campanhas
              agendadas precisam do botao Disparar.
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
              {saving ? 'Criando...' : 'Criar campanha'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

// ── Modal de envios ───────────────────────────────────────────────────────────

interface SendsModalProps {
  campaign: CampaignResponse
  onClose: () => void
}

function SendsModal({ campaign, onClose }: SendsModalProps) {
  const ref = useRef<HTMLDivElement>(null)
  useModalA11y(ref as React.RefObject<HTMLElement | null>, onClose)
  const titleId = useId()
  const [sends, setSends] = useState<CampaignSendResponse[]>([])
  const [loadState, setLoadState] = useState<'loading' | 'error' | 'ok'>('loading')
  const [page, setPage] = useState(0)
  const [totalPages, setTotalPages] = useState(1)

  const load = useCallback(
    async (p: number) => {
      setLoadState('loading')
      try {
        const data = await api.get<CampaignPage<CampaignSendResponse>>(
          `/campaigns/${campaign.id}/sends?page=${p}&size=20`,
        )
        setSends(data.content)
        setTotalPages(data.totalPages)
        setPage(data.number)
        setLoadState('ok')
      } catch {
        setLoadState('error')
      }
    },
    [campaign.id],
  )

  useEffect(() => {
    void load(0)
  }, [load])

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
        className="relative z-10 w-full max-w-2xl rounded-2xl bg-bg-primary shadow-dropdown flex flex-col max-h-[90vh]"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border-light px-6 py-4 shrink-0">
          <h2 id={titleId} className="text-base font-semibold text-text-primary">
            Envios &mdash; {campaign.name}
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

        {/* Metricas compactas */}
        <div className="grid grid-cols-3 gap-3 border-b border-border-light px-6 py-3 shrink-0">
          <div className="rounded-xl bg-green-50 px-3 py-2 text-center">
            <p className="text-lg font-bold text-green-700">{campaign.sentCount}</p>
            <p className="text-xs text-green-600">Enviados</p>
          </div>
          <div className="rounded-xl bg-red-50 px-3 py-2 text-center">
            <p className="text-lg font-bold text-red-700">{campaign.failedCount}</p>
            <p className="text-xs text-red-600">Falharam</p>
          </div>
          <div className="rounded-xl bg-bg-secondary px-3 py-2 text-center">
            <p className="text-lg font-bold text-text-primary">{campaign.totalRecipients}</p>
            <p className="text-xs text-text-muted">Total</p>
          </div>
        </div>

        {/* Conteudo */}
        <div className="flex-1 overflow-y-auto">
          {loadState === 'loading' && (
            <div
              className="flex items-center justify-center py-16"
              aria-busy="true"
              aria-label="Carregando envios..."
            >
              <span
                className="inline-block h-6 w-6 animate-spin rounded-full border-2 border-primary-700 border-t-transparent"
                aria-hidden="true"
              />
            </div>
          )}
          {loadState === 'error' && (
            <div role="alert" className="flex flex-col items-center gap-3 py-12 text-center px-6">
              <p className="text-sm text-text-secondary">Nao foi possivel carregar os envios.</p>
              <button className="btn-outline text-sm" onClick={() => void load(page)}>
                Tentar novamente
              </button>
            </div>
          )}
          {loadState === 'ok' && sends.length === 0 && (
            <div className="flex flex-col items-center gap-2 py-16 text-center">
              <p className="text-sm font-medium text-text-primary">Nenhum envio registrado</p>
              <p className="text-sm text-text-muted">
                Os envios aparecerao aqui quando a campanha for disparada.
              </p>
            </div>
          )}
          {loadState === 'ok' && sends.length > 0 && (
            <table className="w-full text-sm">
              <thead className="sticky top-0 bg-bg-primary">
                <tr className="border-b border-border-light">
                  {['Telefone', 'Status', 'Enviado em', 'Erro'].map((h) => (
                    <th
                      key={h}
                      className="px-4 py-3 text-left text-xs font-semibold uppercase tracking-wider text-text-muted"
                    >
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {sends.map((s) => (
                  <tr key={s.id} className="border-b border-border-light hover:bg-bg-secondary">
                    <td className="px-4 py-3 font-mono text-xs text-text-primary">{s.phone}</td>
                    <td className="px-4 py-3">
                      <SendStatusBadge status={s.status} />
                    </td>
                    <td className="px-4 py-3 text-text-secondary">
                      {s.sentAt ? fmtDate(s.sentAt) : '—'}
                    </td>
                    <td
                      className="px-4 py-3 text-xs text-red-600 max-w-[180px] truncate"
                      title={s.errorMessage ?? undefined}
                    >
                      {s.errorMessage ?? '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>

        {/* Paginacao */}
        {loadState === 'ok' && totalPages > 1 && (
          <div className="flex items-center justify-between border-t border-border-light px-6 py-3 shrink-0">
            <button
              disabled={page === 0}
              onClick={() => void load(page - 1)}
              className="btn-outline text-sm disabled:opacity-40"
            >
              Anterior
            </button>
            <span className="text-sm text-text-muted">
              Pagina {page + 1} de {totalPages}
            </span>
            <button
              disabled={page >= totalPages - 1}
              onClick={() => void load(page + 1)}
              className="btn-outline text-sm disabled:opacity-40"
            >
              Proxima
            </button>
          </div>
        )}
      </div>
    </div>
  )
}

// ── Modal de confirmacao de disparo ───────────────────────────────────────────

interface ConfirmStartModalProps {
  campaign: CampaignResponse
  onClose: () => void
  onConfirm: () => Promise<void>
}

function ConfirmStartModal({ campaign, onClose, onConfirm }: ConfirmStartModalProps) {
  const ref = useRef<HTMLDivElement>(null)
  useModalA11y(ref as React.RefObject<HTMLElement | null>, onClose)
  const titleId = useId()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handle() {
    setLoading(true)
    setError(null)
    try {
      await onConfirm()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao disparar campanha.')
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
          Disparar campanha?
        </h2>
        <p className="mb-5 text-sm text-text-secondary">
          Esta acao vai enviar mensagens para{' '}
          <span className="font-semibold text-text-primary">
            {campaign.totalRecipients} destinatarios
          </span>
          . Confirma o disparo de{' '}
          <span className="font-semibold">{campaign.name}</span>?
        </p>
        {campaign.totalRecipients === 0 && (
          <p className="mb-4 rounded-lg bg-yellow-50 px-3 py-2 text-sm text-yellow-800">
            Nenhum destinatario elegivel (com opt-in) neste segmento. Nada sera enviado.
          </p>
        )}
        {error && (
          <p role="alert" className="mb-4 rounded-lg bg-red-50 px-3 py-2 text-sm text-red-700">
            {error}
          </p>
        )}
        <div className="flex justify-end gap-3">
          <button onClick={onClose} className="btn-outline">
            Nao
          </button>
          <button
            onClick={() => void handle()}
            disabled={loading || campaign.totalRecipients === 0}
            className="btn-primary flex items-center gap-2 disabled:opacity-50"
          >
            {loading && (
              <span
                className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent"
                aria-hidden="true"
              />
            )}
            Sim, disparar
          </button>
        </div>
      </div>
    </div>
  )
}

// ── Pagina principal ──────────────────────────────────────────────────────────

export default function CampanhasPage() {
  const [campaigns, setCampaigns] = useState<CampaignResponse[]>([])
  const [loadState, setLoadState] = useState<'loading' | 'error' | 'ok' | 'empty'>('loading')
  const [createOpen, setCreateOpen] = useState(false)
  const [initialSegment, setInitialSegment] = useState<CampaignSegment | undefined>(undefined)
  const [sendsTarget, setSendsTarget] = useState<CampaignResponse | null>(null)
  const [startTarget, setStartTarget] = useState<CampaignResponse | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  const load = useCallback(async (silent = false) => {
    if (!silent) setLoadState('loading')
    try {
      const data = await api.get<CampaignPage<CampaignResponse>>('/campaigns?page=0&size=20')
      const list = data.content
      setCampaigns(list)
      setLoadState(list.length === 0 ? 'empty' : 'ok')
    } catch {
      if (!silent) setLoadState('error')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  // Deep-link do RFV: /admin/campanhas?segment=RFV_LOYAL abre o modal ja segmentado
  useEffect(() => {
    const seg = new URLSearchParams(window.location.search).get('segment')
    const valid: readonly string[] = ['RFV_LOYAL', 'RFV_AT_RISK', 'RFV_INACTIVE']
    if (seg && valid.includes(seg)) {
      setInitialSegment(seg as CampaignSegment)
      setCreateOpen(true)
    }
  }, [])

  // Enquanto houver campanha RUNNING, atualiza os contadores a cada 10s (silencioso).
  useEffect(() => {
    if (!campaigns.some((c) => c.status === 'RUNNING')) return
    const t = setInterval(() => void load(true), 10_000)
    return () => clearInterval(t)
  }, [campaigns, load])

  async function handleStart(campaign: CampaignResponse) {
    // O start apenas valida e delega ao dispatcher ASSINCRONO: o response ainda vem
    // DRAFT/PAUSED. Recarrega para capturar o RUNNING (o polling assume dali em diante).
    await api.post<CampaignResponse>(`/campaigns/${campaign.id}/start`, {})
    setStartTarget(null)
    setTimeout(() => void load(true), 1500)
  }

  async function handlePause(campaign: CampaignResponse) {
    setActionError(null)
    try {
      const updated = await api.post<CampaignResponse>(`/campaigns/${campaign.id}/pause`, {})
      setCampaigns((prev) => prev.map((c) => (c.id === updated.id ? updated : c)))
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : 'Erro ao pausar campanha.')
    }
  }

  return (
    <div className="flex min-h-screen flex-col bg-bg-secondary">
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8">
        {/* Cabecalho */}
        <div className="mb-6 flex items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <Megaphone className="h-6 w-6 text-primary-700" aria-hidden="true" />
            <h2 className="text-2xl font-bold text-text-primary">Campanhas WhatsApp</h2>
          </div>
          <button onClick={() => setCreateOpen(true)} className="btn-primary flex items-center gap-2">
            <Plus className="h-4 w-4" aria-hidden="true" />
            Nova campanha
          </button>
        </div>

        {actionError && (
          <div role="alert" className="mb-4 rounded-xl bg-red-50 px-4 py-3 text-sm text-red-700">
            {actionError}
          </div>
        )}

        {/* 1. Estado: carregando */}
        {loadState === 'loading' && <TableSkeleton />}

        {/* 2. Estado: erro */}
        {loadState === 'error' && (
          <div
            role="alert"
            className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-12 text-center shadow-card"
          >
            <p className="text-base font-medium text-text-primary">
              Nao foi possivel carregar as campanhas.
            </p>
            <button className="btn-primary" onClick={() => void load()}>
              Tentar novamente
            </button>
          </div>
        )}

        {/* 3. Estado: vazio */}
        {loadState === 'empty' && (
          <div className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-16 text-center shadow-card">
            <Megaphone className="h-12 w-12 text-text-muted" aria-hidden="true" />
            <p className="text-base font-medium text-text-primary">Nenhuma campanha criada ainda</p>
            <p className="text-sm text-text-muted">
              Crie sua primeira campanha e alcance seus clientes pelo WhatsApp.
            </p>
            <button onClick={() => setCreateOpen(true)} className="btn-primary flex items-center gap-2">
              <Plus className="h-4 w-4" aria-hidden="true" />
              Nova campanha
            </button>
          </div>
        )}

        {/* 4. Estado: ok */}
        {loadState === 'ok' && (
          <div className="rounded-2xl bg-bg-primary shadow-card overflow-hidden">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b border-border-light">
                    {['Nome', 'Segmento', 'Status', 'Envio', 'Destinatarios', 'Enviados', 'Falhas', 'Acoes'].map(
                      (h) => (
                        <th
                          key={h}
                          className={[
                            'px-4 py-3 text-xs font-semibold uppercase tracking-wider text-text-muted whitespace-nowrap',
                            ['Destinatarios', 'Enviados', 'Falhas'].includes(h) ? 'text-right' : 'text-left',
                          ].join(' ')}
                        >
                          {h}
                        </th>
                      ),
                    )}
                  </tr>
                </thead>
                <tbody>
                  {campaigns.map((c) => (
                    <tr key={c.id} className="border-b border-border-light hover:bg-bg-secondary">
                      <td
                        className="px-4 py-3 font-medium text-text-primary max-w-[200px] truncate"
                        title={c.name}
                      >
                        {c.name}
                      </td>
                      <td className="px-4 py-3 text-text-secondary whitespace-nowrap">
                        {SEGMENT_LABELS[c.segment] ?? c.segment}
                      </td>
                      <td className="px-4 py-3">
                        <CampaignStatusBadge status={c.status} />
                      </td>
                      <td className="px-4 py-3 text-text-secondary whitespace-nowrap">
                        {c.startedAt
                          ? fmtDate(c.startedAt)
                          : c.scheduledAt
                            ? `Agendada: ${fmtDate(c.scheduledAt)}`
                            : '—'}
                      </td>
                      <td className="px-4 py-3 text-right text-text-secondary">
                        {c.totalRecipients.toLocaleString('pt-BR')}
                      </td>
                      <td className="px-4 py-3 text-right text-text-secondary">
                        {c.sentCount.toLocaleString('pt-BR')}
                      </td>
                      <td className="px-4 py-3 text-right text-text-secondary">
                        {c.failedCount.toLocaleString('pt-BR')}
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-2">
                          {(c.status === 'DRAFT' || c.status === 'PAUSED') && (
                            <button
                              onClick={() => setStartTarget(c)}
                              aria-label={`${c.status === 'PAUSED' ? 'Retomar' : 'Disparar'} campanha ${c.name}`}
                              className="flex items-center gap-1 rounded-lg border border-primary-700 px-2.5 py-1 text-xs font-medium text-primary-700 hover:bg-primary-700 hover:text-white transition-colors"
                            >
                              <Play className="h-3.5 w-3.5" aria-hidden="true" />
                              {c.status === 'PAUSED' ? 'Retomar' : 'Disparar'}
                            </button>
                          )}
                          {c.status === 'RUNNING' && (
                            <button
                              onClick={() => void handlePause(c)}
                              aria-label={`Pausar campanha ${c.name}`}
                              className="flex items-center gap-1 rounded-lg border border-yellow-600 px-2.5 py-1 text-xs font-medium text-yellow-700 hover:bg-yellow-600 hover:text-white transition-colors"
                            >
                              <Pause className="h-3.5 w-3.5" aria-hidden="true" />
                              Pausar
                            </button>
                          )}
                          <button
                            onClick={() => setSendsTarget(c)}
                            aria-label={`Ver envios de ${c.name}`}
                            className="flex items-center gap-1 rounded-lg border border-border-medium px-2.5 py-1 text-xs font-medium text-text-secondary hover:bg-bg-tertiary transition-colors"
                          >
                            <Eye className="h-3.5 w-3.5" aria-hidden="true" />
                            Ver envios
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </main>

      {createOpen && (
        <CreateCampaignModal
          initialSegment={initialSegment}
          onClose={() => setCreateOpen(false)}
          onCreated={() => {
            setCreateOpen(false)
            void load()
          }}
        />
      )}
      {sendsTarget && <SendsModal campaign={sendsTarget} onClose={() => setSendsTarget(null)} />}
      {startTarget && (
        <ConfirmStartModal
          campaign={startTarget}
          onClose={() => setStartTarget(null)}
          onConfirm={() => handleStart(startTarget)}
        />
      )}
    </div>
  )
}
