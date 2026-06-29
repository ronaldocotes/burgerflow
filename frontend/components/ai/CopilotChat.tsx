'use client'

import { useCallback, useEffect, useRef, useState } from 'react'
import { Sparkles, X, Trash2, Send } from 'lucide-react'
import { api, ApiError } from '@/lib/api'

const SESSION_KEY = 'mf_ai_session'

interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  toolsUsed?: string[]
}

interface AiChatResponse {
  text: string
  sessionId: string
  toolsUsed: string[]
}

interface HistoryItem {
  id: string
  role: 'user' | 'assistant'
  content: string
  toolName?: string
  createdAt: string
}

const EXAMPLES = [
  'Como está meu DRE este mês?',
  'Quais produtos vendem mais?',
  'Tenho clientes em risco de perda?',
  'Crie um cupom de 10% para clientes inativos',
]

function renderMarkdown(text: string): string {
  return text
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
}

function TypingDots() {
  return (
    <div className="flex items-center gap-1 px-3 py-2">
      <span className="h-2 w-2 animate-bounce rounded-full bg-gray-400 [animation-delay:0ms]" />
      <span className="h-2 w-2 animate-bounce rounded-full bg-gray-400 [animation-delay:150ms]" />
      <span className="h-2 w-2 animate-bounce rounded-full bg-gray-400 [animation-delay:300ms]" />
    </div>
  )
}

