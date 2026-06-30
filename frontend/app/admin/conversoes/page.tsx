'use client'

import {
  useCallback,
  useEffect,
  useRef,
  useState,
} from 'react'
import { Eye, EyeOff, RefreshCw, Zap } from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import type {
  ConversionConfigPatchRequest,
  ConversionConfigResponse,
  ConversionDispatchPage,
  ConversionDispatchResponse,
  ConversionPlatform,
  ConversionStatus,
} from '@/types/conversion'

// ── Helpers ───────────────────────────────────────────────────────────────────

function fmtDate(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' })
}

// ── Badges ────────────────────────────────────────────────────────────────────

function PlatformBadge({ platform }: { platform: ConversionPlatform }) {
  const map: Record<ConversionPlatform, string> = {
    META:   'bg-blue-100 text-blue-700',
    GOOGLE: 'bg-red-100 text-red-700',
  }
  const labels: Record<ConversionPlatform, string> = {
    META:   'Meta',
    GOOGLE: 'Google',
  }
  return (
    <span className={['inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium', map[platform]].join(' ')}>
      {labels[platform]}
    </span>
  )
}

function StatusBadge({ status }: { status: ConversionStatus }) {
  const map: Record<ConversionStatus, string> = {
    PENDING: 'bg-yellow-100 text-yellow-700',
    SENT:    'bg-green-100 text-green-700',
    FAILED:  'bg-red-100 text-red-700',
    SKIPPED: 'bg-bg-tertiary text-text-secondary',
  }
  const labels: Record<ConversionStatus, string> = {
    PENDING: 'Pendente',
    SENT:    'Enviado',
    FAILED:  'Falhou',
    SKIPPED: 'Ignorado',
  }
  return (
    <span className={['inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium', map[status]].join(' ')}>
      {labels[status]}
    </span>
  )
}

// ── Skeleton ──────────────────────────────────────────────────────────────────

