'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import {
  Sparkles, Send, Trash2, RefreshCw, AlertTriangle,
  BarChart2, Wrench, ShieldAlert, Timer, MessageSquare,
} from 'lucide-react'
import { api, ApiError } from '@/lib/api'
import { getToken } from '@/lib/auth'
import type {
  AiChatResponse, AiConversationEntry, AiMessage, AiMetricsResponse,
} from '@/types/ai'

// Mesma chave do widget flutuante (CopilotChat) para compartilhar a conversa
const SESSION_KEY = 'mf_ai_session'

// ─── Helpers ─────────────────────────────────────────────────────────────────

// Papel do usuario a partir do JWT (mesmo padrao do Sidebar). /ai/metrics e
// restrito a ADMIN no backend; aqui so evitamos pedir algo que dara 403.
function getUserRoleFromToken(): string | null {
  try {
    const token = getToken()
    if (!token) return null
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const payload = JSON.parse(atob(b64)) as { role?: string; roles?: string[] }
    return payload.role ?? ((Array.isArray(payload.roles) && payload.roles[0]) || null)
  } catch {
    return null
  }
}

// Nomes amigaveis das ferramentas do AiToolRegistry (transparencia nos chips)
const TOOL_LABELS: Record<string, string> = {
  get_dre: 'DRE',
  get_top_products: 'Produtos mais vendidos',
  get_rfv_summary: 'RFV',
  get_recent_orders: 'Pedidos recentes',
  get_loyalty_stats: 'Fidelidade',
  get_abandoned_carts: 'Carrinhos abandonados',
  get_customers_at_risk: 'Clientes em risco',
  create_coupon: 'Criar cupom',
  schedule_campaign: 'Agendar campanha',
}

function toolLabel(name: string): string {
  return TOOL_LABELS[name] ?? name
}

const SUGGESTIONS = [
  'Como foi meu faturamento este mês?',
  'Quais clientes estão em risco de churn?',
  'Crie um cupom de 10% para reativar inativos',
  'Quais são meus 5 produtos mais vendidos?',
]

// Dobra o historico cru (user/assistant/tool) em mensagens de chat: as linhas
// "tool" viram chips anexados a resposta do assistente que vem em seguida.
function foldHistory(items: AiConversationEntry[]): AiMessage[] {
  const msgs: AiMessage[] = []
  let pendingTools: string[] = []
  for (const e of items) {
    if (e.role === 'tool') {
      if (e.toolName) pendingTools.push(e.toolName)
      continue
    }
    if (!e.content) continue
    if (e.role === 'assistant') {
      msgs.push({ role: 'assistant', content: e.content, toolsUsed: pendingTools })
      pendingTools = []
    } else if (e.role === 'user') {
      msgs.push({ role: 'user', content: e.content })
      pendingTools = []
    }
  }
  return msgs
}

// ─── Markdown basico (negrito, listas, quebras) sem dangerouslySetInnerHTML ──

function renderInline(text: string, keyPrefix: string): React.ReactNode[] {
  return text.split(/(\*\*[^*]+\*\*)/g).map((part, i) => {
    if (part.startsWith('**') && part.endsWith('**')) {
      return <strong key={keyPrefix + '-' + i}>{part.slice(2, -2)}</strong>
    }
    return <span key={keyPrefix + '-' + i}>{part}</span>
  })
}

function Markdown({ text }: { text: string }) {
  const blocks: React.ReactNode[] = []
  let list: string[] = []
  const flushList = (key: string) => {
    if (!list.length) return
    const items = list
    list = []
    blocks.push(
      <ul key={key} className="ml-4 list-disc space-y-0.5">
        {items.map((li, i) => <li key={i}>{renderInline(li, key + '-li' + i)}</li>)}
      </ul>,
    )
  }
  text.split('\n').forEach((line, i) => {
    const t = line.trim()
    if (t.startsWith('- ') || t.startsWith('* ')) {
      list.push(t.slice(2))
      return
    }
    flushList('ul' + i)
    if (t === '') return
    blocks.push(<p key={'p' + i}>{renderInline(line, 'p' + i)}</p>)
  })
  flushList('ul-end')
  return <div className="space-y-1.5">{blocks}</div>
}

