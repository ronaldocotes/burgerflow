// Tipos do Copilot IA (Fase 4). Espelham os DTOs do backend (AiDtos.kt):
// POST /ai/chat, GET|DELETE /ai/history, GET /ai/metrics.

/** Resposta do Copiloto (POST /ai/chat). */
export interface AiChatResponse {
  text: string
  sessionId: string
  toolsUsed: string[]
  /** Tokens (prompt+completion) consumidos na resposta; 0 quando bloqueado. */
  tokensUsed: number
}

/** Entrada do historico de uma sessao (GET /ai/history). role: user | assistant | tool. */
export interface AiConversationEntry {
  id: string
  role: 'user' | 'assistant' | 'tool' | string
  content: string | null
  /** Nome da ferramenta executada (quando role = "tool"). */
  toolName: string | null
  createdAt: string
}

/** Metricas de um intervalo (hoje / ultimos 7 dias). */
export interface DayMetrics {
  requests: number
  tokens: number
  avgTokensPerRequest: number
}

/** Uso de uma ferramenta no periodo (top tools). */
export interface ToolUsageStats {
  toolName: string
  callCount: number
  avgLatencyMs: number
}

/** Painel de observabilidade do Copiloto (GET /ai/metrics, so ADMIN). */
export interface AiMetricsResponse {
  today: DayMetrics
  last7Days: DayMetrics
  topTools: ToolUsageStats[]
  avgLatencyMs: number
  blockedRequests: number
}

/** Mensagem renderizada no chat (estado do frontend, ja "dobrada" do historico). */
export interface AiMessage {
  role: 'user' | 'assistant'
  content: string
  toolsUsed?: string[]
}