function TableSkeleton() {
  const COLS = 8
  return (
    <div
      className="animate-pulse rounded-2xl bg-bg-primary shadow-card overflow-hidden"
      aria-busy="true"
      aria-label="Carregando dispatches..."
    >
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border-light">
            {['Pedido', 'Plataforma', 'Status', 'Tentativas', 'Cod. resp.', 'Enviado em', 'Criado em', 'Acoes'].map((h) => (
              <th key={h} className="px-4 py-3 text-left text-sm font-semibold uppercase tracking-wider text-text-muted">{h}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {Array.from({ length: 5 }).map((_, i) => (
            <tr key={i} className="border-b border-border-light">
              {Array.from({ length: COLS }).map((__, j) => (
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

function ConfigSkeleton() {
  return (
    <div className="animate-pulse rounded-2xl bg-bg-primary shadow-card p-6 space-y-4" aria-busy="true" aria-label="Carregando configuracao...">
      {Array.from({ length: 4 }).map((_, i) => (
        <div key={i} className="h-8 rounded bg-bg-tertiary" aria-hidden="true" />
      ))}
    </div>
  )
}

// ── Toggle ────────────────────────────────────────────────────────────────────

function Toggle({
  checked,
  onChange,
  label,
  id,
}: {
  checked: boolean
  onChange: (v: boolean) => void
  label: string
  id: string
}) {
  return (
    <label htmlFor={id} className="flex min-h-11 cursor-pointer items-center gap-3">
      <div className="relative">
        <input
          id={id}
          type="checkbox"
          className="sr-only"
          checked={checked}
          onChange={(e) => onChange(e.target.checked)}
        />
        <div
          className={[
            'h-11 w-12 rounded-full transition-colors duration-200',
            checked ? 'bg-primary-700' : 'bg-bg-tertiary',
          ].join(' ')}
        />
        <div
          className={[
            'absolute top-1.5 left-1 h-8 w-8 rounded-full bg-white shadow transition-transform duration-200',
            checked ? 'translate-x-2' : 'translate-x-0',
          ].join(' ')}
        />
      </div>
      <span className="text-sm font-medium text-text-primary">{label}</span>
    </label>
  )
}

// ── Password field ────────────────────────────────────────────────────────────

function PasswordField({
  id,
  value,
  onChange,
  placeholder,
}: {
  id: string
  value: string
  onChange: (v: string) => void
  placeholder?: string
}) {
  const [visible, setVisible] = useState(false)
  return (
    <div className="relative">
      <input
        id={id}
        type={visible ? 'text' : 'password'}
        className="input-field w-full pr-12"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder={placeholder}
        autoComplete="off"
      />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        aria-label={visible ? 'Ocultar token' : 'Mostrar token'}
        className="absolute inset-y-0 right-0 flex w-11 items-center justify-center text-text-muted hover:text-text-secondary"
      >
        {visible ? <EyeOff className="h-4 w-4" aria-hidden="true" /> : <Eye className="h-4 w-4" aria-hidden="true" />}
      </button>
    </div>
  )
}

// ── Secao de configuracao ─────────────────────────────────────────────────────

interface ConfigSectionProps {
  config: ConversionConfigResponse
  onSaved: (next: ConversionConfigResponse) => void
}

function ConfigSection({ config, onSaved }: ConfigSectionProps) {
  const [enabled, setEnabled]       = useState(config.conversionTrackingEnabled)
  const [pixelId, setPixelId]       = useState(config.metaPixelId ?? '')
  const [accessToken, setAccessToken] = useState('')
  const [testCode, setTestCode]     = useState(config.metaTestEventCode ?? '')
  const [sgtmUrl, setSgtmUrl]       = useState(config.googleSgtmUrl ?? '')
  const [measureId, setMeasureId]   = useState(config.googleMeasurementId ?? '')
  const [saving, setSaving]         = useState(false)
  const [error, setError]           = useState<string | null>(null)
  const [success, setSuccess]       = useState(false)

  async function handleSave(e: React.FormEvent) {
    e.preventDefault()
    if (saving) return
    setError(null)
    setSuccess(false)
    setSaving(true)
    try {
      const body: ConversionConfigPatchRequest = {
        conversionTrackingEnabled: enabled,
        metaPixelId: pixelId.trim() || null,
        metaTestEventCode: testCode.trim() || null,
        googleSgtmUrl: sgtmUrl.trim() || null,
        googleMeasurementId: measureId.trim() || null,
      }
      if (accessToken.trim()) {
        body.metaAccessToken = accessToken.trim()
      }
      const updated = await api.patch<ConversionConfigResponse>('/config', body)
      onSaved(updated)
      setAccessToken('')
      setSuccess(true)
      setTimeout(() => setSuccess(false), 3000)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erro ao salvar configuracao')
    } finally {
      setSaving(false)
    }
  }

  return (
    <form onSubmit={handleSave} className="rounded-2xl bg-bg-primary shadow-card p-6 space-y-6">
      <div className="flex items-center justify-between">
        <h2 className="text-base font-semibold text-text-primary">Configuracao de Plataformas</h2>
        <Toggle
          id="conv-enabled"
          checked={enabled}
          onChange={setEnabled}
          label="Ativar rastreamento de conversoes"
        />
      </div>

      {/* ── Meta CAPI ── */}
      <div className="border-t border-border-light pt-5 space-y-4">
        <h3 className="text-sm font-semibold text-text-primary">Meta CAPI</h3>

        <div className="grid gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <label htmlFor="meta-pixel-id" className="text-sm font-medium text-text-secondary">
              Pixel ID
            </label>
            <input
              id="meta-pixel-id"
              type="text"
              className="input-field w-full"
              value={pixelId}
              onChange={(e) => setPixelId(e.target.value)}
              placeholder="123456789012345"
            />
          </div>

          <div className="flex flex-col gap-1.5">
            <label htmlFor="meta-access-token" className="text-sm font-medium text-text-secondary">
              Access Token{' '}
              <span className="text-text-muted">(write-only)</span>
            </label>
            <PasswordField
              id="meta-access-token"
              value={accessToken}
              onChange={setAccessToken}
              placeholder="EAAxxxxxx..."
            />
            <div className="flex items-center gap-2 mt-0.5">
              {config.hasMetaToken ? (
                <span className="inline-flex items-center gap-1 rounded-full bg-green-100 px-2.5 py-0.5 text-xs font-medium text-green-700">
                  Token configurado
                </span>
              ) : (
                <span className="inline-flex rounded-full bg-bg-tertiary px-2.5 py-0.5 text-sm font-medium text-text-secondary">
                  Nao configurado
                </span>
              )}
              <span className="text-sm text-text-muted">
                Deixe em branco para manter o token atual
              </span>
            </div>
          </div>
        </div>

        <div className="flex flex-col gap-1.5 sm:max-w-sm">
          <label htmlFor="meta-test-code" className="text-sm font-medium text-text-secondary">
            Codigo de teste
          </label>
          <input
            id="meta-test-code"
            type="text"
            className="input-field w-full"
            value={testCode}
            onChange={(e) => setTestCode(e.target.value)}
            placeholder="TEST12345"
          />
          <p className="text-sm text-text-muted">
            Preencha para testar sem afetar dados reais. Deixe vazio em producao.
          </p>
        </div>

        <p className="text-sm text-text-muted">
          Como obter o token: acesse o Events Manager do Meta &gt; Configuracoes &gt; API de
          Conversoes &gt; Gerar token de acesso.
        </p>
      </div>

      {/* ── Google sGTM ── */}
      <div className="border-t border-border-light pt-5 space-y-4">
        <h3 className="text-sm font-semibold text-text-primary">Google sGTM</h3>
        <div className="grid gap-4 sm:grid-cols-2">
          <div className="flex flex-col gap-1.5">
            <label htmlFor="google-sgtm-url" className="text-sm font-medium text-text-secondary">
              URL do sGTM
            </label>
            <input
              id="google-sgtm-url"
              type="url"
              className="input-field w-full"
              value={sgtmUrl}
              onChange={(e) => setSgtmUrl(e.target.value)}
              placeholder="https://sgtm.exemplo.com"
            />
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="google-measurement-id" className="text-sm font-medium text-text-secondary">
              Measurement ID
            </label>
            <input
              id="google-measurement-id"
              type="text"
              className="input-field w-full"
              value={measureId}
              onChange={(e) => setMeasureId(e.target.value)}
              placeholder="G-XXXXXXXX"
            />
          </div>
        </div>
      </div>

      {/* ── Acoes ── */}
      {error && (
        <p role="alert" className="rounded-lg bg-red-50 px-4 py-2 text-sm text-red-700">
          {error}
        </p>
      )}
      {success && (
        <p role="status" className="rounded-lg bg-green-50 px-4 py-2 text-sm text-green-700">
          Configuracao salva com sucesso.
        </p>
      )}
      <div className="flex justify-end border-t border-border-light pt-4">
        <button
          type="submit"
          disabled={saving}
          className="btn-primary flex items-center gap-2 disabled:opacity-50"
        >
          {saving ? (
            <span className="inline-block h-4 w-4 animate-spin rounded-full border-2 border-white border-t-transparent" aria-hidden="true" />
          ) : null}
          {saving ? 'Salvando...' : 'Salvar configuracao'}
        </button>
      </div>
    </form>
  )
}

// ── Filtro de status ──────────────────────────────────────────────────────────

type FilterStatus = 'ALL' | ConversionStatus

const FILTER_OPTIONS: { value: FilterStatus; label: string }[] = [
  { value: 'ALL',     label: 'Todos'    },
  { value: 'PENDING', label: 'Pendente' },
  { value: 'SENT',    label: 'Enviado'  },
  { value: 'FAILED',  label: 'Falhou'   },
  { value: 'SKIPPED', label: 'Ignorado' },
]

// ── Linha da tabela ───────────────────────────────────────────────────────────

function DispatchRow({
  dispatch,
  onRetry,
}: {
  dispatch: ConversionDispatchResponse
  onRetry: (id: string) => Promise<void>
}) {
  const [retrying, setRetrying] = useState(false)

  async function handleRetry() {
    if (retrying) return
    setRetrying(true)
    try {
      await onRetry(dispatch.id)
    } finally {
      setRetrying(false)
    }
  }

  return (
    <tr className="border-b border-border-light hover:bg-bg-secondary/50 transition-colors">
      <td className="px-4 py-3 text-sm font-mono text-text-primary">
        {dispatch.orderId.slice(0, 8)}...
      </td>
      <td className="px-4 py-3">
        <PlatformBadge platform={dispatch.platform} />
      </td>
      <td className="px-4 py-3">
        <StatusBadge status={dispatch.status} />
      </td>
      <td className="px-4 py-3 text-sm text-text-secondary text-center">
        {dispatch.attempts}
      </td>
      <td className="px-4 py-3 text-sm text-text-secondary">
        {dispatch.responseCode ?? '—'}
      </td>
      <td className="px-4 py-3 text-sm text-text-secondary whitespace-nowrap">
        {fmtDate(dispatch.sentAt)}
      </td>
      <td className="px-4 py-3 text-sm text-text-secondary whitespace-nowrap">
        {fmtDate(dispatch.createdAt)}
      </td>
      <td className="px-4 py-3">
        {dispatch.status === 'FAILED' && (
          <button
            onClick={handleRetry}
            disabled={retrying}
            title="Tentar novamente"
            aria-label="Tentar novamente"
            className="inline-flex items-center gap-1.5 rounded-lg px-2.5 py-1.5 text-sm font-medium text-text-secondary hover:bg-bg-tertiary disabled:opacity-50 transition-colors"
          >
            <RefreshCw className={['h-3.5 w-3.5', retrying ? 'animate-spin' : ''].join(' ')} aria-hidden="true" />
            {retrying ? 'Reenviando...' : 'Reenviar'}
          </button>
        )}
      </td>
    </tr>
  )
}

// ── Paginacao ─────────────────────────────────────────────────────────────────

function Pagination({
  page,
  totalPages,
  onPage,
}: {
  page: number
  totalPages: number
  onPage: (p: number) => void
}) {
  if (totalPages <= 1) return null
  return (
    <div className="flex items-center justify-center gap-1 pt-4" role="navigation" aria-label="Paginacao">
      <button
        onClick={() => onPage(page - 1)}
        disabled={page === 0}
        className="rounded-lg px-3 py-1.5 text-sm text-text-secondary hover:bg-bg-tertiary disabled:opacity-40 transition-colors"
      >
        Anterior
      </button>
      <span className="px-3 py-1.5 text-sm text-text-muted">
        {page + 1} / {totalPages}
      </span>
      <button
        onClick={() => onPage(page + 1)}
        disabled={page === totalPages - 1}
        className="rounded-lg px-3 py-1.5 text-sm text-text-secondary hover:bg-bg-tertiary disabled:opacity-40 transition-colors"
      >
        Proxima
      </button>
    </div>
  )
}

// ── Secao de dispatches ───────────────────────────────────────────────────────

const PAGE_SIZE = 20

type LoadState = 'loading' | 'error' | 'empty' | 'ok'

function DispatchesSection() {
  const [filterStatus, setFilterStatus] = useState<FilterStatus>('ALL')
  const [page, setPage]                 = useState(0)
  const [data, setData]                 = useState<ConversionDispatchPage | null>(null)
  const [loadState, setLoadState]       = useState<LoadState>('loading')
  const [errorMsg, setErrorMsg]         = useState<string | null>(null)

  const load = useCallback(async (p: number, status: FilterStatus) => {
    setLoadState('loading')
    setErrorMsg(null)
    try {
      const qs = new URLSearchParams({ page: String(p), size: String(PAGE_SIZE) })
      if (status !== 'ALL') qs.set('status', status)
      const result = await api.get<ConversionDispatchPage>(`/conversions/dispatches?${qs}`)
      setData(result)
      setLoadState(result.content.length === 0 ? 'empty' : 'ok')
    } catch (err) {
      setErrorMsg(err instanceof ApiError ? err.message : 'Erro ao carregar dispatches')
      setLoadState('error')
    }
  }, [])

  useEffect(() => {
    void load(page, filterStatus)
  }, [load, page, filterStatus])

  function handleFilter(status: FilterStatus) {
    setFilterStatus(status)
    setPage(0)
  }

  async function handleRetry(id: string) {
    await api.post<void>(`/conversions/dispatches/${id}/retry`, {})
    void load(page, filterStatus)
  }

  return (
    <section className="space-y-4">
      <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
        <h2 className="text-base font-semibold text-text-primary">Dispatches de Conversao</h2>
        <div className="flex flex-wrap gap-1.5" role="group" aria-label="Filtrar por status">
          {FILTER_OPTIONS.map(({ value, label }) => (
            <button
              key={value}
              onClick={() => handleFilter(value)}
              aria-pressed={filterStatus === value}
              className={[
                'inline-flex min-h-11 items-center justify-center rounded-full px-3 text-xs font-medium transition-colors duration-150',
                filterStatus === value
                  ? 'bg-primary-700 text-white'
                  : 'bg-bg-tertiary text-text-secondary hover:bg-bg-tertiary/80',
              ].join(' ')}
            >
              {label}
            </button>
          ))}
        </div>
      </div>

      {loadState === 'loading' && <TableSkeleton />}

      {loadState === 'error' && (
        <div className="rounded-2xl bg-bg-primary shadow-card p-8 text-center">
          <p className="text-sm text-text-secondary mb-3">{errorMsg ?? 'Erro ao carregar dados.'}</p>
          <button
            onClick={() => void load(page, filterStatus)}
            className="btn-outline text-sm"
          >
            Tentar novamente
          </button>
        </div>
      )}

      {loadState === 'empty' && (
        <div className="rounded-2xl bg-bg-primary shadow-card p-12 text-center">
          <Zap className="mx-auto h-10 w-10 text-text-muted mb-3" aria-hidden="true" />
          <p className="text-sm text-text-secondary">Nenhum dispatch encontrado para este filtro.</p>
        </div>
      )}

      {loadState === 'ok' && data && (
        <>
          <div className="rounded-2xl bg-bg-primary shadow-card overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border-light">
                  {['Pedido', 'Plataforma', 'Status', 'Tentativas', 'Cod. resp.', 'Enviado em', 'Criado em', 'Acoes'].map((h) => (
                    <th key={h} className="px-4 py-3 text-left text-sm font-semibold uppercase tracking-wider text-text-muted whitespace-nowrap">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {data.content.map((d) => (
                  <DispatchRow key={d.id} dispatch={d} onRetry={handleRetry} />
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={page} totalPages={data.totalPages} onPage={setPage} />
          <p className="text-center text-sm text-text-muted">
            {data.totalElements} registro{data.totalElements !== 1 ? 's' : ''} no total
          </p>
        </>
      )}
    </section>
  )
}

// ── Pagina principal ──────────────────────────────────────────────────────────

export default function ConversoesPage() {
  const [config, setConfig]         = useState<ConversionConfigResponse | null>(null)
  const [configState, setConfigState] = useState<'loading' | 'error' | 'ok'>('loading')
  const [configError, setConfigError] = useState<string | null>(null)

  useEffect(() => {
    async function loadConfig() {
      try {
        const data = await api.get<ConversionConfigResponse>('/config')
        setConfig(data)
        setConfigState('ok')
      } catch (err) {
        setConfigError(err instanceof ApiError ? err.message : 'Erro ao carregar configuracao')
        setConfigState('error')
      }
    }
    void loadConfig()
  }, [])

  return (
    <div className="flex flex-col gap-8 p-6">
      <div>
        <h1 className="text-xl font-bold text-text-primary">Conversoes</h1>
        <p className="mt-1 text-sm text-text-secondary">
          Configure Meta CAPI e Google sGTM para rastreamento server-side de conversoes.
        </p>
      </div>

      {/* Configuracao */}
      {configState === 'loading' && <ConfigSkeleton />}
      {configState === 'error' && (
        <div className="rounded-2xl bg-bg-primary shadow-card p-8 text-center">
          <p className="text-sm text-text-secondary mb-3">{configError}</p>
          <button
            onClick={() => {
              setConfigState('loading')
              void api.get<ConversionConfigResponse>('/config').then((d) => {
                setConfig(d)
                setConfigState('ok')
              }).catch((e) => {
                setConfigError(e instanceof ApiError ? e.message : 'Erro')
                setConfigState('error')
              })
            }}
            className="btn-outline text-sm"
          >
            Tentar novamente
          </button>
        </div>
      )}
      {configState === 'ok' && config && (
        <ConfigSection config={config} onSaved={setConfig} />
      )}

      {/* Dispatches */}
      <DispatchesSection />
    </div>
  )
}
