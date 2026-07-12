'use client'

// Chaves de API da plataforma (super-admin) — card do Google Maps.
// Contrato WRITE-ONLY: o backend NUNCA devolve o valor da chave. O input serve só
// para GRAVAR (PUT); o estado exibido é sempre mascarado (GET). A chave digitada
// vive apenas em estado LOCAL e é apagada após salvar — nunca vai para
// localStorage/estado global.
// Rotas: GET/PUT/DELETE /admin/api-keys/GOOGLE_MAPS, POST .../test (rate-limit 1/5s → 429).

import { useCallback, useEffect, useRef, useState } from 'react'
import {
  KeyRound,
  Eye,
  EyeOff,
  CheckCircle,
  AlertTriangle,
  XCircle,
  Server,
  Database,
  Trash2,
  FlaskConical,
  Save,
  ExternalLink,
  ChevronDown,
  Loader2,
} from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { useSuperAdminGuard } from '@/lib/use-super-admin-guard'
import {
  type PlatformApiKeyResponse,
  type PlatformApiKeyTestResponse,
  type ApiKeySource,
  formatDateTime,
} from '@/lib/platform'
import { useToast, ToastContainer } from '@/components/ui/toast'

const PROVIDER = 'GOOGLE_MAPS'
const KEY_PATH = `/admin/api-keys/${PROVIDER}`

// Badge da origem da chave: texto + ícone (nunca só cor).
const SOURCE_META: Record<ApiKeySource, { label: string; icon: typeof Server; badge: string }> = {
  DB: { label: 'gerenciada aqui', icon: Database, badge: 'bg-success-light text-success-dark' },
  ENV: { label: 'via variável de ambiente', icon: Server, badge: 'bg-bg-tertiary text-text-secondary' },
  NONE: { label: 'nenhuma', icon: AlertTriangle, badge: 'bg-warning-light text-warning-dark' },
}

function SourceBadge({ source }: { source: ApiKeySource }) {
  const meta = SOURCE_META[source] ?? SOURCE_META.NONE
  const Icon = meta.icon
  return (
    <span
      className={`inline-flex shrink-0 items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold ${meta.badge}`}
    >
      <Icon className="h-3.5 w-3.5" aria-hidden="true" />
      {meta.label}
    </span>
  )
}

function CardSkeleton() {
  return (
    <div
      aria-busy="true"
      aria-label="Carregando estado da chave"
      className="animate-pulse rounded-xl border border-border-light bg-bg-primary p-5"
    >
      <div className="flex items-center gap-3">
        <div className="h-10 w-10 rounded-lg bg-bg-tertiary" />
        <div className="flex-1 space-y-2">
          <div className="h-4 w-40 rounded bg-bg-tertiary" />
          <div className="h-3 w-64 rounded bg-bg-tertiary" />
        </div>
      </div>
      <div className="mt-5 h-11 w-full rounded-lg bg-bg-tertiary" />
    </div>
  )
}

// Bloco de status conforme o GET (DEFINED/ABSENT × source).
function StatusBanner({ data }: { data: PlatformApiKeyResponse }) {
  if (data.status === 'DEFINED') {
    const managed = data.source === 'DB'
    return (
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 rounded-lg border border-success/30 bg-success-light px-4 py-3">
        <CheckCircle className="h-5 w-5 shrink-0 text-success-dark" aria-hidden="true" />
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-success-dark">
            {managed ? 'Chave definida' : 'Usando a variável de ambiente do servidor'}
          </p>
          <p className="mt-0.5 break-all text-sm text-text-secondary">
            <code className="rounded bg-bg-primary px-1.5 py-0.5 font-mono text-xs text-text-primary">
              {data.masked ?? '••••'}
            </code>
            {managed && data.updatedAt && (
              <>
                {' '}atualizada em {formatDateTime(data.updatedAt)}
                {data.updatedBy && (
                  <>
                    {' '}por{' '}
                    <span title={data.updatedBy} className="font-medium text-text-primary">
                      {data.updatedBy.slice(0, 8)}…
                    </span>
                  </>
                )}
              </>
            )}
          </p>
        </div>
        <SourceBadge source={data.source} />
      </div>
    )
  }

  // ABSENT
  if (data.source === 'ENV') {
    return (
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 rounded-lg border border-border-light bg-bg-secondary px-4 py-3">
        <Server className="h-5 w-5 shrink-0 text-text-secondary" aria-hidden="true" />
        <p className="min-w-0 flex-1 text-sm font-medium text-text-primary">
          Usando a variável de ambiente do servidor
        </p>
        <SourceBadge source="ENV" />
      </div>
    )
  }

  // ABSENT + NONE
  return (
    <div className="flex flex-wrap items-center gap-x-3 gap-y-2 rounded-lg border border-warning/30 bg-warning-light px-4 py-3">
      <AlertTriangle className="h-5 w-5 shrink-0 text-warning-dark" aria-hidden="true" />
      <p className="min-w-0 flex-1 text-sm font-medium text-warning-dark">
        Nenhuma chave configurada — a distância de entrega e o geocode ficam indisponíveis.
      </p>
      <SourceBadge source="NONE" />
    </div>
  )
}