export function CopilotChat() {
  const [open, setOpen] = useState(false)
  const [messages, setMessages] = useState<ChatMessage[]>([])
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [historyLoaded, setHistoryLoaded] = useState(false)
  const [sessionId, setSessionId] = useState<string | null>(null)
  const [unread, setUnread] = useState(0)
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  const messagesEndRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // Carrega sessionId do localStorage na montagem
  useEffect(() => {
    const stored = window.localStorage.getItem(SESSION_KEY)
    if (stored) setSessionId(stored)
  }, [])

  // Rola para a ultima mensagem
  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [messages, loading])

  // Carrega historico quando o painel abre pela primeira vez
  const loadHistory = useCallback(() => {
    const sid = window.localStorage.getItem(SESSION_KEY)
    if (!sid) {
      setHistoryLoaded(true)
      return
    }
    api
      .get<HistoryItem[]>(`/ai/history?sessionId=${sid}`)
      .then((items) => {
        setMessages(
          items.map((item) => ({
            role: item.role,
            content: item.content,
          })),
        )
        setHistoryLoaded(true)
      })
      .catch(() => {
        setHistoryLoaded(true)
      })
  }, [])

  useEffect(() => {
    if (!open || historyLoaded) return
    loadHistory()
  }, [open, historyLoaded, loadHistory])

  // Limpa badge ao abrir
  useEffect(() => {
    if (open) setUnread(0)
  }, [open])

  function handleInputChange(e: React.ChangeEvent<HTMLTextAreaElement>) {
    setInput(e.target.value)
    const ta = e.target
    ta.style.height = 'auto'
    ta.style.height = Math.min(ta.scrollHeight, 96) + 'px'
  }

  async function sendMessage(text: string) {
    if (!text.trim() || loading) return
    setErrorMsg(null)

    const userMsg: ChatMessage = { role: 'user', content: text.trim() }
    setMessages((prev) => [...prev, userMsg])
    setInput('')
    if (textareaRef.current) textareaRef.current.style.height = 'auto'
    setLoading(true)

    try {
      const sid = window.localStorage.getItem(SESSION_KEY) ?? sessionId ?? undefined
      const body: { message: string; sessionId?: string } = { message: text.trim() }
      if (sid) body.sessionId = sid

      const res = await api.post<AiChatResponse>('/ai/chat', body)

      if (res.sessionId) {
        window.localStorage.setItem(SESSION_KEY, res.sessionId)
        setSessionId(res.sessionId)
      }

      const assistantMsg: ChatMessage = {
        role: 'assistant',
        content: res.text,
        toolsUsed: res.toolsUsed,
      }
      setMessages((prev) => [...prev, assistantMsg])

      if (!open) setUnread((n) => n + 1)
    } catch (err) {
      let msg = 'Erro ao enviar mensagem. Tente novamente.'
      if (err instanceof ApiError) {
        if (err.status === 403)
          msg = 'O Copiloto IA está desativado. Ative em Configurações > IA.'
        else if (err.status === 429)
          msg = 'Limite diário de uso atingido. Tente amanhã.'
        else if (err.status === 503)
          msg = 'IA temporariamente indisponível. Tente em alguns instantes.'
        else msg = err.message
      }
      setErrorMsg(msg)
      setMessages((prev) => prev.slice(0, -1))
    } finally {
      setLoading(false)
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      void sendMessage(input)
    }
  }

  async function handleClear() {
    const sid = window.localStorage.getItem(SESSION_KEY)
    if (sid) {
      try {
        await api.del<void>(`/ai/history?sessionId=${sid}`)
      } catch {
        // ignora erros ao limpar
      }
      window.localStorage.removeItem(SESSION_KEY)
      setSessionId(null)
    }
    setMessages([])
    setHistoryLoaded(true)
    setErrorMsg(null)
  }

  return (
    <>
      {/* Painel de chat */}
      {open && (
        <div
          role="dialog"
          aria-label="Copiloto IA"
          aria-modal="true"
          className="fixed bottom-20 right-6 z-50 flex h-96 w-80 flex-col rounded-2xl border border-gray-200 bg-white shadow-2xl sm:h-[480px] sm:w-96"
        >
          {/* Cabecalho */}
          <div className="flex items-center justify-between rounded-t-2xl border-b border-gray-100 px-4 py-3">
            <div className="flex items-center gap-2">
              <Sparkles className="h-4 w-4 text-primary-700" aria-hidden="true" />
              <span className="text-sm font-semibold text-text-primary">Copiloto IA</span>
            </div>
            <div className="flex items-center gap-1">
              <button
                onClick={() => void handleClear()}
                aria-label="Limpar histórico"
                title="Limpar histórico"
                className="rounded-lg p-1.5 text-text-secondary transition-colors hover:bg-gray-100 hover:text-text-primary"
              >
                <Trash2 className="h-4 w-4" aria-hidden="true" />
              </button>
              <button
                onClick={() => setOpen(false)}
                aria-label="Fechar chat"
                className="rounded-lg p-1.5 text-text-secondary transition-colors hover:bg-gray-100 hover:text-text-primary"
              >
                <X className="h-4 w-4" aria-hidden="true" />
              </button>
            </div>
          </div>

          {/* Area de mensagens */}
          <div className="flex-1 space-y-3 overflow-y-auto px-4 py-3">
            {/* Estado vazio com exemplos */}
            {messages.length === 0 && !loading && (
              <div className="space-y-2">
                <p className="text-center text-xs text-text-muted">
                  Pergunte sobre o seu negócio
                </p>
                {EXAMPLES.map((ex) => (
                  <button
                    key={ex}
                    onClick={() => setInput(ex)}
                    className="w-full rounded-xl border border-gray-200 px-3 py-2 text-left text-xs text-text-secondary transition-colors hover:border-primary-700 hover:text-primary-700"
                  >
                    {ex}
                  </button>
                ))}
              </div>
            )}

            {/* Mensagens */}
            {messages.map((msg, idx) => (
              <div
                key={idx}
                className={[
                  'flex flex-col gap-1',
                  msg.role === 'user' ? 'items-end' : 'items-start',
                ].join(' ')}
              >
                {msg.role === 'user' ? (
                  <div className="max-w-[85%] rounded-2xl bg-primary-700 px-3 py-2 text-sm text-white">
                    {msg.content}
                  </div>
                ) : (
                  <div
                    className="max-w-[85%] rounded-2xl bg-gray-100 px-3 py-2 text-sm text-gray-800"
                    dangerouslySetInnerHTML={{ __html: renderMarkdown(msg.content) }}
                  />
                )}

                {/* Chips de tools usadas */}
                {msg.role === 'assistant' &&
                  msg.toolsUsed &&
                  msg.toolsUsed.length > 0 && (
                    <div className="flex flex-wrap gap-1">
                      {msg.toolsUsed.map((tool) => (
                        <span
                          key={tool}
                          className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-text-secondary"
                        >
                          {tool}
                        </span>
                      ))}
                    </div>
                  )}
              </div>
            ))}

            {/* Loading: tres pontos */}
            {loading && (
              <div className="flex items-start">
                <div className="rounded-2xl bg-gray-100">
                  <TypingDots />
                </div>
              </div>
            )}

            {/* Erro */}
            {errorMsg && (
              <div role="alert" className="rounded-xl bg-red-50 px-3 py-2 text-xs text-red-700">
                {errorMsg}
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>

          {/* Rodape com input */}
          <div className="rounded-b-2xl border-t border-gray-100 px-3 py-2">
            <div className="flex items-end gap-2">
              <textarea
                ref={textareaRef}
                value={input}
                onChange={handleInputChange}
                onKeyDown={handleKeyDown}
                placeholder="Pergunte sobre seu negócio..."
                rows={1}
                disabled={loading}
                aria-label="Mensagem para o Copiloto IA"
                className="flex-1 resize-none rounded-xl border border-gray-200 bg-gray-50 px-3 py-2 text-sm text-text-primary placeholder:text-text-muted transition-colors focus:border-primary-700 focus:bg-white focus:outline-none focus:ring-1 focus:ring-primary-700 disabled:opacity-50"
                style={{ minHeight: '36px', maxHeight: '96px' }}
              />
              <button
                onClick={() => void sendMessage(input)}
                disabled={loading || !input.trim()}
                aria-label="Enviar mensagem"
                className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl bg-primary-700 text-white transition-colors hover:bg-primary-800 disabled:cursor-not-allowed disabled:opacity-40"
              >
                <Send className="h-4 w-4" aria-hidden="true" />
              </button>
            </div>
          </div>
        </div>
      )}

      {/* FAB */}
      <button
        onClick={() => setOpen((prev) => !prev)}
        aria-label={open ? 'Fechar Copiloto IA' : 'Abrir Copiloto IA'}
        aria-expanded={open}
        className="relative fixed bottom-6 right-6 z-50 flex h-14 w-14 items-center justify-center rounded-full bg-primary-700 text-white shadow-lg transition-colors hover:bg-primary-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary-700 focus-visible:ring-offset-2"
      >
        <Sparkles className="h-6 w-6" aria-hidden="true" />
        {!open && unread > 0 && (
          <span
            aria-label={`${unread} mensagens não lidas`}
            className="absolute -right-1 -top-1 flex h-5 w-5 items-center justify-center rounded-full bg-red-500 text-xs font-bold text-white"
          >
            {unread > 9 ? '9+' : String(unread)}
          </span>
        )}
      </button>
    </>
  )
}