// ─── Indicador "digitando..." ────────────────────────────────────────────────

function TypingDots() {
  return (
    <div className="flex items-center gap-1 px-4 py-3" aria-label="Copiloto digitando">
      <span className="h-2 w-2 animate-bounce rounded-full bg-text-muted [animation-delay:0ms]" />
      <span className="h-2 w-2 animate-bounce rounded-full bg-text-muted [animation-delay:150ms]" />
      <span className="h-2 w-2 animate-bounce rounded-full bg-text-muted [animation-delay:300ms]" />
    </div>
  )
}

// ─── Painel de metricas (GET /ai/metrics, so ADMIN) ─────────────────────────

function MetricCard({ label, value, hint, icon: Icon }: {
  label: string
  value: string
  hint?: string
  icon: typeof BarChart2
}) {
  return (
    <div className="rounded-xl border border-border-light bg-bg-primary p-3">
      <div className="flex items-center gap-2 text-xs text-text-muted">
        <Icon className="h-3.5 w-3.5" aria-hidden="true" />
        {label}
      </div>
      <p className="mt-1 text-lg font-bold text-text-primary">{value}</p>
      {hint && <p className="text-xs text-text-muted">{hint}</p>}
    </div>
  )
}

function MetricsPanel() {
  const [metrics, setMetrics] = useState<AiMetricsResponse | null>(null)
  const [state, setState] = useState<'loading' | 'error' | 'forbidden' | 'ok'>('loading')

  const load = useCallback(() => {
    setState('loading')
    api.get<AiMetricsResponse>('/ai/metrics')
      .then((m) => { setMetrics(m); setState('ok') })
      .catch((err) => {
        if (err instanceof ApiError && err.status === 403) setState('forbidden')
        else setState('error')
      })
  }, [])

  useEffect(() => { load() }, [load])

  if (state === 'forbidden') return null

  return (
    <section aria-label="Métricas do Copiloto" className="space-y-3">
      <h2 className="flex items-center gap-2 text-sm font-semibold text-text-primary">
        <BarChart2 className="h-4 w-4 text-primary-700" aria-hidden="true" />
        Uso do Copiloto
      </h2>

      {state === 'loading' && (
        <div className="space-y-2" aria-hidden="true">
          {[0, 1, 2].map((i) => (
            <div key={i} className="h-16 animate-pulse rounded-xl bg-bg-tertiary" />
          ))}
        </div>
      )}

      {state === 'error' && (
        <div role="alert" className="rounded-xl border border-border-light bg-bg-primary p-3 text-xs text-text-secondary">
          <p>Não foi possível carregar as métricas.</p>
          <button
            onClick={load}
            className="mt-2 inline-flex items-center gap-1 font-medium text-primary-700 hover:text-primary-800"
          >
            <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
            Tentar novamente
          </button>
        </div>
      )}

      {state === 'ok' && metrics && (
        <>
          <MetricCard
            icon={MessageSquare}
            label="Perguntas hoje"
            value={String(metrics.today.requests)}
            hint={metrics.today.tokens + ' tokens'}
          />
          <MetricCard
            icon={MessageSquare}
            label="Últimos 7 dias"
            value={String(metrics.last7Days.requests)}
            hint={metrics.last7Days.tokens + ' tokens'}
          />
          <MetricCard
            icon={Timer}
            label="Latência média"
            value={metrics.avgLatencyMs + ' ms'}
          />
          {metrics.blockedRequests > 0 && (
            <MetricCard
              icon={ShieldAlert}
              label="Requisições bloqueadas"
              value={String(metrics.blockedRequests)}
            />
          )}

          <div className="rounded-xl border border-border-light bg-bg-primary p-3">
            <div className="flex items-center gap-2 text-xs text-text-muted">
              <Wrench className="h-3.5 w-3.5" aria-hidden="true" />
              Ferramentas mais usadas
            </div>
            {metrics.topTools.length === 0 ? (
              <p className="mt-2 text-xs text-text-muted">Nenhuma ferramenta usada ainda.</p>
            ) : (
              <ul className="mt-2 space-y-1.5">
                {metrics.topTools.map((t) => (
                  <li key={t.toolName} className="flex items-center justify-between gap-2 text-xs">
                    <span className="truncate text-text-secondary">{toolLabel(t.toolName)}</span>
                    <span className="shrink-0 rounded-full bg-bg-tertiary px-2 py-0.5 font-medium text-text-primary">
                      {t.callCount}x
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </>
      )}
    </section>
  )
}

// ─── Pagina ──────────────────────────────────────────────────────────────────

export default function CopilotIaPage() {
  const [messages, setMessages] = useState<AiMessage[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [historyState, setHistoryState] = useState<'loading' | 'ok' | 'error'>('loading')
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [isAdmin, setIsAdmin] = useState(false)

  const [lastFailed, setLastFailed] = useState<string | null>(null)
  const messagesEndRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // Papel lido no cliente (apos montar, para nao divergir do SSR)
  useEffect(() => {
    setIsAdmin(getUserRoleFromToken() === 'ADMIN')
  }, [])

  // Carrega o historico da sessao salva (compartilhada com o widget flutuante)
  const loadHistory = useCallback(() => {
    const sid = window.localStorage.getItem(SESSION_KEY)
    if (!sid) {
      setHistoryState('ok')
      return
    }
    setHistoryState('loading')
    api.get<AiConversationEntry[]>('/ai/history?sessionId=' + encodeURIComponent(sid))
      .then((items) => {
        setMessages(foldHistory(items))
        setHistoryState('ok')
      })
      .catch(() => setHistoryState('error'))
  }, [])

  useEffect(() => { loadHistory() }, [loadHistory])

  // Rola para a ultima mensagem
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, loading])

  function handleInputChange(e: React.ChangeEvent<HTMLTextAreaElement>) {
    setInput(e.target.value)
    const ta = e.target
    ta.style.height = 'auto'
    ta.style.height = Math.min(ta.scrollHeight, 120) + 'px'
  }

  async function sendMessage(text: string) {
    const trimmed = text.trim()
    if (!trimmed || loading) return
    setErrorMsg(null)
    setLastFailed(null)

    setMessages((prev) => [...prev, { role: 'user', content: trimmed }])
    setInput('')
    if (textareaRef.current) textareaRef.current.style.height = 'auto'
    setLoading(true)

    try {
      const sid = window.localStorage.getItem(SESSION_KEY)
      const body: { message: string; sessionId?: string } = { message: trimmed }
      if (sid) body.sessionId = sid

      const res = await api.post<AiChatResponse>('/ai/chat', body)
      if (res.sessionId) window.localStorage.setItem(SESSION_KEY, res.sessionId)

      setMessages((prev) => [
        ...prev,
        { role: 'assistant', content: res.text, toolsUsed: res.toolsUsed },
      ])
    } catch (err) {
      let msg = 'Erro ao enviar mensagem.'
      if (err instanceof ApiError) {
        if (err.status === 403) msg = 'O Copiloto IA está desativado para este restaurante.'
        else if (err.status === 429) msg = 'Limite diário de uso atingido. Tente amanhã.'
        else if (err.status === 503) msg = 'IA temporariamente indisponível. Tente em instantes.'
        else msg = err.message
      }
      setErrorMsg(msg)
      setLastFailed(trimmed)
      // remove a mensagem do usuario que falhou (o retry reenvia)
      setMessages((prev) => prev.slice(0, -1))
    } finally {
      setLoading(false)
      // foco volta ao input apos cada resposta
      textareaRef.current?.focus()
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      void sendMessage(input)
    }
  }

  async function handleClear() {
    if (!window.confirm('Limpar todo o histórico desta conversa?')) return
    const sid = window.localStorage.getItem(SESSION_KEY)
    if (sid) {
      try {
        await api.del<void>('/ai/history?sessionId=' + encodeURIComponent(sid))
      } catch {
        // limpeza local segue mesmo se a API falhar
      }
      window.localStorage.removeItem(SESSION_KEY)
    }
    setMessages([])
    setErrorMsg(null)
    setLastFailed(null)
    textareaRef.current?.focus()
  }

  const showSuggestions = historyState === 'ok' && messages.length === 0 && !loading

  return (
    <div className="flex h-full">
      {/* Coluna esquerda: metricas (so ADMIN; oculta no mobile) */}
      {isAdmin && (
        <aside className="hidden w-72 shrink-0 flex-col gap-4 overflow-y-auto border-r border-border-light bg-bg-secondary p-4 lg:flex">
          <MetricsPanel />
        </aside>
      )}

      {/* Coluna direita: chat */}
      <section className="flex min-w-0 flex-1 flex-col bg-bg-secondary" aria-label="Conversa com o Copiloto IA">
        {/* Cabecalho */}
        <header className="flex items-center justify-between border-b border-border-light bg-bg-primary px-4 py-3">
          <div>
            <h1 className="flex items-center gap-2 text-lg font-bold text-text-primary">
              <Sparkles className="h-5 w-5 text-primary-700" aria-hidden="true" />
              Copilot IA
            </h1>
            <p className="text-xs text-text-muted">
              Pergunte sobre faturamento, clientes, produtos — ou peça para criar cupons e campanhas.
            </p>
          </div>
          <button
            onClick={() => void handleClear()}
            disabled={messages.length === 0}
            aria-label="Limpar histórico da conversa"
            title="Limpar histórico"
            className="flex min-h-11 min-w-11 items-center justify-center gap-1.5 rounded-lg px-3 text-sm text-text-secondary transition-colors hover:bg-bg-tertiary hover:text-text-primary disabled:cursor-not-allowed disabled:opacity-40"
          >
            <Trash2 className="h-4 w-4" aria-hidden="true" />
            <span className="hidden sm:inline">Limpar</span>
          </button>
        </header>

        {/* Area de mensagens */}
        <div
          role="log"
          aria-live="polite"
          aria-label="Mensagens da conversa"
          className="flex-1 space-y-4 overflow-y-auto px-4 py-4 sm:px-6"
        >
          {historyState === 'loading' && (
            <div className="space-y-3" aria-hidden="true">
              <div className="ml-auto h-10 w-2/3 max-w-sm animate-pulse rounded-2xl bg-bg-tertiary" />
              <div className="h-20 w-3/4 max-w-md animate-pulse rounded-2xl bg-bg-tertiary" />
            </div>
          )}

          {historyState === 'error' && (
            <div role="alert" className="mx-auto flex max-w-md flex-col items-center gap-2 rounded-2xl bg-bg-primary p-6 text-center shadow-card">
              <AlertTriangle className="h-6 w-6 text-warning" aria-hidden="true" />
              <p className="text-sm text-text-secondary">Não foi possível carregar o histórico da conversa.</p>
              <button
                onClick={loadHistory}
                className="inline-flex items-center gap-1.5 text-sm font-medium text-primary-700 hover:text-primary-800"
              >
                <RefreshCw className="h-4 w-4" aria-hidden="true" />
                Tentar novamente
              </button>
            </div>
          )}

          {/* Estado vazio: chips de sugestao */}
          {showSuggestions && (
            <div className="mx-auto flex max-w-lg flex-col items-center gap-4 pt-8 text-center">
              <Sparkles className="h-10 w-10 text-primary-700" aria-hidden="true" />
              <div>
                <p className="font-semibold text-text-primary">Como posso ajudar no seu negócio hoje?</p>
                <p className="mt-1 text-sm text-text-muted">
                  Tenho acesso ao seu DRE, pedidos, clientes e cupons.
                </p>
              </div>
              <div className="flex flex-wrap justify-center gap-2">
                {SUGGESTIONS.map((s) => (
                  <button
                    key={s}
                    onClick={() => void sendMessage(s)}
                    className="rounded-full border border-border-medium bg-bg-primary px-4 py-2 text-sm text-text-secondary transition-colors hover:border-primary-700 hover:text-primary-700"
                  >
                    {s}
                  </button>
                ))}
              </div>
            </div>
          )}

          {/* Mensagens */}
          {messages.map((msg, idx) => (
            <div
              key={idx}
              className={['flex flex-col gap-1.5', msg.role === 'user' ? 'items-end' : 'items-start'].join(' ')}
            >
              {msg.role === 'user' ? (
                <div className="max-w-[85%] whitespace-pre-wrap rounded-2xl rounded-br-md bg-primary-700 px-4 py-2.5 text-sm text-white sm:max-w-[70%]">
                  {msg.content}
                </div>
              ) : (
                <div className="max-w-[85%] rounded-2xl rounded-bl-md bg-bg-primary px-4 py-2.5 text-sm text-text-primary shadow-card sm:max-w-[70%]">
                  <Markdown text={msg.content} />
                </div>
              )}

              {/* Chips das ferramentas usadas na resposta */}
              {msg.role === 'assistant' && msg.toolsUsed && msg.toolsUsed.length > 0 && (
                <div className="flex flex-wrap gap-1.5" aria-label="Ferramentas consultadas">
                  {msg.toolsUsed.map((tool, i) => (
                    <span
                      key={tool + i}
                      className="inline-flex items-center gap-1 rounded-full bg-bg-tertiary px-2.5 py-0.5 text-xs text-text-secondary"
                    >
                      <Wrench className="h-3 w-3" aria-hidden="true" />
                      {toolLabel(tool)}
                    </span>
                  ))}
                </div>
              )}
            </div>
          ))}

          {/* Digitando... */}
          {loading && (
            <div className="flex items-start">
              <div className="rounded-2xl rounded-bl-md bg-bg-primary shadow-card">
                <TypingDots />
              </div>
            </div>
          )}

          {/* Erro inline com retry */}
          {errorMsg && (
            <div role="alert" className="mx-auto flex max-w-md items-start gap-2 rounded-xl bg-error-light px-4 py-3 text-sm text-error-dark">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
              <div>
                <p>{errorMsg}</p>
                {lastFailed && (
                  <button
                    onClick={() => void sendMessage(lastFailed)}
                    className="mt-1 inline-flex items-center gap-1 font-medium underline"
                  >
                    <RefreshCw className="h-3.5 w-3.5" aria-hidden="true" />
                    Tentar novamente
                  </button>
                )}
              </div>
            </div>
          )}

          <div ref={messagesEndRef} />
        </div>

        {/* Rodape com input */}
        <footer className="border-t border-border-light bg-bg-primary px-4 py-3 sm:px-6">
          <div className="mx-auto flex max-w-3xl items-end gap-2">
            <textarea
              ref={textareaRef}
              value={input}
              onChange={handleInputChange}
              onKeyDown={handleKeyDown}
              placeholder="Pergunte sobre seu negócio... (Enter envia, Shift+Enter quebra linha)"
              rows={1}
              disabled={loading}
              aria-label="Mensagem para o Copilot IA"
              className="flex-1 resize-none rounded-xl border border-border-medium bg-bg-secondary px-4 py-2.5 text-sm text-text-primary placeholder:text-text-muted transition-colors focus:border-primary-700 focus:bg-bg-primary focus:outline-none focus:ring-1 focus:ring-primary-700 disabled:opacity-50"
              style={{ minHeight: '44px', maxHeight: '120px' }}
            />
            <button
              onClick={() => void sendMessage(input)}
              disabled={loading || !input.trim()}
              aria-label="Enviar mensagem"
              className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-primary-700 text-white transition-colors hover:bg-primary-800 disabled:cursor-not-allowed disabled:opacity-40"
            >
              <Send className="h-5 w-5" aria-hidden="true" />
            </button>
          </div>
        </footer>
      </section>
    </div>
  )
}