export default function ChavesApiPage() {
  useSuperAdminGuard()
  const { toasts, show } = useToast()

  const [data, setData] = useState<PlatformApiKeyResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)

  // Form (a chave digitada vive SÓ aqui — apagada após salvar).
  const [value, setValue] = useState('')
  const [showValue, setShowValue] = useState(false)
  const [saving, setSaving] = useState(false)

  // Teste
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<PlatformApiKeyTestResponse | null>(null)
  const [testError, setTestError] = useState<string | null>(null)

  // Remoção (confirmação em dois passos)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const [helpOpen, setHelpOpen] = useState(false)

  const abortRef = useRef<AbortController | null>(null)

  const load = useCallback(async () => {
    abortRef.current?.abort()
    const controller = new AbortController()
    abortRef.current = controller
    try {
      const res = await api.get<PlatformApiKeyResponse>(KEY_PATH, controller.signal)
      setData(res)
      setLoadError(null)
    } catch (err) {
      if (controller.signal.aborted) return
      setLoadError(err instanceof ApiError ? err.message : 'Erro ao carregar o estado da chave.')
    } finally {
      if (!controller.signal.aborted) setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
    return () => abortRef.current?.abort()
  }, [load])

  const handleSave = async () => {
    const trimmed = value.trim()
    if (!trimmed || saving) return // trava anti-duplo-clique
    setSaving(true)
    setTestResult(null)
    setTestError(null)
    try {
      await api.put<PlatformApiKeyResponse>(KEY_PATH, { value: trimmed })
      setValue('') // a chave nunca é relida em texto
      setShowValue(false)
      setConfirmingDelete(false)
      show('Chave salva com sucesso.', 'success')
      await load() // recarrega o status mascarado
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : 'Falha ao salvar a chave.'
      show(msg, 'error')
    } finally {
      setSaving(false)
    }
  }

  const handleTest = async () => {
    if (testing) return
    setTesting(true)
    setTestResult(null)
    setTestError(null)
    try {
      // POST sem corpo (o backend usa a chave vigente); {} satisfaz o cliente.
      const res = await api.post<PlatformApiKeyTestResponse>(`${KEY_PATH}/test`, {})
      setTestResult(res)
    } catch (err) {
      if (err instanceof ApiError && err.status === 429) {
        setTestError('Aguarde alguns segundos antes de testar novamente.')
      } else {
        setTestError(err instanceof ApiError ? err.message : 'Falha ao testar a chave.')
      }
    } finally {
      setTesting(false)
    }
  }

  const handleDelete = async () => {
    if (deleting) return
    setDeleting(true)
    setTestResult(null)
    setTestError(null)
    try {
      await api.del<PlatformApiKeyResponse>(KEY_PATH)
      setConfirmingDelete(false)
      show('Chave removida. Voltou a usar a variável de ambiente do servidor.', 'success')
      await load()
    } catch (err) {
      const msg = err instanceof ApiError ? err.message : 'Falha ao remover a chave.'
      show(msg, 'error')
    } finally {
      setDeleting(false)
    }
  }

  const canTest = data?.status === 'DEFINED'
  const canDelete = data?.source === 'DB'

  return (
    <main className="mx-auto max-w-3xl p-4 sm:p-6">
      <div className="mb-6">
        <h1 className="text-xl font-bold text-text-primary">Chaves de API</h1>
        <p className="mt-0.5 text-sm text-text-secondary">
          Chaves dos serviços externos usados pela plataforma. Uma chave gravada aqui fica no banco
          de controle (cifrada) e tem prioridade sobre a variável de ambiente do servidor.
        </p>
      </div>

      {loading ? (
        <CardSkeleton />
      ) : loadError && !data ? (
        <div
          role="alert"
          className="flex flex-wrap items-center gap-3 rounded-xl border border-error/30 bg-error-light px-4 py-3"
        >
          <XCircle className="h-5 w-5 shrink-0 text-error-dark" aria-hidden="true" />
          <p className="min-w-0 flex-1 text-sm font-medium text-error-dark">{loadError}</p>
          <button type="button" onClick={() => void load()} className="btn-outline">
            Tentar novamente
          </button>
        </div>
      ) : data ? (
        <div className="rounded-xl border border-border-light bg-bg-primary p-5">
          {/* Cabeçalho do card */}
          <div className="mb-4 flex items-center gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-bg-tertiary text-text-secondary">
              <KeyRound className="h-5 w-5" aria-hidden="true" />
            </div>
            <div className="min-w-0">
              <p className="font-semibold text-text-primary">Google Maps</p>
              <p className="text-sm text-text-secondary">
                Distância de entrega (Routes API) e geocode de endereços (Geocoding API)
              </p>
            </div>
          </div>

          {/* Status atual */}
          <StatusBanner data={data} />

          {/* Formulário: colar/rotacionar a chave */}
          <div className="mt-5">
            <label htmlFor="apikey-input" className="mb-1.5 block text-sm font-medium text-text-primary">
              {canDelete ? 'Substituir chave (rotacionar)' : 'Colar chave'}
            </label>
            <div className="flex flex-col gap-2 sm:flex-row">
              <div className="relative flex-1">
                <input
                  id="apikey-input"
                  type={showValue ? 'text' : 'password'}
                  value={value}
                  onChange={(e) => setValue(e.target.value)}
                  autoComplete="off"
                  spellCheck={false}
                  placeholder="AIza…"
                  className="input-field pr-12 font-mono"
                />
                <button
                  type="button"
                  onClick={() => setShowValue((v) => !v)}
                  aria-label={showValue ? 'Ocultar chave digitada' : 'Mostrar chave digitada'}
                  aria-pressed={showValue}
                  className="absolute inset-y-0 right-0 flex w-11 items-center justify-center rounded-r-lg text-text-secondary hover:text-text-primary focus:outline-none focus:ring-2 focus:ring-primary-500"
                >
                  {showValue ? (
                    <EyeOff className="h-5 w-5" aria-hidden="true" />
                  ) : (
                    <Eye className="h-5 w-5" aria-hidden="true" />
                  )}
                </button>
              </div>
              <button
                type="button"
                onClick={() => void handleSave()}
                disabled={saving || value.trim().length === 0}
                className="btn-primary shrink-0 gap-2"
              >
                {saving ? (
                  <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                ) : (
                  <Save className="h-4 w-4" aria-hidden="true" />
                )}
                {saving ? 'Salvando…' : 'Salvar'}
              </button>
            </div>
            <p className="mt-1.5 text-xs text-text-muted">
              A chave é cifrada ao salvar e nunca é exibida de volta em texto — só uma versão
              mascarada.
            </p>
          </div>

          {/* Ações: testar / remover */}
          {(canTest || canDelete) && (
            <div className="mt-5 flex flex-wrap gap-2 border-t border-border-light pt-4">
              {canTest && (
                <button
                  type="button"
                  onClick={() => void handleTest()}
                  disabled={testing}
                  className="btn-outline gap-2"
                >
                  {testing ? (
                    <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                  ) : (
                    <FlaskConical className="h-4 w-4" aria-hidden="true" />
                  )}
                  {testing ? 'Testando…' : 'Testar chave'}
                </button>
              )}
              {canDelete && !confirmingDelete && (
                <button
                  type="button"
                  onClick={() => setConfirmingDelete(true)}
                  className="btn-outline gap-2 text-error-dark hover:bg-error-light"
                >
                  <Trash2 className="h-4 w-4" aria-hidden="true" />
                  Remover chave
                </button>
              )}
            </div>
          )}

          {/* Confirmação de remoção (inline, dois passos) */}
          {confirmingDelete && (
            <div
              role="alertdialog"
              aria-label="Confirmar remoção da chave"
              className="mt-3 rounded-lg border border-error/30 bg-error-light px-4 py-3"
            >
              <p className="flex items-start gap-2 text-sm font-medium text-error-dark">
                <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
                Remover a chave gerenciada aqui? Isso volta a usar a variável de ambiente do
                servidor.
              </p>
              <div className="mt-3 flex flex-wrap gap-2">
                <button
                  type="button"
                  onClick={() => void handleDelete()}
                  disabled={deleting}
                  className="inline-flex min-h-11 items-center justify-center gap-2 rounded-lg bg-error px-4 py-2 text-sm font-medium text-white hover:bg-error-dark disabled:cursor-not-allowed disabled:opacity-50"
                >
                  {deleting ? (
                    <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" />
                  ) : (
                    <Trash2 className="h-4 w-4" aria-hidden="true" />
                  )}
                  {deleting ? 'Removendo…' : 'Confirmar remoção'}
                </button>
                <button
                  type="button"
                  onClick={() => setConfirmingDelete(false)}
                  disabled={deleting}
                  className="btn-outline"
                >
                  Cancelar
                </button>
              </div>
            </div>
          )}

          {/* Resultado do teste (texto + ícone, nunca só cor) */}
          {testResult && (
            <div
              role="status"
              className={`mt-3 flex items-start gap-2 rounded-lg px-4 py-3 text-sm font-medium ${
                testResult.ok
                  ? 'bg-success-light text-success-dark'
                  : 'bg-error-light text-error-dark'
              }`}
            >
              {testResult.ok ? (
                <CheckCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
              ) : (
                <XCircle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
              )}
              <span className="min-w-0">
                {testResult.ok
                  ? `Respondeu em ${testResult.latencyMs}ms (fonte: ${testResult.source}).`
                  : testResult.message}
              </span>
            </div>
          )}
          {testError && (
            <div
              role="alert"
              className="mt-3 flex items-start gap-2 rounded-lg bg-warning-light px-4 py-3 text-sm font-medium text-warning-dark"
            >
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
              <span className="min-w-0">{testError}</span>
            </div>
          )}
        </div>
      ) : null}

      {/* Ajuda: como obter a chave no Google Cloud */}
      <div className="mt-5 rounded-xl border border-border-light bg-bg-primary">
        <button
          type="button"
          onClick={() => setHelpOpen((v) => !v)}
          aria-expanded={helpOpen}
          aria-controls="ajuda-google"
          className="flex min-h-11 w-full items-center justify-between gap-2 px-5 py-3 text-left text-sm font-medium text-text-primary"
        >
          Como obter a chave do Google Maps
          <ChevronDown
            className={`h-4 w-4 shrink-0 text-text-secondary transition-transform ${helpOpen ? 'rotate-180' : ''}`}
            aria-hidden="true"
          />
        </button>
        {helpOpen && (
          <div id="ajuda-google" className="border-t border-border-light px-5 py-4">
            <ol className="list-decimal space-y-2 pl-5 text-sm text-text-secondary">
              <li>
                Abra o{' '}
                <a
                  href="https://console.cloud.google.com/apis/credentials"
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1 font-medium text-primary-700 underline hover:text-primary-800"
                >
                  Google Cloud Console — Credenciais
                  <ExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
                </a>{' '}
                e clique em <strong>Criar credenciais → Chave de API</strong>.
              </li>
              <li>
                Em <strong>APIs e serviços → Biblioteca</strong>, habilite a{' '}
                <strong>Routes API</strong> (distância de entrega) e a{' '}
                <strong>Geocoding API</strong> (endereço → coordenadas).
              </li>
              <li>
                Restrinja a chave (<strong>por IP do servidor</strong>) e defina um{' '}
                <strong>teto de cota</strong> para evitar cobrança inesperada.
              </li>
              <li>Copie a chave (começa com <code className="font-mono">AIza</code>) e cole no campo acima.</li>
            </ol>
          </div>
        )}
      </div>

      <ToastContainer toasts={toasts} />
    </main>
  )
}
