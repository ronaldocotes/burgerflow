'use client'

import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
} from 'react'
import { Check, Copy, Link2, Plus, Trash2 } from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { useModalA11y } from '@/lib/use-modal-a11y'
import type {
  TrackingLinkCreateRequest,
  TrackingLinksPage,
  TrackingLinkResponse,
  TrackingSummaryResponse,
} from '@/types/tracking'

// ── Tipos locais ──────────────────────────────────────────────────────────────

type Period = '7d' | '30d' | '90d' | 'custom'
type LoadState = 'loading' | 'error' | 'ok' | 'empty'

// ── Helpers ───────────────────────────────────────────────────────────────────

function fmtCurrency(cents: number): string {
  return (cents / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' })
}

function fmtPercent(rate: number): string {
  return (
    (rate * 100).toLocaleString('pt-BR', {
      minimumFractionDigits: 1,
      maximumFractionDigits: 1,
    }) + '%'
  )
}

function fmtDate(iso: string): string {
  return new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short' })
}

function getPeriodRange(
  period: Period,
  fromCustom: string,
  toCustom: string,
): { from: string; to: string } {
  if (period === 'custom') return { from: fromCustom, to: toCustom }
  const days = period === '7d' ? 7 : period === '30d' ? 30 : 90
  const to = new Date()
  const from = new Date()
  from.setDate(from.getDate() - days)
  const fmt = (d: Date) => d.toISOString().split('T')[0]
  return { from: fmt(from), to: fmt(to) }
}

// ── Badge ─────────────────────────────────────────────────────────────────────

function LinkStatusBadge({ active }: { active: boolean }) {
  return (
    <span
      className={[
        'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium',
        active
          ? 'bg-green-100 text-green-700'
          : 'bg-bg-tertiary text-text-secondary',
      ].join(' ')}
    >
      {active ? 'Ativo' : 'Inativo'}
    </span>
  )
}

// ── Skeleton ──────────────────────────────────────────────────────────────────

function TableSkeleton({ cols }: { cols: number }) {
  return (
    <div
      className="animate-pulse rounded-2xl bg-bg-primary shadow-card overflow-hidden"
      aria-busy="true"
      aria-label="Carregando..."
    >
      <table className="w-full text-sm">
        <tbody>
          {Array.from({ length: 4 }).map((_, i) => (
            <tr key={i} className="border-b border-border-light">
              {Array.from({ length: cols }).map((__, j) => (
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

// ── Chips de fonte ────────────────────────────────────────────────────────────

const SOURCE_CHIPS = ['whatsapp', 'instagram', 'google', 'panfleto', 'ifood', 'tiktok']

// ── Modal criar link ──────────────────────────────────────────────────────────

interface CreateLinkModalProps {
  onClose: () => void
  onCreated: () => void
}

function CreateLinkModal({ onClose, onCreated }: CreateLinkModalProps) {
  const ref = useRef<HTMLDivElement>(null)
  useModalA11y(ref as React.RefObject<HTMLElement | null>, onClose)
  const titleId = useId()

  const [name, setName] = useState('')
  const [source, setSource] = useState('')
  const [medium, setMedium] = useState('')
  const [campaign, setCampaign] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [createdLink, setCreatedLink] = useState<TrackingLinkResponse | null>(null)
  const [copied, setCopied] = useState(false)

  function copyShareUrl(url: string) {
    void navigator.clipboard.writeText(url).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (saving) return
    setError(null)
    const body: TrackingLinkCreateRequest = {
      name: name.trim(),
      source: source.trim(),
      ...(medium.trim() ? { medium: medium.trim() } : {}),
      ...(campaign.trim() ? { campaign: campaign.trim() } : {}),
    }
    setSaving(true)
    try {
      const link = await api.post<TrackingLinkResponse>('/tracking/links', body)
      setCreatedLink(link)
      onCreated()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao criar link.')
    } finally {
      setSaving(false)
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
      <div
        ref={ref}
        className="relative z-10 w-full max-w-lg rounded-2xl bg-bg-primary shadow-dropdown overflow-y-auto max-h-[90vh]"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-border-light px-6 py-4">
          <h2 id={titleId} className="text-base font-semibold text-text-primary">
            Novo link de rastreamento
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

        {/* Estado: link criado — exibe shareUrl */}
        {createdLink ? (
          <div className="px-6 py-6 flex flex-col gap-4">
            <div className="flex flex-col items-center gap-2 py-2 text-center">
              <div className="flex h-12 w-12 items-center justify-center rounded-full bg-green-100">
                <Check className="h-6 w-6 text-green-700" aria-hidden="true" />
              </div>
              <p className="text-base font-semibold text-text-primary">Link criado com sucesso!</p>
              <p className="text-sm text-text-muted">Compartilhe este link na sua campanha.</p>
            </div>
            <div className="rounded-xl border border-border-light bg-bg-secondary p-3">
              <p className="mb-2 text-xs font-semibold uppercase tracking-wider text-text-muted">
                URL de rastreamento
              </p>
              <p className="break-all font-mono text-sm text-text-primary">{createdLink.shareUrl}</p>
            </div>
            <button
              onClick={() => copyShareUrl(createdLink.shareUrl)}
              className="btn-primary flex items-center justify-center gap-2"
            >
              {copied ? (
                <>
                  <Check className="h-4 w-4" aria-hidden="true" />
                  Copiado!
                </>
              ) : (
                <>
                  <Copy className="h-4 w-4" aria-hidden="true" />
                  Copiar URL
                </>
              )}
            </button>
            <button onClick={onClose} className="btn-outline">
              Fechar
            </button>
          </div>
        ) : (
          /* Estado: formulario */
          <form onSubmit={(e) => void handleSubmit(e)} className="space-y-5 px-6 py-5">
            {/* Nome */}
            <div>
              <label htmlFor="link-name" className="mb-1 block text-sm font-medium text-text-primary">
                Nome do link
              </label>
              <input
                id="link-name"
                type="text"
                required
                value={name}
                onChange={(e) => setName(e.target.value)}
                className="input-field w-full"
                placeholder="Ex: Campanha Junho WhatsApp"
              />
            </div>

            {/* Fonte */}
            <div>
              <label htmlFor="link-source" className="mb-1 block text-sm font-medium text-text-primary">
                Fonte (source)
              </label>
              <input
                id="link-source"
                type="text"
                required
                value={source}
                onChange={(e) => setSource(e.target.value)}
                className="input-field w-full"
                placeholder="Ex: whatsapp"
              />
              <div className="mt-2 flex flex-wrap gap-2">
                {SOURCE_CHIPS.map((chip) => (
                  <button
                    key={chip}
                    type="button"
                    onClick={() => setSource(chip)}
                    className={[
                      'rounded-full border px-3 py-0.5 text-xs font-medium transition-colors',
                      source === chip
                        ? 'border-primary-700 bg-primary-700 text-white'
                        : 'border-border-medium bg-bg-secondary text-text-secondary hover:bg-bg-tertiary hover:text-text-primary',
                    ].join(' ')}
                  >
                    {chip}
                  </button>
                ))}
              </div>
            </div>

            {/* Midia */}
            <div>
              <label htmlFor="link-medium" className="mb-1 block text-sm font-medium text-text-primary">
                Midia (medium){' '}
                <span className="font-normal text-text-muted">(opcional)</span>
              </label>
              <input
                id="link-medium"
                type="text"
                value={medium}
                onChange={(e) => setMedium(e.target.value)}
                className="input-field w-full"
                placeholder="Ex: messaging"
              />
            </div>

            {/* Campanha */}
            <div>
              <label htmlFor="link-campaign" className="mb-1 block text-sm font-medium text-text-primary">
                Campanha{' '}
                <span className="font-normal text-text-muted">(opcional)</span>
              </label>
              <input
                id="link-campaign"
                type="text"
                value={campaign}
                onChange={(e) => setCampaign(e.target.value)}
                className="input-field w-full"
                placeholder="Ex: junho-2026"
              />
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
                {saving ? 'Criando...' : 'Criar link'}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  )
}

// ── Constantes ────────────────────────────────────────────────────────────────

const PERIOD_OPTIONS: { value: Period; label: string }[] = [
  { value: '7d', label: '7 dias' },
  { value: '30d', label: '30 dias' },
  { value: '90d', label: '90 dias' },
  { value: 'custom', label: 'Personalizado' },
]

// ── Pagina principal ──────────────────────────────────────────────────────────

export default function TrackingPage() {
  // Periodo
  const [period, setPeriod] = useState<Period>('30d')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')

  // Summary ROAS
  const [summaryList, setSummaryList] = useState<TrackingSummaryResponse[]>([])
  const [summaryState, setSummaryState] = useState<LoadState>('loading')

  // Links
  const [links, setLinks] = useState<TrackingLinkResponse[]>([])
  const [linksState, setLinksState] = useState<LoadState>('loading')
  const [linksPage, setLinksPage] = useState(0)
  const [totalLinksPages, setTotalLinksPages] = useState(1)

  // UI
  const [createOpen, setCreateOpen] = useState(false)
  const [copiedId, setCopiedId] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)

  // ── Carregar summary ──────────────────────────────────────────────────────

  const loadSummary = useCallback(async (p: Period, from: string, to: string) => {
    if (p === 'custom' && (!from || !to)) return
    setSummaryState('loading')
    const range = getPeriodRange(p, from, to)
    try {
      const data = await api.get<TrackingSummaryResponse[]>(
        `/tracking/summary?from=${range.from}&to=${range.to}`,
      )
      const sorted = [...data].sort((a, b) => b.revenueCents - a.revenueCents)
      setSummaryList(sorted)
      setSummaryState(sorted.length === 0 ? 'empty' : 'ok')
    } catch {
      setSummaryState('error')
    }
  }, [])

  useEffect(() => {
    void loadSummary(period, fromDate, toDate)
  }, [loadSummary, period, fromDate, toDate])

  // ── Carregar links ────────────────────────────────────────────────────────

  const loadLinks = useCallback(async (p: number) => {
    setLinksState('loading')
    try {
      const data = await api.get<TrackingLinksPage>(`/tracking/links?page=${p}&size=20`)
      setLinks(data.content)
      setTotalLinksPages(data.totalPages)
      setLinksPage(data.number)
      setLinksState(data.content.length === 0 ? 'empty' : 'ok')
    } catch {
      setLinksState('error')
    }
  }, [])

  useEffect(() => {
    void loadLinks(0)
  }, [loadLinks])

  // ── Acoes ─────────────────────────────────────────────────────────────────

  async function handleToggleActive(link: TrackingLinkResponse) {
    setActionError(null)
    try {
      const updated = await api.patch<TrackingLinkResponse>(`/tracking/links/${link.id}`, {
        active: !link.active,
      })
      setLinks((prev) => prev.map((l) => (l.id === updated.id ? updated : l)))
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : 'Erro ao atualizar link.')
    }
  }

  async function handleDelete(link: TrackingLinkResponse) {
    setActionError(null)
    try {
      await api.del<void>(`/tracking/links/${link.id}`)
      setLinks((prev) => {
        const next = prev.filter((l) => l.id !== link.id)
        if (next.length === 0) setLinksState('empty')
        return next
      })
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : 'Erro ao excluir link.')
    }
  }

  function handleCopyUrl(url: string, id: string) {
    void navigator.clipboard.writeText(url).then(() => {
      setCopiedId(id)
      setTimeout(() => setCopiedId((prev) => (prev === id ? null : prev)), 2000)
    })
  }

  return (
    <div className="flex min-h-screen flex-col bg-bg-secondary">
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8">

        {/* Cabecalho */}
        <div className="mb-8 flex items-center gap-3">
          <Link2 className="h-6 w-6 text-primary-700" aria-hidden="true" />
          <h2 className="text-2xl font-bold text-text-primary">Rastreamento</h2>
        </div>

        {/* ── Secao 1: Dashboard ROAS ───────────────────────────────────── */}
        <section aria-labelledby="roas-heading" className="mb-10">
          <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
            <h3 id="roas-heading" className="text-base font-semibold text-text-primary">
              Performance por link
            </h3>
            {/* Seletor de periodo */}
            <div className="flex flex-wrap items-center gap-2">
              {PERIOD_OPTIONS.map((opt) => (
                <button
                  key={opt.value}
                  onClick={() => setPeriod(opt.value)}
                  className={[
                    'rounded-lg border px-3 py-1.5 text-xs font-medium transition-colors',
                    period === opt.value
                      ? 'border-primary-700 bg-primary-700 text-white'
                      : 'border-border-medium bg-bg-primary text-text-secondary hover:bg-bg-tertiary',
                  ].join(' ')}
                >
                  {opt.label}
                </button>
              ))}
              {period === 'custom' && (
                <div className="flex items-center gap-2">
                  <input
                    type="date"
                    value={fromDate}
                    onChange={(e) => setFromDate(e.target.value)}
                    className="input-field py-1 text-xs"
                    aria-label="Data inicial"
                  />
                  <span className="text-xs text-text-muted">ate</span>
                  <input
                    type="date"
                    value={toDate}
                    onChange={(e) => setToDate(e.target.value)}
                    className="input-field py-1 text-xs"
                    aria-label="Data final"
                  />
                </div>
              )}
            </div>
          </div>

          {/* 1. Carregando */}
          {summaryState === 'loading' && <TableSkeleton cols={6} />}

          {/* 2. Erro */}
          {summaryState === 'error' && (
            <div
              role="alert"
              className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-10 text-center shadow-card"
            >
              <p className="text-sm text-text-secondary">
                Nao foi possivel carregar os dados de performance.
              </p>
              <button
                className="btn-outline text-sm"
                onClick={() => void loadSummary(period, fromDate, toDate)}
              >
                Tentar novamente
              </button>
            </div>
          )}

          {/* 3. Vazio */}
          {summaryState === 'empty' && (
            <div className="flex flex-col items-center gap-3 rounded-2xl bg-bg-primary p-12 text-center shadow-card">
              <Link2 className="h-10 w-10 text-text-muted" aria-hidden="true" />
              <p className="text-sm font-medium text-text-primary">
                Crie seu primeiro link de rastreamento
              </p>
              <p className="text-sm text-text-muted">
                Os dados de performance aparecerao aqui assim que houver cliques.
              </p>
            </div>
          )}

          {/* 4. Ok */}
          {summaryState === 'ok' && (
            <div className="rounded-2xl bg-bg-primary shadow-card overflow-hidden">
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b border-border-light">
                      {['Link', 'Fonte', 'Cliques', 'Conversoes', 'Receita', 'Taxa de conv.'].map((h) => (
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
                    {summaryList.map((s) => (
                      <tr key={s.trackingLinkId} className="border-b border-border-light hover:bg-bg-secondary">
                        <td
                          className="px-4 py-3 font-medium text-text-primary max-w-[180px] truncate"
                          title={s.name}
                        >
                          {s.name}
                        </td>
                        <td className="px-4 py-3 text-text-secondary">{s.source}</td>
                        <td className="px-4 py-3 text-right text-text-secondary">
                          {s.clicks.toLocaleString('pt-BR')}
                        </td>
                        <td className="px-4 py-3 text-right text-text-secondary">
                          {s.conversions.toLocaleString('pt-BR')}
                        </td>
                        <td className="px-4 py-3 text-right font-medium text-text-primary">
                          {fmtCurrency(s.revenueCents)}
                        </td>
                        <td className="px-4 py-3 text-right text-text-secondary">
                          {fmtPercent(s.conversionRate)}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </section>

        {/* ── Secao 2: Gerenciar links ──────────────────────────────────── */}
        <section aria-labelledby="links-heading">
          <div className="mb-4 flex items-center justify-between gap-4">
            <h3 id="links-heading" className="text-base font-semibold text-text-primary">
              Links de rastreamento
            </h3>
            <button
              onClick={() => setCreateOpen(true)}
              className="btn-primary flex items-center gap-2"
            >
              <Plus className="h-4 w-4" aria-hidden="true" />
              Novo link
            </button>
          </div>

          {actionError && (
            <div role="alert" className="mb-4 rounded-xl bg-red-50 px-4 py-3 text-sm text-red-700">
              {actionError}
            </div>
          )}

          {/* 1. Carregando */}
          {linksState === 'loading' && <TableSkeleton cols={7} />}

          {/* 2. Erro */}
          {linksState === 'error' && (
            <div
              role="alert"
              className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-12 text-center shadow-card"
            >
              <p className="text-sm text-text-secondary">Nao foi possivel carregar os links.</p>
              <button className="btn-primary" onClick={() => void loadLinks(linksPage)}>
                Tentar novamente
              </button>
            </div>
          )}

          {/* 3. Vazio */}
          {linksState === 'empty' && (
            <div className="flex flex-col items-center gap-4 rounded-2xl bg-bg-primary p-16 text-center shadow-card">
              <Link2 className="h-12 w-12 text-text-muted" aria-hidden="true" />
              <p className="text-base font-medium text-text-primary">Nenhum link criado ainda</p>
              <p className="text-sm text-text-muted">
                Crie links rastreados para medir o desempenho de cada canal de marketing.
              </p>
              <button
                onClick={() => setCreateOpen(true)}
                className="btn-primary flex items-center gap-2"
              >
                <Plus className="h-4 w-4" aria-hidden="true" />
                Novo link
              </button>
            </div>
          )}

          {/* 4. Ok */}
          {linksState === 'ok' && (
            <>
              <div className="rounded-2xl bg-bg-primary shadow-card overflow-hidden">
                <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead>
                      <tr className="border-b border-border-light">
                        {['Nome', 'Slug', 'Fonte', 'Cliques', 'Status', 'Criado em', 'Acoes'].map((h) => (
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
                      {links.map((link) => (
                        <tr key={link.id} className="border-b border-border-light hover:bg-bg-secondary">
                          <td
                            className="px-4 py-3 font-medium text-text-primary max-w-[160px] truncate"
                            title={link.name}
                          >
                            {link.name}
                          </td>
                          <td className="px-4 py-3 font-mono text-xs text-text-secondary">
                            {link.slug}
                          </td>
                          <td className="px-4 py-3 text-text-secondary">{link.source}</td>
                          <td className="px-4 py-3 text-right text-text-secondary">
                            {link.clickCount.toLocaleString('pt-BR')}
                          </td>
                          <td className="px-4 py-3">
                            <LinkStatusBadge active={link.active} />
                          </td>
                          <td className="px-4 py-3 text-text-secondary whitespace-nowrap">
                            {fmtDate(link.createdAt)}
                          </td>
                          <td className="px-4 py-3">
                            <div className="flex items-center gap-2">
                              {/* Copiar URL */}
                              <button
                                onClick={() => handleCopyUrl(link.shareUrl, link.id)}
                                aria-label={`Copiar URL de ${link.name}`}
                                className="flex items-center gap-1 rounded-lg border border-border-medium px-2.5 py-1 text-xs font-medium text-text-secondary hover:bg-bg-tertiary transition-colors"
                              >
                                {copiedId === link.id ? (
                                  <>
                                    <Check className="h-3.5 w-3.5 text-green-600" aria-hidden="true" />
                                    Copiado!
                                  </>
                                ) : (
                                  <>
                                    <Copy className="h-3.5 w-3.5" aria-hidden="true" />
                                    Copiar
                                  </>
                                )}
                              </button>
                              {/* Ativar / Desativar */}
                              <button
                                onClick={() => void handleToggleActive(link)}
                                aria-label={link.active ? `Desativar ${link.name}` : `Ativar ${link.name}`}
                                className={[
                                  'flex items-center gap-1 rounded-lg border px-2.5 py-1 text-xs font-medium transition-colors',
                                  link.active
                                    ? 'border-yellow-500 text-yellow-700 hover:bg-yellow-50'
                                    : 'border-primary-700 text-primary-700 hover:bg-green-50',
                                ].join(' ')}
                              >
                                {link.active ? 'Desativar' : 'Ativar'}
                              </button>
                              {/* Excluir */}
                              <button
                                onClick={() => void handleDelete(link)}
                                aria-label={`Excluir link ${link.name}`}
                                className="flex items-center justify-center rounded-lg border border-red-300 p-1 text-red-500 hover:bg-red-50 transition-colors"
                              >
                                <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
                              </button>
                            </div>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>

              {/* Paginacao */}
              {totalLinksPages > 1 && (
                <div className="mt-4 flex items-center justify-between">
                  <button
                    disabled={linksPage === 0}
                    onClick={() => void loadLinks(linksPage - 1)}
                    className="btn-outline text-sm disabled:opacity-40"
                  >
                    Anterior
                  </button>
                  <span className="text-sm text-text-muted">
                    Pagina {linksPage + 1} de {totalLinksPages}
                  </span>
                  <button
                    disabled={linksPage >= totalLinksPages - 1}
                    onClick={() => void loadLinks(linksPage + 1)}
                    className="btn-outline text-sm disabled:opacity-40"
                  >
                    Proxima
                  </button>
                </div>
              )}
            </>
          )}
        </section>
      </main>

      {createOpen && (
        <CreateLinkModal
          onClose={() => setCreateOpen(false)}
          onCreated={() => {
            void loadLinks(0)
          }}
        />
      )}
    </div>
  )
}
